package com.openwallet.core.wallet.families.solana;

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
 * Solana family wallet — single-address account (Ed25519 keypair).
 * Derived at m/44'/501'/0'/0' (BIP-44 with Solana coin type 501).
 */
public class SolanaFamilyWallet extends AbstractWallet<SolanaTransaction, SolanaAddress>
        implements Serializable {
    private static final long serialVersionUID = 1L;

    @Nullable private transient Wallet wallet;
    @Nullable private transient BlockchainConnection<SolanaTransaction> blockchainConnection;
    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    private SolanaAddress address;
    private final Map<Sha256Hash, SolanaTransaction> txMap = new ConcurrentHashMap<>();
    private Value balance;
    private boolean isConnected = false;

    public SolanaFamilyWallet(CoinType coinType, String id) {
        super(coinType, id);
        this.balance = coinType.value(0);
    }

    public SolanaFamilyWallet(CoinType coinType, String id, DeterministicKey rootKey) {
        super(coinType, id);
        this.balance = coinType.value(0);
        // Derive Solana address from public key: use SHA256 of pubkey as 32-byte address
        byte[] pubKey = rootKey.getPubKey();
        byte[] hash = Sha256Hash.create(pubKey).getBytes();
        try {
            this.address = SolanaAddress.fromPubkey(coinType, hash);
        } catch (AddressMalformedException e) {
            throw new RuntimeException("Failed to derive Solana address", e);
        }
    }

    public SolanaFamilyWallet(CoinType coinType, String id, String addressStr) throws AddressMalformedException {
        super(coinType, id);
        this.address = SolanaAddress.from(coinType, addressStr);
        this.balance = coinType.value(0);
    }

    @Override public SolanaAddress getChangeAddress() { return address; }
    @Override public SolanaAddress getReceiveAddress() { return address; }
    @Override public AbstractAddress getRefundAddress(boolean m) { return address; }
    @Override public SolanaAddress getReceiveAddress(boolean m) { return address; }
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
        if (blockchainConnection != null && tx instanceof SolanaTransaction)
            return blockchainConnection.broadcastTxSync((SolanaTransaction) tx);
        return false;
    }
    @Override public void broadcastTx(AbstractTransaction tx) throws TransactionBroadcastException {
        if (blockchainConnection != null && tx instanceof SolanaTransaction)
            blockchainConnection.broadcastTx((SolanaTransaction) tx, null);
    }

    @Nullable
    @Override public ECKey findKeyFromPubHash(byte[] pubkeyHash) { throw new RuntimeException("Not implemented"); }
    @Nullable
    @Override public ECKey findKeyFromPubKey(byte[] pubkey) { throw new RuntimeException("Not implemented"); }
    @Nullable
    @Override public RedeemData findRedeemDataFromScriptHash(byte[] scriptHash) { throw new RuntimeException("Not implemented"); }
    @Override public SolanaTransaction getTransaction(String txId) {
        for (Map.Entry<Sha256Hash, SolanaTransaction> e : txMap.entrySet()) {
            if (e.getValue().getHashAsString().equals(txId)) return e.getValue();
        }
        return null;
    }
    @Override public Map<Sha256Hash, SolanaTransaction> getPendingTransactions() {
        Map<Sha256Hash, SolanaTransaction> pending = new HashMap<>();
        for (Map.Entry<Sha256Hash, SolanaTransaction> e : txMap.entrySet()) {
            if (e.getValue().getConfidenceType() == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING)
                pending.put(e.getKey(), e.getValue());
        }
        return pending;
    }
    @Override public Map<Sha256Hash, SolanaTransaction> getTransactions() { return Collections.unmodifiableMap(txMap); }
    public void addTransaction(SolanaTransaction tx) { txMap.put(tx.getHash(), tx); }

    @Override public void setWallet(Wallet wallet) { this.wallet = wallet; }
    @Override public Wallet getWallet() { return wallet; }
    @Override public void walletSaveLater() { if (wallet != null) wallet.saveLater(); }
    @Override public void walletSaveNow() { if (wallet != null) wallet.saveNow(); }

    @Override public boolean isEncryptable() { return false; }
    @Override public boolean isEncrypted() { return false; }
    @Override public KeyCrypter getKeyCrypter() { return null; }
    @Override public void encrypt(KeyCrypter kc, KeyParameter ak) {
        throw new UnsupportedOperationException("Solana wallet encryption not yet implemented");
    }
    @Override public void decrypt(KeyParameter ak) {
        throw new UnsupportedOperationException("Solana wallet decryption not yet implemented");
    }

    @Override public void addEventListener(WalletAccountEventListener l) { addEventListener(l, Runnable::run); }
    @Override public void addEventListener(WalletAccountEventListener l, Executor e) {
        listeners.add(new ListenerRegistration(l, e));
    }
    @Override public boolean removeEventListener(WalletAccountEventListener l) {
        return ListenerRegistration.removeFromList(l, listeners);
    }

    @Override public boolean isNew() { return txMap.isEmpty(); }
    @Override public byte[] getPublicKey() { return address != null ? address.getPubkey() : null; }
    @Override public void maybeInitializeAllKeys() { }
    @Override public String getPublicKeyMnemonic() { return null; }
    @Override public String getPublicKeySerialized() { return address != null ? address.toString() : null; }

    @Override public SendRequest getEmptyWalletRequest(AbstractAddress dest) throws WalletAccountException {
        throw new WalletAccountException("Solana send not yet implemented");
    }
    @Override public SendRequest getSendToRequest(AbstractAddress dest, Value amt) throws WalletAccountException {
        throw new WalletAccountException("Solana send not yet implemented");
    }
    @Override public void completeTransaction(SendRequest req) throws WalletAccountException {
        throw new WalletAccountException("Solana transaction completion not yet implemented");
    }
    @Override public void signTransaction(SendRequest req) {
        throw new UnsupportedOperationException("Solana signing not yet implemented");
    }
    @Override public void signMessage(SignedMessage msg, @Nullable KeyParameter ak) {
        throw new UnsupportedOperationException("Solana message signing not yet implemented");
    }
    @Override public void verifyMessage(SignedMessage msg) {
        throw new UnsupportedOperationException("Solana message verification not yet implemented");
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
