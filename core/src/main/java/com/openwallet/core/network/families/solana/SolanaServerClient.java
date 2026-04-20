package com.openwallet.core.network.families.solana;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.solana.SolanaTransaction;

import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

/**
 * Solana blockchain connection — communicates with Solana validators via JSON-RPC.
 * Supports getBlock, getTransaction, getBalance, getSignaturesForAddress,
 * sendTransaction, accountSubscribe, etc.
 *
 * TODO: Implement actual HTTP/WebSocket JSON-RPC transport.
 */
public class SolanaServerClient implements BlockchainConnection<SolanaTransaction> {
    private static final Logger log = LoggerFactory.getLogger(SolanaServerClient.class);

    private final String rpcUrl;
    private boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();

    /**
     * @param rpcUrl JSON-RPC endpoint (e.g., "https://api.mainnet-beta.solana.com")
     */
    public SolanaServerClient(String rpcUrl) {
        this.rpcUrl = rpcUrl;
    }

    @Override
    public void getBlock(int height, TransactionEventListener<SolanaTransaction> listener) {
        log.info("getBlock(slot={}) via {}", height, rpcUrl);
        // TODO: getBlock with slot number
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<SolanaTransaction> listener) {
        log.info("subscribeToBlockchain via {}", rpcUrl);
        // TODO: slotSubscribe via WebSocket
    }

    @Override
    public void subscribeToAddresses(List<AbstractAddress> addresses, TransactionEventListener<SolanaTransaction> listener) {
        log.info("subscribeToAddresses({} addrs) via {}", addresses.size(), rpcUrl);
        // TODO: accountSubscribe for each address via WebSocket
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<SolanaTransaction> listener) {
        log.info("getHistoryTx for {} via {}", status, rpcUrl);
        // TODO: getSignaturesForAddress → getTransaction for each
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<SolanaTransaction> listener) {
        log.info("getTransaction({}) via {}", txHash, rpcUrl);
        // TODO: getTransaction by signature
    }

    @Override
    public void broadcastTx(SolanaTransaction tx, TransactionEventListener<SolanaTransaction> listener) {
        log.info("broadcastTx({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: sendTransaction
    }

    @Override
    public boolean broadcastTxSync(SolanaTransaction tx) {
        log.info("broadcastTxSync({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: sendTransaction synchronously + confirmTransaction
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        // TODO: getHealth or getVersion
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
