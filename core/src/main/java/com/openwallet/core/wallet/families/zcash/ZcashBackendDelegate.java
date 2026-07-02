package com.openwallet.core.wallet.families.zcash;

import java.util.List;

import javax.annotation.Nullable;

/**
 * Bridge interface between the Android-only zcash-android-sdk (wallet module)
 * and the pure-Java core module.
 *
 * Implementations live in the wallet module (ZcashSdkBackendImpl.kt) and are
 * injected into ZcashSdkWallet instances by CoinServiceImpl after the wallet loads.
 */
public interface ZcashBackendDelegate {

    // ---- Lifecycle ----------------------------------------------------------

    /**
     * Start the lightwalletd gRPC synchronizer in a background coroutine.
     * Must be called from a Context-owning component (Service or Activity).
     * Safe to call multiple times; a no-op if already running.
     */
    void startSync();

    /**
     * Stop the synchronizer and release resources. The instance may be restarted
     * via {@link #startSync()} after this call.
     */
    void stopSync();

    // ---- Addresses ----------------------------------------------------------

    /**
     * Returns the best receive address for this account:
     *  - Unified Address (u1...) if SDK is initialized and Orchard is supported
     *  - Transparent t-address (t1...) otherwise
     * May return null if not yet initialized.
     */
    String getReceiveAddress();

    /**
     * Returns the transparent t-address for this account.
     * May return null if not yet initialized.
     */
    String getTransparentAddress();

    /**
     * Returns the Sapling shielded address (zs1...) for this account.
     * May return null if not yet initialized.
     */
    @Nullable String getSaplingAddress();

    // ---- Balance ------------------------------------------------------------

    /**
     * Total spendable balance in zatoshi (1 ZEC = 1e8 zatoshi), summing
     * transparent + Sapling + Orchard pools.
     */
    long getBalanceZatoshi();

    // ---- Status -------------------------------------------------------------

    /**
     * True once the synchronizer has completed at least one full scan and is connected.
     */
    boolean isConnected();

    /**
     * True while the synchronizer is still performing initial block download.
     */
    boolean isLoading();

    /**
     * Sync progress as an integer 0–100. Returns 0 before sync starts, 100 when fully synced.
     */
    int getSyncProgressPercent();

    // ---- Transactions -------------------------------------------------------

    /**
     * Returns the confirmed + pending transaction history for this account, newest first.
     * Returns an empty list if the synchronizer is not yet initialized.
     */
    List<ZcashSdkTransaction> getTransactions();

    // ---- Sending ------------------------------------------------------------

    /**
     * Callback interface for async ZEC sends.
     */
    interface SendCallback {
        /**
         * Called on success. {@code txId} is the 64-char hex transaction ID.
         */
        void onSuccess(String txId);

        /**
         * Called if the send fails for any reason.
         */
        void onError(Exception e);
    }

    /**
     * Asynchronously send ZEC to {@code recipient}.
     *
     * @param recipient  Destination address — t-addr, z-addr, or Unified Address.
     * @param zatoshi    Amount in zatoshi (1 ZEC = 100_000_000 zatoshi).
     * @param memo       Optional memo string (≤512 bytes, shielded sends only). Pass null to omit.
     * @param callback   Called on the IO thread when the send succeeds or fails.
     */
    void sendTo(String recipient, long zatoshi, @Nullable String memo, SendCallback callback);
}
