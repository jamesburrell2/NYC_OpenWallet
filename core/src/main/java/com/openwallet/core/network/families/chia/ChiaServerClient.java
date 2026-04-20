package com.openwallet.core.network.families.chia;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.chia.ChiaTransaction;

import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

/**
 * Chia blockchain connection — communicates with Chia full node via HTTPS RPC.
 *
 * Chia full node RPC endpoints:
 * - /get_blockchain_state
 * - /get_block_record_by_height
 * - /get_coin_records_by_puzzle_hash
 * - /get_coin_record_by_name
 * - /push_tx
 * - /get_additions_and_removals
 *
 * Requires TLS client certificate authentication to the full node.
 *
 * TODO: Implement actual HTTPS transport with cert-based auth.
 */
public class ChiaServerClient implements BlockchainConnection<ChiaTransaction> {
    private static final Logger log = LoggerFactory.getLogger(ChiaServerClient.class);

    private final String rpcUrl;
    private boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();

    /**
     * @param rpcUrl Full node RPC endpoint (e.g., "https://localhost:8555")
     */
    public ChiaServerClient(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    @Override
    public void getBlock(int height, TransactionEventListener<ChiaTransaction> listener) {
        log.info("getBlock({}) via {}", height, rpcUrl);
        // TODO: POST /get_block_record_by_height + /get_additions_and_removals
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<ChiaTransaction> listener) {
        log.info("subscribeToBlockchain via {}", rpcUrl);
        // TODO: Poll /get_blockchain_state for peak height changes
    }

    @Override
    public void subscribeToAddresses(List<AbstractAddress> addresses, TransactionEventListener<ChiaTransaction> listener) {
        log.info("subscribeToAddresses({} addrs) via {}", addresses.size(), rpcUrl);
        // TODO: Poll /get_coin_records_by_puzzle_hash for each address puzzle hash
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<ChiaTransaction> listener) {
        log.info("getHistoryTx for {} via {}", status, rpcUrl);
        // TODO: /get_coin_records_by_puzzle_hash with include_spent_coins=true
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<ChiaTransaction> listener) {
        log.info("getTransaction({}) via {}", txHash, rpcUrl);
        // TODO: /get_coin_record_by_name
    }

    @Override
    public void broadcastTx(ChiaTransaction tx, TransactionEventListener<ChiaTransaction> listener) {
        log.info("broadcastTx({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: POST /push_tx with spend bundle JSON
    }

    @Override
    public boolean broadcastTxSync(ChiaTransaction tx) {
        log.info("broadcastTxSync({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: POST /push_tx synchronously
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        // TODO: POST /get_blockchain_state as a lightweight ping
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
        log.info("Starting Chia client at {}", rpcUrl);
        connected = true;
        for (ConnectionEventListener l : eventListeners) l.onConnection(this);
    }
}
