package com.openwallet.core.network.families.evm;

import com.openwallet.core.network.AddressStatus;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.network.interfaces.TransactionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.evm.EvmTransaction;

import org.bitcoinj.core.Sha256Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

/**
 * EVM blockchain connection — communicates with Ethereum-compatible nodes via JSON-RPC.
 * Supports eth_getBlockByNumber, eth_getTransactionByHash, eth_getBalance,
 * eth_sendRawTransaction, eth_subscribe (newHeads / logs), etc.
 *
 * TODO: Implement actual HTTP/WebSocket JSON-RPC transport.
 */
public class EvmServerClient implements BlockchainConnection<EvmTransaction> {
    private static final Logger log = LoggerFactory.getLogger(EvmServerClient.class);

    private final String rpcUrl;
    private final long chainId;
    private boolean connected = false;
    private final CopyOnWriteArrayList<ConnectionEventListener> eventListeners = new CopyOnWriteArrayList<>();

    /**
     * @param rpcUrl  JSON-RPC endpoint (e.g., "https://mainnet.infura.io/v3/YOUR_KEY")
     * @param chainId EIP-155 chain ID (1 for Ethereum mainnet, 137 for Polygon, 56 for BSC)
     */
    public EvmServerClient(String rpcUrl, long chainId) {
        this.rpcUrl = rpcUrl;
        this.chainId = chainId;
    }

    @Override
    public void getBlock(int height, TransactionEventListener<EvmTransaction> listener) {
        log.info("getBlock({}) via {}", height, rpcUrl);
        // TODO: eth_getBlockByNumber → parse transactions → listener.onTransactionUpdate()
    }

    @Override
    public void subscribeToBlockchain(TransactionEventListener<EvmTransaction> listener) {
        log.info("subscribeToBlockchain via {}", rpcUrl);
        // TODO: eth_subscribe("newHeads") over WebSocket or poll eth_blockNumber
    }

    @Override
    public void subscribeToAddresses(List<AbstractAddress> addresses, TransactionEventListener<EvmTransaction> listener) {
        log.info("subscribeToAddresses({} addrs) via {}", addresses.size(), rpcUrl);
        // TODO: eth_subscribe("logs") with address filter, or poll eth_getTransactionCount + eth_getBalance
    }

    @Override
    public void getHistoryTx(AddressStatus status, TransactionEventListener<EvmTransaction> listener) {
        log.info("getHistoryTx for {} via {}", status, rpcUrl);
        // TODO: Use Etherscan-like API or event logs to fetch transaction history
    }

    @Override
    public void getTransaction(Sha256Hash txHash, TransactionEventListener<EvmTransaction> listener) {
        log.info("getTransaction({}) via {}", txHash, rpcUrl);
        // TODO: eth_getTransactionByHash + eth_getTransactionReceipt
    }

    @Override
    public void broadcastTx(EvmTransaction tx, TransactionEventListener<EvmTransaction> listener) {
        log.info("broadcastTx({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: eth_sendRawTransaction
    }

    @Override
    public boolean broadcastTxSync(EvmTransaction tx) {
        log.info("broadcastTxSync({}) via {}", tx.getHashAsString(), rpcUrl);
        // TODO: eth_sendRawTransaction synchronously
        return false;
    }

    @Override
    public void ping(@Nullable String versionString) {
        log.debug("ping({})", versionString);
        // TODO: eth_blockNumber as a lightweight ping
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
