package com.openwallet.core.wallet.families.evm;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.exceptions.TransactionBroadcastException;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.network.interfaces.ConnectionEventListener;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.AbstractWallet;
import com.openwallet.core.wallet.SendRequest;
import com.openwallet.core.wallet.SignedMessage;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.core.wallet.WalletAccountEventListener;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterException;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.wallet.RedeemData;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.spongycastle.crypto.params.KeyParameter;

/**
 * EVM family wallet — single-address account model (one address per chain).
 * Tracks an address derived from the HD seed at m/44'/60'/0'/0/0 (BIP-44).
 */
public class EvmFamilyWallet extends AbstractWallet<EvmTransaction, EvmAddress>
        implements Serializable {
    private static final long serialVersionUID = 1L;

    @Nullable private transient Wallet wallet;
    @Nullable private transient BlockchainConnection<EvmTransaction> blockchainConnection;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    private EvmAddress address;
    private final Map<Sha256Hash, EvmTransaction> txMap = new ConcurrentHashMap<>();
    private Value balance;
    private boolean isConnected = false;
    private boolean isLoading = false;

    public EvmFamilyWallet(CoinType coinType, String id) {
        super(coinType, id);
        this.balance = coinType.value(0);
    }

    public EvmFamilyWallet(CoinType coinType, String id, DeterministicKey rootKey) {
        super(coinType, id);
        // FAIL CLOSED: Ethereum addresses require Keccak-256 of the uncompressed public
        // key. The previous SHA-256-based derivation produced addresses with no spendable
        // key — funds sent to them would be lost. Refuse to construct until a correct
        // Keccak-256 derivation is implemented.
        throw new UnsupportedOperationException(
                "EVM key-based address derivation not implemented (requires Keccak-256)");
    }

    public EvmFamilyWallet(CoinType coinType, String id, String addressStr) throws AddressMalformedException {
        super(coinType, id);
        this.address = EvmAddress.from(coinType, addressStr);
        this.balance = coinType.value(0);
    }

    // ---- Address methods ----

    @Override
    public EvmAddress getChangeAddress() { return address; }

    @Override
    public EvmAddress getReceiveAddress() { return address; }

    @Override
    public AbstractAddress getRefundAddress(boolean isManualAddressManagement) { return address; }

    @Override
    public EvmAddress getReceiveAddress(boolean isManualAddressManagement) { return address; }

    @Override
    public boolean hasUsedAddresses() { return address != null; }

    @Override
    public boolean canCreateNewAddresses() { return false; }

    @Override
    public List<AbstractAddress> getActiveAddresses() {
        return address != null ? Collections.singletonList((AbstractAddress) address) : Collections.<AbstractAddress>emptyList();
    }

    @Override
    public void markAddressAsUsed(AbstractAddress addr) { }

    @Override
    public boolean isAddressMine(AbstractAddress addr) {
        return address != null && address.equals(addr);
    }

    // ---- Balance ----

    @Override
    public Value getBalance() { return balance; }

    public void setBalance(Value balance) { this.balance = balance; }

    // ---- Connectivity ----

    @Override
    public boolean isConnected() { return isConnected; }

    @Override
    public boolean isLoading() { return isLoading; }

    @Override
    public void disconnect() {
        isConnected = false;
        if (blockchainConnection != null) {
            blockchainConnection.stopAsync();
            blockchainConnection = null;
        }
    }

    @Override
    public void refresh() {
        if (blockchainConnection != null) {
            blockchainConnection.resetConnection();
        }
    }

    public void setBlockchainConnection(BlockchainConnection<EvmTransaction> conn) {
        this.blockchainConnection = conn;
    }

    // ---- Transactions ----

    @Override
    public boolean broadcastTxSync(AbstractTransaction tx) throws TransactionBroadcastException {
        if (blockchainConnection != null && tx instanceof EvmTransaction) {
            return blockchainConnection.broadcastTxSync((EvmTransaction) tx);
        }
        return false;
    }

    @Override
    public void broadcastTx(AbstractTransaction tx) throws TransactionBroadcastException {
        if (blockchainConnection != null && tx instanceof EvmTransaction) {
            blockchainConnection.broadcastTx((EvmTransaction) tx, null);
        }
    }

    @Nullable
    @Override
    public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
        throw new RuntimeException("Not implemented");
    }

    @Nullable
    @Override
    public ECKey findKeyFromPubKey(byte[] pubkey) {
        throw new RuntimeException("Not implemented");
    }

    @Nullable
    @Override
    public RedeemData findRedeemDataFromScriptHash(byte[] scriptHash) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public EvmTransaction getTransaction(String txId) {
        for (Map.Entry<Sha256Hash, EvmTransaction> e : txMap.entrySet()) {
            if (e.getValue().getHashAsString().equals(txId)) return e.getValue();
        }
        return null;
    }

    @Override
    public Map<Sha256Hash, EvmTransaction> getPendingTransactions() {
        Map<Sha256Hash, EvmTransaction> pending = new HashMap<>();
        for (Map.Entry<Sha256Hash, EvmTransaction> e : txMap.entrySet()) {
            if (e.getValue().getConfidenceType() == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING) {
                pending.put(e.getKey(), e.getValue());
            }
        }
        return pending;
    }

    @Override
    public Map<Sha256Hash, EvmTransaction> getTransactions() {
        return Collections.unmodifiableMap(txMap);
    }

    public void addTransaction(EvmTransaction tx) {
        txMap.put(tx.getHash(), tx);
    }

    // ---- Wallet binding ----

    @Override
    public void setWallet(Wallet wallet) { this.wallet = wallet; }

    @Override
    public Wallet getWallet() { return wallet; }

    @Override
    public void walletSaveLater() { if (wallet != null) wallet.saveLater(); }

    @Override
    public void walletSaveNow() { if (wallet != null) wallet.saveNow(); }

    // ---- Encryption (not yet supported for EVM family) ----

    @Override
    public boolean isEncryptable() { return false; }

    @Override
    public boolean isEncrypted() { return false; }

    @Override
    public KeyCrypter getKeyCrypter() { return null; }

    @Override
    public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
        throw new UnsupportedOperationException("EVM wallet encryption not yet implemented");
    }

    @Override
    public void decrypt(KeyParameter aesKey) {
        throw new UnsupportedOperationException("EVM wallet decryption not yet implemented");
    }

    // ---- Events ----

    @Override
    public void addEventListener(WalletAccountEventListener listener) {
        addEventListener(listener, Threading.USER_THREAD);
    }

    @Override
    public void addEventListener(WalletAccountEventListener listener, Executor executor) {
        listeners.add(new ListenerRegistration(listener, executor));
    }

    @Override
    public boolean removeEventListener(WalletAccountEventListener listener) {
        return ListenerRegistration.removeFromList(listener, listeners);
    }

    // ---- Keys ----

    @Override
    public boolean isNew() { return txMap.isEmpty(); }

    @Override
    public byte[] getPublicKey() { return null; }

    @Override
    public void maybeInitializeAllKeys() { }

    @Override
    public String getPublicKeyMnemonic() { return null; }

    @Override
    public String getPublicKeySerialized() {
        return address != null ? address.toString() : null;
    }

    // ---- Send/Sign ----

    @Override
    public SendRequest getEmptyWalletRequest(AbstractAddress destination) throws WalletAccountException {
        throw new WalletAccountException("EVM send not yet implemented");
    }

    @Override
    public SendRequest getSendToRequest(AbstractAddress destination, Value amount) throws WalletAccountException {
        throw new WalletAccountException("EVM send not yet implemented");
    }

    @Override
    public void completeTransaction(SendRequest request) throws WalletAccountException {
        throw new WalletAccountException("EVM transaction completion not yet implemented");
    }

    @Override
    public void signTransaction(SendRequest request) {
        throw new UnsupportedOperationException("EVM signing not yet implemented");
    }

    @Override
    public void signMessage(SignedMessage msg, @Nullable KeyParameter aesKey) {
        throw new UnsupportedOperationException("EVM message signing not yet implemented");
    }

    @Override
    public void verifyMessage(SignedMessage msg) {
        throw new UnsupportedOperationException("EVM message verification not yet implemented");
    }

    // ---- ConnectionEventListener ----

    @Override
    public void onConnection(BlockchainConnection connection) {
        this.blockchainConnection = connection;
        this.isConnected = true;
    }

    @Override
    public void onDisconnect() {
        this.isConnected = false;
        this.blockchainConnection = null;
    }

    // ---- Helper classes ----

    private static class ListenerRegistration {
        final WalletAccountEventListener listener;
        final Executor executor;

        ListenerRegistration(WalletAccountEventListener listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }

        static boolean removeFromList(WalletAccountEventListener listener, List<ListenerRegistration> list) {
            for (ListenerRegistration reg : list) {
                if (reg.listener == listener) {
                    list.remove(reg);
                    return true;
                }
            }
            return false;
        }
    }

    private static class Threading {
        static final Executor USER_THREAD = Runnable::run;
    }
}
