package com.openwallet.core.network.families.evm;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.evm.EvmTransaction;

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
import java.math.BigInteger;
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
 * EVM blockchain connection — communicates with Ethereum-compatible nodes via JSON-RPC.
 */
public class EvmServerClient implements BlockchainConnection<EvmTransaction> {
    private static final Logger log = LoggerFactory.getLogger(EvmServerClient.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;

    private final String rpcUrl;
    private final long chainId;
    private volatile boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicLong idCounter = new AtomicLong(1);

    /**
     * @param rpcUrl  JSON-RPC endpoint (e.g., "https://mainnet.infura.io/v3/KEY")
     * @param chainId EIP-155 chain ID (1 = Ethereum, 56 = BSC)
     */
    public EvmServerClient(String rpcUrl, long chainId) {
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
    }

    // ---- Internal JSON-RPC helper ----

    /**
     * Execute a JSON-RPC call and return the result as a String, or null on error.
     */
    @Nullable
    private String jsonRpcCall(String method, JSONArray params) {
        try {
            JSONObject req = new JSONObject();
            req.put("jsonrpc", "2.0");
            req.put("method", method);
            req.put("params", params);
            req.put("id", idCounter.getAndIncrement());

            byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);

            // Normalise URL — strip leading "https://" prefix in port-only ServerAddress strings
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

            JSONObject resp = new JSONObject(sb.toString());
            if (resp.has("error")) {
                log.warn("JSON-RPC error for {}: {}", method, resp.getJSONObject("error"));
                return null;
            }
            return resp.optString("result", null);
        } catch (IOException | JSONException e) {
            log.error("JSON-RPC call failed for {}: {}", method, e.getMessage());
            return null;
        }
    }

    // ---- Public helpers ----

    /**
     * Fetch the balance for an address in wei, asynchronously calling the listener with the result.
     * The balance is delivered via {@link TransactionEventListener#onTransactionUpdate} with a
     * sentinel {@link EvmTransaction} that carries only the balance amount.
     */
    public void getBalance(final AbstractAddress address,
                           final TransactionEventListener<EvmTransaction> listener) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONArray params = new JSONArray();
                    params.put(address.toString());
                    params.put("latest");
                    String hexBalance = jsonRpcCall("eth_getBalance", params);
                    if (hexBalance != null && hexBalance.startsWith("0x")) {
                        BigInteger wei = new BigInteger(hexBalance.substring(2), 16);
                        log.info("Balance for {}: {} wei", address, wei);
                        // Deliver balance via a balance-only EvmTransaction sentinel
                        if (listener != null) {
                            listener.onTransactionUpdate(new EvmTransaction(address.getType(), wei));
                        }
                    }
                } catch (Exception e) {
                    log.error("getBalance failed", e);
                }
            }
        });
    }

    /**
     * Broadcast a signed raw transaction hex string.
     * @return true if the node accepted the transaction (returns a tx hash).
     */
    public boolean sendRawTransaction(final String signedHex) {
        try {
            JSONArray params = new JSONArray();
            params.put(signedHex);
            String txHash = jsonRpcCall("eth_sendRawTransaction", params);
            if (txHash != null && txHash.startsWith("0x")) {
                log.info("Broadcast accepted, txHash={}", txHash);
                return true;
            }
        } catch (Exception e) {
            log.error("sendRawTransaction failed", e);
        }
        return false;
    }

    // ---- BlockchainConnection interface ----

    @Override
    public void getBlock(int height, TransactionEventListener<EvmTransaction> listener) {
        log.debug("getBlock({}) via {}", height, rpcUrl);
        // TODO: eth_getBlockByNumber
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<EvmTransaction> listener) {
        log.debug("subscribeToBlockchain via {}", rpcUrl);
        // TODO: poll eth_blockNumber periodically
    }

    @Override
    public void subscribeToAddresses(final List<AbstractAddress> addresses,
                                     final TransactionEventListener<EvmTransaction> listener) {
        // Fetch balances for all subscribed addresses immediately
        for (AbstractAddress addr : addresses) {
            getBalance(addr, listener);
        }
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<EvmTransaction> listener) {
        log.debug("getHistoryTx for {} via {}", status, rpcUrl);
        // TODO: Use Etherscan-compatible API for transaction history
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<EvmTransaction> listener) {
        log.debug("getTransaction({}) via {}", txHash, rpcUrl);
        // TODO: eth_getTransactionByHash
    }

    @Override
    public void broadcastTx(EvmTransaction tx, TransactionEventListener<EvmTransaction> listener) {
        log.debug("broadcastTx({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: build signed hex and call sendRawTransaction
    }

    @Override
    public boolean broadcastTxSync(EvmTransaction tx) {
        log.debug("broadcastTxSync({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: build signed hex and call sendRawTransaction synchronously
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    String blockNum = jsonRpcCall("eth_blockNumber", new JSONArray());
                    if (blockNum != null) {
                        log.debug("ping OK — latest block: {}", blockNum);
                    }
                } catch (Exception e) {
                    log.debug("ping failed", e);
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
        log.info("Resetting EVM connection to {}", rpcUrl);
        stopAsync();
        startAsync();
    }

    @Override
    public void stopAsync() {
        connected = false;
        for (ConnectionEventListener l : eventListeners) {
            l.onDisconnect();
        }
    }

    @Override
    public boolean isActivelyConnected() {
        return connected;
    }

    @Override
    public void startAsync() {
        log.info("Starting EVM client for chainId={} at {}", chainId, rpcUrl);
        connected = true;
        for (ConnectionEventListener l : eventListeners) {
            l.onConnection(this);
        }
    }

    public String getRpcUrl() { return rpcUrl; }
    public long getChainId() { return chainId; }
}
