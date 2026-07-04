package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.coins.ZcashAddress;
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
import com.openwallet.core.wallet.WalletAccountEventListener;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.wallet.RedeemData;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.openwallet.core.wallet.WalletAccount.WalletAccountException;

import org.spongycastle.crypto.params.KeyParameter;

/**
 * Zcash wallet account backed by the zcash-android-sdk via {@link ZcashBackendDelegate}.
 *
 * Before the backend is injected (e.g. immediately after wallet load), the account
 * falls back to displaying the BIP-44 derived transparent t-address with a zero balance.
 * Once {@link #setBackend(ZcashBackendDelegate)} is called and {@link ZcashBackendDelegate#startSync()}
 * completes, Unified Addresses and live balances are available.
 *
 * Sending (proposeTransfer / createProposedTransactions) is planned for Phase 3.
 */
public class ZcashSdkWallet extends AbstractWallet<ZcashSdkTransaction, ZcashSdkAddress>
        implements Serializable {
    private static final long serialVersionUID = 1L;

    /** ZIP-317 conventional fee: 5,000 zat marginal fee x 3 logical actions (typical shielded send). */
    public static final long ZIP317_STANDARD_FEE = 15_000L;
    /**
     * Conservative margin for "send all" - covers cross-pool sends (up to 4 actions).
     * The SDK computes the exact ZIP-317 fee at proposal time, so send-all may leave
     * up to 5,000 zat behind; exact-fee send-all requires a proposeTransfer round-trip
     * and is deferred.
     */
    public static final long SEND_ALL_FEE_MARGIN = 20_000L;

    /** Fallback t-address derived from the BIP-44 HD key — available before SDK init. */
    private final ZcashSdkAddress tAddress;

    /** Live SDK backend. Injected by CoinServiceImpl; null until the service starts. */
    @Nullable private transient ZcashBackendDelegate backend;

    @Nullable private transient Wallet wallet;

    /**
     * Stores pending outgoing transactions created by {@link #getSendToRequest} until the send
     * is confirmed by the synchronizer and appears in the backend's transaction history.
     */
    private final Map<Sha256Hash, ZcashSdkTransaction> txMap = new HashMap<>();
    private Value balance;

    private final CopyOnWriteArrayList<ListenerRegistration> listeners = new CopyOnWriteArrayList<>();

    /** Last balance sent to listeners; used to suppress redundant onNewBalance events. */
    @Nullable private transient Value lastNotifiedBalance;

    // ---- Constructors -------------------------------------------------------

    public ZcashSdkWallet(CoinType coinType, String id) {
        super(coinType, id);
        this.balance = coinType.value(0);
        this.tAddress = new ZcashSdkAddress(coinType, "");
    }

    /**
     * Preferred constructor.  Derives the t-address from the account's root HD key
     * so the address is available immediately (even before the SDK synchronizer starts).
     */
    public ZcashSdkWallet(CoinType coinType, String id, DeterministicKey rootKey) {
        super(coinType, id);
        this.balance = coinType.value(0);
        ZcashSdkAddress fallback;
        try {
            // rootKey is the account key at m/44'/133'/0'. Derive the first external address:
            // external chain = rootKey / 0 (non-hardened), first address = / 0 (non-hardened).
            DeterministicKey externalChain = HDKeyDerivation.deriveChildKey(
                    rootKey, new ChildNumber(0, false));
            DeterministicKey firstKey = HDKeyDerivation.deriveChildKey(
                    externalChain, new ChildNumber(0, false));
            ZcashAddress za = ZcashAddress.fromHash160(coinType, firstKey.getPubKeyHash());
            fallback = new ZcashSdkAddress(coinType, za.toString());
        } catch (Exception e) {
            fallback = new ZcashSdkAddress(coinType, "");
        }
        this.tAddress = fallback;
    }

    /**
     * Deserialization constructor. Restores an account from its persisted identity
     * and previously derived t-address (the root HD key is not persisted — it is
     * only ever used once, at creation time, to derive the fallback t-address).
     */
    public ZcashSdkWallet(CoinType coinType, String id, String tAddressStr) {
        super(coinType, id);
        this.balance = coinType.value(0);
        this.tAddress = new ZcashSdkAddress(coinType, tAddressStr == null ? "" : tAddressStr);
    }

    /** The persisted fallback t-address (may be empty); stable regardless of backend state. */
    public String getFallbackTAddressString() {
        return tAddress.toString();
    }

    // ---- Backend injection --------------------------------------------------

    /**
     * Called by CoinServiceImpl once it has constructed a ZcashSdkBackendImpl.
     * Immediately starts the background sync.
     */
    public void setBackend(ZcashBackendDelegate backend) {
        this.backend = backend;
        backend.setUpdateListener(new ZcashBackendDelegate.UpdateListener() {
            @Override
            public void onBackendUpdated() {
                notifyBackendUpdated();
            }
        });
        backend.startSync();
    }

    private void notifyBackendUpdated() {
        final Value newBalance = getBalance();
        final boolean balanceChanged =
                lastNotifiedBalance == null || newBalance.compareTo(lastNotifiedBalance) != 0;
        lastNotifiedBalance = newBalance;
        for (final ListenerRegistration reg : listeners) {
            reg.executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (balanceChanged) {
                        reg.listener.onNewBalance(newBalance);
                    }
                    reg.listener.onWalletChanged(ZcashSdkWallet.this);
                }
            });
        }
        // Deliberately NO wallet.saveLater() here: nothing persistable changes on
        // backend updates (balance/txs live in the SDK's own database), and during the
        // initial scan a save per update kept the wallet lock busy serializing every
        // other account's tx history — starving the UI thread into ANRs.
    }

    @Nullable
    public ZcashBackendDelegate getBackend() {
        return backend;
    }

    // ---- Address methods ----------------------------------------------------

    /**
     * Best receive address: Unified Address when the SDK is running, t-address otherwise.
     */
    @Override
    public ZcashSdkAddress getReceiveAddress() {
        if (backend != null) {
            String ua = backend.getReceiveAddress();
            if (ua != null && !ua.isEmpty()) {
                return new ZcashSdkAddress(type, ua);
            }
        }
        return tAddress;
    }

    /** Unified Address (u1…), or null until the SDK backend has derived it. */
    @Nullable
    public ZcashSdkAddress getUnifiedAddress() {
        if (backend != null) {
            String ua = backend.getReceiveAddress();
            if (ua != null && !ua.isEmpty()) return new ZcashSdkAddress(type, ua);
        }
        return null;
    }

    /** Transparent t-address: SDK-derived when available, BIP-44 fallback otherwise. */
    @Nullable
    public ZcashSdkAddress getTransparentAddress() {
        if (backend != null) {
            String ta = backend.getTransparentAddress();
            if (ta != null && !ta.isEmpty()) return new ZcashSdkAddress(type, ta);
        }
        return tAddress.toString().isEmpty() ? null : tAddress;
    }

    /** Shielded Sapling address (zs1…), or null until the SDK backend has derived it. */
    @Nullable
    public ZcashSdkAddress getShieldedAddress() {
        if (backend != null) {
            String zs = backend.getSaplingAddress();
            if (zs != null && !zs.isEmpty()) return new ZcashSdkAddress(type, zs);
        }
        return null;
    }

    @Override
    public ZcashSdkAddress getChangeAddress() {
        return getReceiveAddress();
    }

    @Override
    public AbstractAddress getRefundAddress(boolean isManualAddressManagement) {
        return getReceiveAddress();
    }

    @Override
    public ZcashSdkAddress getReceiveAddress(boolean isManualAddressManagement) {
        return getReceiveAddress();
    }

    @Override
    public boolean hasUsedAddresses() {
        return !tAddress.toString().isEmpty();
    }

    @Override
    public boolean canCreateNewAddresses() {
        return false;
    }

    @Override
    public List<AbstractAddress> getActiveAddresses() {
        ZcashSdkAddress addr = getReceiveAddress();
        if (addr.toString().isEmpty()) return Collections.emptyList();
        return Collections.<AbstractAddress>singletonList(addr);
    }

    @Override
    public void markAddressAsUsed(AbstractAddress addr) { }

    @Override
    public boolean isAddressMine(AbstractAddress addr) {
        if (addr == null) return false;
        String addrStr = addr.toString();
        if (tAddress.toString().equals(addrStr)) return true;
        if (backend != null) {
            String ua = backend.getReceiveAddress();
            if (ua != null && ua.equals(addrStr)) return true;
            String ta = backend.getTransparentAddress();
            if (ta != null && ta.equals(addrStr)) return true;
            String zs = backend.getSaplingAddress();
            if (zs != null && zs.equals(addrStr)) return true;
        }
        return false;
    }

    /**
     * Returns the Sapling shielded address (zs1...) once the SDK has initialised,
     * or {@code null} if the backend is not yet running.
     */
    @Nullable
    public ZcashSdkAddress getSaplingAddress() {
        if (backend != null) {
            String zs = backend.getSaplingAddress();
            if (zs != null && !zs.isEmpty()) {
                return new ZcashSdkAddress(type, zs);
            }
        }
        return null;
    }

    // ---- Balance ------------------------------------------------------------

    @Override
    public Value getBalance() {
        if (backend != null) {
            return type.value(backend.getBalanceZatoshi());
        }
        return balance;
    }

    // ---- Connection state ---------------------------------------------------

    @Override
    public boolean isConnected() {
        return backend != null && backend.isConnected();
    }

    @Override
    public boolean isLoading() {
        return backend != null && backend.isLoading();
    }

    /** SDK block-scan progress, 0..100. -1 when no backend is attached. */
    public int getSyncProgressPercent() {
        return backend != null ? backend.getSyncProgressPercent() : -1;
    }

    @Override
    public void disconnect() {
        if (backend != null) {
            backend.stopSync();
        }
    }

    @Override
    public void refresh() { }

    // ---- Transactions -------------------------------------------------------

    @Override
    public boolean broadcastTxSync(AbstractTransaction tx) throws TransactionBroadcastException {
        if (!(tx instanceof ZcashSdkTransaction)) {
            throw new TransactionBroadcastException("Expected ZcashSdkTransaction");
        }
        if (backend == null) {
            throw new TransactionBroadcastException("ZEC backend not initialized — wallet not yet synced");
        }
        ZcashSdkTransaction zecTx = (ZcashSdkTransaction) tx;
        String recipient = zecTx.getToAddressStr();
        long zatoshi = zecTx.getValueZatoshi();
        if (recipient == null || recipient.isEmpty()) {
            throw new TransactionBroadcastException("No destination address in send request");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] resultTxId = {null};
        final Exception[] resultError = {null};

        backend.sendTo(recipient, zatoshi, null, new ZcashBackendDelegate.SendCallback() {
            @Override
            public void onSuccess(String txId) {
                resultTxId[0] = txId;
                latch.countDown();
            }
            @Override
            public void onError(Exception e) {
                resultError[0] = e;
                latch.countDown();
            }
        });

        try {
            boolean completed = latch.await(120, TimeUnit.SECONDS);
            if (!completed) {
                throw new TransactionBroadcastException("ZEC send timed out after 120 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransactionBroadcastException("ZEC send interrupted");
        }

        if (resultError[0] != null) {
            throw new TransactionBroadcastException(resultError[0].getMessage());
        }
        // Remove the placeholder pending tx — the real tx will appear via sync
        txMap.remove(zecTx.getHash());
        return true;
    }

    @Override
    public void broadcastTx(AbstractTransaction tx) throws TransactionBroadcastException {
        if (!(tx instanceof ZcashSdkTransaction)) {
            throw new TransactionBroadcastException("Expected ZcashSdkTransaction");
        }
        if (backend == null) {
            throw new TransactionBroadcastException("ZEC backend not initialized — wallet not yet synced");
        }
        final ZcashSdkTransaction zecTx = (ZcashSdkTransaction) tx;
        String recipient = zecTx.getToAddressStr();
        long zatoshi = zecTx.getValueZatoshi();
        if (recipient == null || recipient.isEmpty()) {
            throw new TransactionBroadcastException("No destination address in send request");
        }

        backend.sendTo(recipient, zatoshi, null, new ZcashBackendDelegate.SendCallback() {
            @Override
            public void onSuccess(String txId) {
                // Remove the placeholder; the confirmed tx will appear via the sync loop
                txMap.remove(zecTx.getHash());
                if (wallet != null) wallet.saveLater();
            }
            @Override
            public void onError(Exception e) {
                // Mark the placeholder as failed by removing it;
                // the UI will see the balance unchanged and can retry
                txMap.remove(zecTx.getHash());
            }
        });
    }

    @Override
    @Nullable
    public ZcashSdkTransaction getTransaction(String txId) {
        for (Map.Entry<Sha256Hash, ZcashSdkTransaction> e : txMap.entrySet()) {
            if (e.getValue().getHashAsString().equals(txId)) return e.getValue();
        }
        return null;
    }

    @Override
    public Map<Sha256Hash, ZcashSdkTransaction> getPendingTransactions() {
        // Local pending sends + any pending entries from the backend
        Map<Sha256Hash, ZcashSdkTransaction> pending = new HashMap<>();
        for (Map.Entry<Sha256Hash, ZcashSdkTransaction> e : txMap.entrySet()) {
            if (e.getValue().getConfidenceType()
                    == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING) {
                pending.put(e.getKey(), e.getValue());
            }
        }
        if (backend != null) {
            for (ZcashSdkTransaction tx : backend.getTransactions()) {
                if (tx.getConfidenceType()
                        == org.bitcoinj.core.TransactionConfidence.ConfidenceType.PENDING) {
                    pending.put(tx.getHash(), tx);
                }
            }
        }
        return Collections.unmodifiableMap(pending);
    }

    @Override
    public Map<Sha256Hash, ZcashSdkTransaction> getTransactions() {
        if (backend != null) {
            List<ZcashSdkTransaction> backendTxs = backend.getTransactions();
            // LinkedHashMap preserves insertion order (newest-first from backend)
            Map<Sha256Hash, ZcashSdkTransaction> result = new LinkedHashMap<>();
            for (ZcashSdkTransaction tx : backendTxs) {
                result.put(tx.getHash(), tx);
            }
            // Overlay any locally-pending sends not yet seen by the synchronizer
            for (Map.Entry<Sha256Hash, ZcashSdkTransaction> e : txMap.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    result.put(e.getKey(), e.getValue());
                }
            }
            return Collections.unmodifiableMap(result);
        }
        return Collections.unmodifiableMap(txMap);
    }

    // ---- Wallet binding -----------------------------------------------------

    @Override
    public void setWallet(Wallet w) { this.wallet = w; }

    @Override
    public Wallet getWallet() { return wallet; }

    @Override
    public void walletSaveLater() { if (wallet != null) wallet.saveLater(); }

    @Override
    public void walletSaveNow() { if (wallet != null) wallet.saveNow(); }

    // ---- Encryption (delegated to SDK; not in HD layer) ---------------------

    @Override
    public boolean isEncryptable() { return false; }

    @Override
    public boolean isEncrypted() { return false; }

    @Override
    public KeyCrypter getKeyCrypter() { return null; }

    @Override
    public void encrypt(KeyCrypter keyCrypter, KeyParameter aesKey) {
        throw new UnsupportedOperationException("ZEC wallet encryption managed by zcash-android-sdk");
    }

    @Override
    public void decrypt(KeyParameter aesKey) {
        throw new UnsupportedOperationException("ZEC wallet decryption managed by zcash-android-sdk");
    }

    // ---- Events -------------------------------------------------------------

    @Override
    public void addEventListener(WalletAccountEventListener listener) {
        addEventListener(listener, Runnable::run);
    }

    @Override
    public void addEventListener(WalletAccountEventListener listener, Executor executor) {
        listeners.add(new ListenerRegistration(listener, executor));
    }

    @Override
    public boolean removeEventListener(WalletAccountEventListener listener) {
        return ListenerRegistration.removeFromList(listener, listeners);
    }

    // ---- Keys (derived by SDK) ----------------------------------------------

    @Override
    public boolean isNew() {
        if (backend != null && !backend.getTransactions().isEmpty()) return false;
        return txMap.isEmpty();
    }

    @Override
    public byte[] getPublicKey() { return null; }

    @Override
    public void maybeInitializeAllKeys() { }

    @Override
    public String getPublicKeyMnemonic() { return null; }

    @Override
    public String getPublicKeySerialized() {
        return tAddress.toString();
    }

    @Override
    @Nullable
    public ECKey findKeyFromPubHash(byte[] pubkeyHash) {
        throw new UnsupportedOperationException("ZEC keys managed by zcash-android-sdk");
    }

    @Override
    @Nullable
    public ECKey findKeyFromPubKey(byte[] pubkey) {
        throw new UnsupportedOperationException("ZEC keys managed by zcash-android-sdk");
    }

    @Override
    @Nullable
    public RedeemData findRedeemDataFromScriptHash(byte[] scriptHash) {
        throw new UnsupportedOperationException("ZEC keys managed by zcash-android-sdk");
    }

    // ---- BlockchainConnection event callbacks (not used — SDK manages its own) ----

    @Override
    public void onConnection(BlockchainConnection connection) { }

    @Override
    public void onDisconnect() { }

    // ---- Send/Sign ----------------------------------------------------------

    @Override
    public SendRequest getEmptyWalletRequest(AbstractAddress destination)
            throws WalletAccountException {
        // "Send all" maps to sending the full spendable balance minus a conservative margin
        // that covers the exact ZIP-317 fee the SDK will compute at proposal time.
        long spendable = getBalance().getValue();
        long fee = SEND_ALL_FEE_MARGIN;
        if (spendable <= fee) {
            throw new WalletAccountException("Insufficient balance to cover the minimum fee");
        }
        return getSendToRequest(destination, type.value(spendable - fee));
    }

    @Override
    public SendRequest getSendToRequest(AbstractAddress destination, Value amount)
            throws WalletAccountException {
        if (backend == null) {
            throw new WalletAccountException(
                    "ZEC wallet is not synced yet — please wait for synchronization to complete");
        }
        if (amount == null || amount.getValue() <= 0) {
            throw new WalletAccountException("Send amount must be greater than zero");
        }
        // Generate a unique 64-char hex ID for this pending send so the placeholder can be
        // tracked in txMap independently of any real txId returned by the SDK.
        byte[] randBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(randBytes);
        StringBuilder pendingId = new StringBuilder(64);
        for (byte b : randBytes) pendingId.append(String.format("%02x", b));

        ZcashSdkTransaction pendingTx = new ZcashSdkTransaction(
                type,
                pendingId.toString(),
                amount.getValue(),
                ZIP317_STANDARD_FEE,              // ZIP-317 conventional fee estimate in zatoshi
                System.currentTimeMillis() / 1000L, // getTimestamp() convention is epoch SECONDS
                -1,                              // not yet mined
                false,                           // outgoing
                destination.toString());

        txMap.put(pendingTx.getHash(), pendingTx);

        ZcashSendRequest req = new ZcashSendRequest(type, pendingTx);
        return req;
    }

    @Override
    public void completeTransaction(SendRequest request) throws WalletAccountException {
        // Validate balance (the SDK will enforce this again during proposal, but fail fast here)
        if (request.tx instanceof ZcashSdkTransaction) {
            ZcashSdkTransaction zecTx = (ZcashSdkTransaction) request.tx;
            long required = zecTx.getValueZatoshi() + ZIP317_STANDARD_FEE; // amount + ZIP-317 fee
            long available = getBalance().getValue();
            if (available < required) {
                throw new WalletAccountException(String.format(
                        "Insufficient balance: need %d zatoshi, have %d zatoshi",
                        required, available));
            }
        }
        // No UTXO selection or fee calculation needed — the SDK handles all of this
    }

    @Override
    public void signTransaction(SendRequest request) {
        // Signing is performed internally by the SDK during sendTo(); intentional no-op here.
    }

    @Override
    public void signMessage(SignedMessage msg, @Nullable KeyParameter aesKey) {
        throw new UnsupportedOperationException("ZEC message signing not yet implemented");
    }

    @Override
    public void verifyMessage(SignedMessage msg) {
        throw new UnsupportedOperationException("ZEC message verification not yet implemented");
    }

    // ---- Helper -------------------------------------------------------------

    private static class ListenerRegistration {
        final WalletAccountEventListener listener;
        final Executor executor;

        ListenerRegistration(WalletAccountEventListener listener, Executor executor) {
            this.listener = listener;
            this.executor = executor;
        }

        static boolean removeFromList(WalletAccountEventListener listener,
                                      List<ListenerRegistration> list) {
            for (ListenerRegistration reg : list) {
                if (reg.listener == listener) {
                    list.remove(reg);
                    return true;
                }
            }
            return false;
        }
    }

    // ---- Helper: typed SendRequest subclass ---------------------------------

    /** Concrete SendRequest subclass for Zcash, allowing access to the protected constructor. */
    public static final class ZcashSendRequest extends SendRequest<ZcashSdkTransaction> {
        private ZcashSendRequest(CoinType type, ZcashSdkTransaction tx) {
            super(type);
            this.tx = tx;
        }
    }
}
