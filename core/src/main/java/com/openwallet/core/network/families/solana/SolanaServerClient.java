package com.openwallet.core.network.families.solana;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.solana.SolanaTransaction;

import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

/**
 * Solana blockchain connection — communicates with Solana validators via JSON-RPC.
 */
public class SolanaServerClient implements BlockchainConnection<SolanaTransaction> {
    private static final Logger log = LoggerFactory.getLogger(SolanaServerClient.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;

    private final String rpcUrl;
    private volatile boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicLong idCounter = new AtomicLong(1);

    /**
     * @param rpcUrl JSON-RPC endpoint (e.g., "https://api.mainnet-beta.solana.com")
     */
    public SolanaServerClient(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    // ---- Internal JSON-RPC helper ----

    @Nullable
    private JSONObject jsonRpcCall(String method, JSONArray params) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", method);
            req.put("params", params);
            req.put("id", idCounter.getAndIncrement());

            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);

            String endpoint = rpcUrl.startsWith("http") ? rpcUrl : "https://" + rpcUrl;
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int status = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(status >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                            StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();

            return new JSONObject(sb.toString());
        } catch (IOException | JSONException e) {
            log.error("Solana JSON-RPC call failed for {}: {}", method, e.getMessage());
            return null;
        }
    }

    // ---- Public helpers ----

    /**
     * Fetch the SOL balance (in lamports) for an address asynchronously.
     */
    public void getBalance(final AbstractAddress address,
                           final TransactionEventListener<SolanaTransaction> listener) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray params = new JSONArray();
                    params.put(address.toString());
                    JSONObject resp = jsonRpcCall("getBalance", params);
                    if (resp != null && !resp.has("error")) {
                        long lamports = resp.getJSONObject("result").getLong("value");
                        log.info("Balance for {}: {} lamports", address, lamports);
                        if (listener != null) {
                            listener.onTransactionUpdate(
                                    new SolanaTransaction(address.getType(), lamports));
                        }
                    }
                } catch (Exception e) {
                    log.error("getBalance failed", e);
                }
            }
        });
    }

    /**
     * Broadcast a base64-encoded signed transaction.
     * @return true if the node accepted the transaction (returns a signature string).
     */
    public boolean sendTransaction(final String base64Tx) {
        try {
            JSONArray params = new JSONArray();
            params.put(base64Tx);
            JSONObject config = new JSONObject();
            config.put("encoding", "base64");
            params.put(config);

            JSONObject resp = jsonRpcCall("sendTransaction", params);
            if (resp != null && resp.has("result")) {
                String sig = resp.getString("result");
                log.info("Broadcast accepted, signature={}", sig);
                return true;
            }
        } catch (Exception e) {
            log.error("sendTransaction failed", e);
        }
        return false;
    }

    // ---- BlockchainConnection interface ----

    @Override
    public void getBlock(int height, TransactionEventListener<SolanaTransaction> listener) {
        log.debug("getBlock(slot={}) via {}", height, rpcUrl);
        // TODO: getBlock with slot number
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<SolanaTransaction> listener) {
        log.debug("subscribeToBlockchain via {}", rpcUrl);
        // TODO: slotSubscribe via WebSocket
    }

    @Override
    public void subscribeToAddresses(final List<AbstractAddress> addresses,
                                     final TransactionEventListener<SolanaTransaction> listener) {
        for (AbstractAddress addr : addresses) {
            getBalance(addr, listener);
        }
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<SolanaTransaction> listener) {
        log.debug("getHistoryTx for {} via {}", status, rpcUrl);
        // TODO: getSignaturesForAddress → getTransaction for each
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<SolanaTransaction> listener) {
        log.debug("getTransaction({}) via {}", txHash, rpcUrl);
        // TODO: getTransaction by signature
    }

    @Override
    public void broadcastTx(SolanaTransaction tx, TransactionEventListener<SolanaTransaction> listener) {
        log.debug("broadcastTx({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: build base64 signed tx and call sendTransaction
    }

    @Override
    public boolean broadcastTxSync(SolanaTransaction tx) {
        log.debug("broadcastTxSync({}) via {}", tx.getHashAsString(), rpcUrl);
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject resp = jsonRpcCall("getHealth", new JSONArray());
                    if (resp != null) log.debug("Solana ping OK: {}", resp.optString("result"));
                } catch (Exception e) {
                    log.debug("Solana ping failed", e);
                }
            }
        });
    }

    @Override
    public void addEventListener(ConnectionEventListener listener) {
        eventListeners.add(listener);
    }

    @Override
    public void resetConnection() {
        stopAsync();
        startAsync();
    }

    @Override
    public void stopAsync() {
        connected = false;
        for (ConnectionEventListener l : eventListeners) l.onDisconnect();
    }

    @Override
    public boolean isActivelyConnected() { return connected; }

    @Override
    public void startAsync() {
        log.info("Starting Solana client at {}", rpcUrl);
        connected = true;
        for (ConnectionEventListener l : eventListeners) l.onConnection(this);
    }
}
