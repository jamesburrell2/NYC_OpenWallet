package com.openwallet.core.network;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.families.BitFamily;
import com.openwallet.core.coins.families.CardanoFamily;
import com.openwallet.core.coins.families.ChiaFamily;
import com.openwallet.core.coins.families.EvmFamily;
import com.openwallet.core.coins.families.NxtFamily;
import com.openwallet.core.coins.families.SolanaFamily;
import com.openwallet.core.exceptions.UnsupportedCoinTypeException;
import com.openwallet.core.network.families.cardano.CardanoServerClient;
import com.openwallet.core.network.families.chia.ChiaServerClient;
import com.openwallet.core.network.families.evm.EvmServerClient;
import com.openwallet.core.network.families.solana.SolanaServerClient;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.wallet.WalletAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.List;

import javax.annotation.Nullable;

/**
 * @author John L. Jegutanis
 */
public class ServerClients {
    private static final Logger log = LoggerFactory.getLogger(ServerClient.class);
    private final ConnectivityHelper connectivityHelper;
    private HashMap<CoinType, BlockchainConnection> connections = new HashMap<>();
    private HashMap<CoinType, CoinAddress> addresses = new HashMap<>();


    private static ConnectivityHelper DEFAULT_CONNECTIVITY_HELPER = new ConnectivityHelper() {
        @Override
        public boolean isConnected() { return true; }
    };
    private File cacheDir;
    private int cacheSize;

    public ServerClients(List<CoinAddress> coins) {
        // Supply a dumb ConnectivityHelper that reports that connection is always available
        this(coins, DEFAULT_CONNECTIVITY_HELPER);
    }

    public ServerClients(List<CoinAddress> coinAddresses, ConnectivityHelper connectivityHelper) {
        this.connectivityHelper = connectivityHelper;
        setupAddresses(coinAddresses);
    }

    private void setupAddresses(List<CoinAddress> coins) {
        for (CoinAddress coinAddress : coins) {
            addresses.put(coinAddress.getType(), coinAddress);
        }
    }

    /**
     * Dynamically add a coin address not present in the static DEFAULT_COINS_SERVERS.
     * Used to inject the NYC ElectrumX server address at runtime.
     */
    public void addCoinAddress(CoinAddress coinAddress) {
        addresses.put(coinAddress.getType(), coinAddress);
    }

    public void resetAccount(WalletAccount account) {
        BlockchainConnection connection = connections.get(account.getCoinType());
        if (connection == null) return;
        connection.addEventListener(account);
        connection.resetConnection();
    }

    public boolean hasAddress(CoinType type) {
        return addresses.containsKey(type);
    }

    public void startAsync(WalletAccount account) {
        if (account == null) {
            log.warn("Provided wallet account is null, not doing anything");
            return;
        }
        CoinType type = account.getCoinType();
        if (!hasAddress(type)) {
            log.warn("No server configured for {}, skipping connection", type.getName());
            return;
        }
        BlockchainConnection connection = getConnection(type);
        connection.addEventListener(account);
        connection.startAsync();
    }

    private BlockchainConnection getConnection(CoinType type) {
        if (connections.containsKey(type)) return connections.get(type);
        // Try to create a connection
        if (addresses.containsKey(type)) {
            if (type instanceof BitFamily) {
                ServerClient client = new ServerClient(addresses.get(type), connectivityHelper);
                client.setCacheDir(cacheDir, cacheSize);
                connections.put(type, client);
                return client;
            } else if (type instanceof NxtFamily) {
                NxtServerClient client = new NxtServerClient(addresses.get(type), connectivityHelper);
                client.setCacheDir(cacheDir, cacheSize);
                connections.put(type, client);
                return client;
            } else if (type instanceof EvmFamily) {
                CoinAddress addr = addresses.get(type);
                // getHost() returns the full HTTPS URL; toString() returns debug format
                String rpcUrl = addr.getAddresses().get(0).getHost();
                EvmServerClient client = new EvmServerClient(rpcUrl, type.getBip44Index());
                connections.put(type, client);
                return client;
            } else if (type instanceof SolanaFamily) {
                CoinAddress addr = addresses.get(type);
                String rpcUrl = addr.getAddresses().get(0).getHost();
                SolanaServerClient client = new SolanaServerClient(rpcUrl);
                connections.put(type, client);
                return client;
            } else if (type instanceof CardanoFamily) {
                CoinAddress addr = addresses.get(type);
                String apiUrl = addr.getAddresses().get(0).getHost();
                CardanoServerClient client = new CardanoServerClient(apiUrl, "");
                connections.put(type, client);
                return client;
            } else if (type instanceof ChiaFamily) {
                CoinAddress addr = addresses.get(type);
                String rpcUrl = addr.getAddresses().get(0).getHost();
                ChiaServerClient client = new ChiaServerClient(rpcUrl);
                connections.put(type, client);
                return client;
            } else {
                throw new UnsupportedCoinTypeException(type);
            }
        } else {
            // Should not happen
            throw new RuntimeException("Tried to create connection for an unknown server.");
        }
    }

    public void stopAllAsync() {
        for (BlockchainConnection client : connections.values()) {
            client.stopAsync();
        }
        connections.clear();
    }

    public void ping() {
        ping(null);
    }

    public void ping(@Nullable String versionString) {
        for (final CoinType type : connections.keySet()) {
            BlockchainConnection connection = connections.get(type);
            if (connection.isActivelyConnected()) connection.ping(versionString);
        }
    }

    public void resetConnections() {
        for (final CoinType type : connections.keySet()) {
            BlockchainConnection connection = connections.get(type);
            if (connection.isActivelyConnected()) connection.resetConnection();
        }
    }

    public void setCacheDir(File cacheDir, int cacheSize) {
        this.cacheDir = cacheDir;
        this.cacheSize = cacheSize;
    }

    public void startOrResetAccountAsync(WalletAccount account) {
        if (connections.containsKey(account.getCoinType())) {
            resetAccount(account);
        } else {
            startAsync(account);
        }
    }
}
