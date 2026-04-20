package com.openwallet.core.network.families.cardano;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.cardano.CardanoTransaction;

import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

/**
 * Cardano blockchain connection — communicates via Blockfrost REST API or
 * cardano-submit-api / Ogmios for tx submission.
 *
 * Blockfrost endpoints:
 * - /blocks/{hash_or_number}
 * - /addresses/{address}/transactions
 * - /addresses/{address}
 * - /txs/{hash}
 * - /tx/submit
 *
 * TODO: Implement actual HTTP transport with Blockfrost API key.
 */
public class CardanoServerClient implements BlockchainConnection<CardanoTransaction> {
    private static final Logger log = LoggerFactory.getLogger(CardanoServerClient.class);

    private final String apiUrl;
    private final String apiKey;
    private boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();

    /**
     * @param apiUrl  Blockfrost API base URL (e.g., "https://cardano-mainnet.blockfrost.io/api/v0")
     * @param apiKey  Blockfrost project API key
     */
    public CardanoServerClient(String apiUrl, String apiKey) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void getBlock(int height, TransactionEventListener<CardanoTransaction> listener) {
        log.info("getBlock({}) via {}", height, apiUrl);
        // TODO: GET /blocks/{number} → parse block data
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<CardanoTransaction> listener) {
        log.info("subscribeToBlockchain via {}", apiUrl);
        // TODO: Poll /blocks/latest periodically (Blockfrost has no WebSocket)
    }

    @Override
    public void subscribeToAddresses(List<AbstractAddress> addresses, TransactionEventListener<CardanoTransaction> listener) {
        log.info("subscribeToAddresses({} addrs) via {}", addresses.size(), apiUrl);
        // TODO: Poll /addresses/{addr}/transactions for each address
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<CardanoTransaction> listener) {
        log.info("getHistoryTx for {} via {}", status, apiUrl);
        // TODO: GET /addresses/{addr}/transactions?order=desc
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<CardanoTransaction> listener) {
        log.info("getTransaction({}) via {}", txHash, apiUrl);
        // TODO: GET /txs/{hash} + /txs/{hash}/utxos
    }

    @Override
    public void broadcastTx(CardanoTransaction tx, TransactionEventListener<CardanoTransaction> listener) {
        log.info("broadcastTx({}) via {}", tx.getHashAsString(), apiUrl);
        // TODO: POST /tx/submit with CBOR-serialized transaction
    }

    @Override
    public boolean broadcastTxSync(CardanoTransaction tx) {
        log.info("broadcastTxSync({}) via {}", tx.getHashAsString(), apiUrl);
        // TODO: POST /tx/submit synchronously
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        // TODO: GET / (Blockfrost root endpoint returns server info)
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
        log.info("Starting Cardano client at {}", apiUrl);
        connected = true;
        for (ConnectionEventListener l : eventListeners) l.onConnection(this);
    }
}
