package com.openwallet.core.wallet.families.chia;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.exceptions.TransactionBroadcastException;
import com.openwallet.core.network.interfaces.BlockchainConnection;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.AbstractWallet;
import com.openwallet.core.wallet.SendRequest;
import com.openwallet.core.wallet.SignedMessage;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccountEventListener;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.KeyCrypter;
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
 * Chia family wallet — single-address account using BLS12-381.
 * Chia uses a CLVM-based puzzle hash model rather than traditional UTXO or account model.
 * Derived at m/12381/8444/2/0 (Chia key derivation).
 */
public class ChiaFamilyWallet extends AbstractWallet<ChiaTransaction, ChiaAddress>
        implements Serializable {
    private static final long serialVersionUID = 1L;

    @Nullable private transient Wallet wallet;
    @Nullable private transient BlockchainConnection<ChiaTransaction> blockchainConnection;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    private ChiaAddress address;
    private final Map<Sha256Hash, ChiaTransaction> txMap = new ConcurrentHashMap<>();
    private Value balance;
    private boolean isConnected = false;

    public ChiaFamilyWallet(CoinType coinType, String id) {
        super(coinType, id);
        this.balance = coinType.value(0);
    }

    public ChiaFamilyWallet(CoinType coinType, String id, DeterministicKey rootKey) {
        super(coinType, id);
        this.balance = coinType.value(0);
        // Derive Chia address: use SHA256(pubkey) as puzzle hash
        byte[] pubKey = rootKey.getPubKey();
        byte[] puzzleHash = Sha256Hash.create(pubKey).getBytes();
        try {
            this.address = ChiaAddress.fromPuzzleHash(coinType, puzzleHash, false);
        } catch (AddressMalformedException e) {
            throw new RuntimeException("Failed to derive Chia address", e);
        }
    }

    public ChiaFamilyWallet(CoinType coinType, String id, String addressStr) throws AddressMalformedException {
        super(coinType, id);
        this.address = ChiaAddress.from(coinType, addressStr);
        this.balance = coinType.value(0);
    }

    @Override public ChiaAddress getChangeAddress() { return address; }
    @Override public ChiaAddress getReceiveAddress() { return address; }
    @Override public AbstractAddress getRefundAddress(boolean m) { return address; }
    @Override public ChiaAddress getReceiveAddress(boolean m) { return address; }
    @Override public boolean hasUsedAddresses() { return address != null; }
    @Override public boolean canCreateNewAddresses() { return false; }
    @Override public List<AbstractAddress> getActiveAddresses() {
        return address != null ? Collections.singletonList((AbstractAddress) address) : Collections.<AbstractAddress>emptyList();
    }
    @Override public void markAddressAsUsed(AbstractAddress addr) { }
    @Override public boolean isAddressMine(AbstractAddress addr) {
        return address != null && address.equals(addr);
    }

    @Override public Value getBalance() { return balance; }
    public void setBalance(Value balance) { this.balance = balance; }

    @Override public boolean isConnected() { return isConnected; }
    @Override public boolean isLoading() { return false; }
    @Override public void disconnect() {
        isConnected = false;
        if (blockchainConnection != null) { blockchainConnection.stopAsync(); blockchainConnection = null; }
    }
    @Override public void refresh() {
        if (blockchainConnection != null) blockchainConnection.resetConnection();
    }

    @Override public boolean broadcastTxSync(AbstractTransaction tx) throws TransactionBroadcastException {
        if (blockchainConnection != null && tx instanceof ChiaTransaction)
            return blockchainConnection.broadcastTxSync((ChiaTransaction) tx);
        return false;
    }
    @Override public void broadcastTx(AbstractTransaction tx) throws TransactionBroadcastException {
        if (blockchainConnection != null && tx instanceof ChiaTransaction)
            blockchainConnection.broadcastTx((ChiaTransaction) tx, null);
    }

    @Nullable
    @Override public ECKey findKeyFromPubHash(byte[] pubkeyHash) { throw new RuntimeException("Not implemented"); }
    @Nullable
    @Override public ECKey findKeyFromPubKey(byte[] pubkey) { throw new RuntimeException("Not implemented"); }
    @Nullable
    @Override public RedeemData findRedeemDataFromScriptHash(byte[] scriptHash) { throw new RuntimeException("Not implemented"); }
    @Override public ChiaTransaction getTransaction(String txId) {
        for (Map.Entry<Sha256Hash, ChiaTransaction> e : txMap.entrySet()) {
            if (e.getValue().getHashAsString().equals(txId)) return e.getValue();
        }
        return null;
    }
    @Override public Map<Sha256Hash, ChiaTransaction> getPendingTransactions() {
        Map<Sha256Hash, ChiaTransaction> pending = new HashMap<>();
        for (Map.Entry<Sha256Hash, ChiaTransaction> e : txMap.entrySet()) {
            if (e.getValue().getConfidenceType() == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING)
                pending.put(e.getKey(), e.getValue());
        }
        return pending;
    }
    @Override public Map<Sha256Hash, ChiaTransaction> getTransactions() { return Collections.unmodifiableMap(txMap); }
    public void addTransaction(ChiaTransaction tx) { txMap.put(tx.getHash(), tx); }

    @Override public void setWallet(Wallet wallet) { this.wallet = wallet; }
    @Override public Wallet getWallet() { return wallet; }
    @Override public void walletSaveLater() { if (wallet != null) wallet.saveLater(); }
    @Override public void walletSaveNow() { if (wallet != null) wallet.saveNow(); }

    @Override public boolean isEncryptable() { return false; }
    @Override public boolean isEncrypted() { return false; }
    @Override public KeyCrypter getKeyCrypter() { return null; }
    @Override public void encrypt(KeyCrypter kc, KeyParameter ak) {
        throw new UnsupportedOperationException("Chia wallet encryption not yet implemented");
    }
    @Override public void decrypt(KeyParameter ak) {
        throw new UnsupportedOperationException("Chia wallet decryption not yet implemented");
    }

    @Override public void addEventListener(WalletAccountEventListener l) { addEventListener(l, Runnable::run); }
    @Override public void addEventListener(WalletAccountEventListener l, Executor e) {
        listeners.add(new ListenerRegistration(l, e));
    }
    @Override public boolean removeEventListener(WalletAccountEventListener l) {
        return ListenerRegistration.removeFromList(l, listeners);
    }

    @Override public boolean isNew() { return txMap.isEmpty(); }
    @Override public byte[] getPublicKey() { return null; }
    @Override public void maybeInitializeAllKeys() { }
    @Override public String getPublicKeyMnemonic() { return null; }
    @Override public String getPublicKeySerialized() { return address != null ? address.toString() : null; }

    @Override public SendRequest getEmptyWalletRequest(AbstractAddress dest) throws WalletAccountException {
        throw new WalletAccountException("Chia send not yet implemented");
    }
    @Override public SendRequest getSendToRequest(AbstractAddress dest, Value amt) throws WalletAccountException {
        throw new WalletAccountException("Chia send not yet implemented");
    }
    @Override public void completeTransaction(SendRequest req) throws WalletAccountException {
        throw new WalletAccountException("Chia transaction completion not yet implemented");
    }
    @Override public void signTransaction(SendRequest req) {
        throw new UnsupportedOperationException("Chia signing not yet implemented");
    }
    @Override public void signMessage(SignedMessage msg, @Nullable KeyParameter ak) {
        throw new UnsupportedOperationException("Chia message signing not yet implemented");
    }
    @Override public void verifyMessage(SignedMessage msg) {
        throw new UnsupportedOperationException("Chia message verification not yet implemented");
    }

    @Override public void onConnection(BlockchainConnection conn) {
        this.blockchainConnection = conn;
        this.isConnected = true;
    }
    @Override public void onDisconnect() {
        this.isConnected = false;
        this.blockchainConnection = null;
    }

    private static class ListenerRegistration {
        final WalletAccountEventListener listener;
        final Executor executor;
        ListenerRegistration(WalletAccountEventListener l, Executor e) { listener = l; executor = e; }
        static boolean removeFromList(WalletAccountEventListener l, List<ListenerRegistration> list) {
            for (ListenerRegistration r : list) { if (r.listener == l) { list.remove(r); return true; } }
            return false;
        }
    }
}
