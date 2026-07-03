package com.openwallet.wallet

import android.content.Context
import android.util.Log
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import com.openwallet.core.coins.ZcashMain
import com.openwallet.core.wallet.families.zcash.ZcashBackendDelegate
import com.openwallet.core.wallet.families.zcash.ZcashSdkTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ZcashSdkBackendImpl(
    private val context: Context,
    private val seedBytes: ByteArray,
    private val servers: List<ZcashSdkBackendImpl.HostPort>,
    private val seedCreationTimeSeconds: Long = 0L,
) : ZcashBackendDelegate {

    /** A single lightwalletd endpoint to try (host, port). */
    data class HostPort(val host: String, val port: Int)

    /** Thrown when the on-disk SDK database can't be prepared for the current seed. */
    private class SeedDatabaseException(message: String) : Exception(message)

    companion object {
        private const val TAG = "ZcashSdkBackend"
        private const val SDK_ALIAS = "openwallet_zec"
        private const val PREFS_NAME = "zec_sdk_prefs"
        private const val KEY_INITIALIZED = "wallet_initialized"
        private const val KEY_SEED_FP = "seed_fingerprint"
        private const val ZCASH_BLOCK_TIME_SECONDS = 75L
        private const val ONE_DAY_SECONDS = 86_400L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    @Volatile private var stopJob: Job? = null

    @Volatile private var synchronizer: CloseableSynchronizer? = null
    @Volatile private var cachedAccount: Account? = null
    @Volatile private var cachedAddress: String? = null
    @Volatile private var cachedTAddress: String? = null
    @Volatile private var cachedSaplingAddress: String? = null
    @Volatile private var cachedBalance: Long = 0L
    @Volatile private var cachedTransactions: List<ZcashSdkTransaction> = emptyList()
    @Volatile private var connected: Boolean = false
    @Volatile private var loading: Boolean = false
    @Volatile private var syncProgressPercent: Int = 0
    @Volatile private var updateListener: ZcashBackendDelegate.UpdateListener? = null
    @Volatile private var lastError: String? = null
    override fun getLastErrorMessage(): String? = lastError

    override fun setUpdateListener(listener: ZcashBackendDelegate.UpdateListener?) {
        updateListener = listener
    }

    private fun notifyUpdated() {
        runCatching { updateListener?.onBackendUpdated() }
            .onFailure { Log.w(TAG, "Update listener threw: ${it.javaClass.simpleName}") }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isWalletInitialized() = prefs().getBoolean(KEY_INITIALIZED, false)
    private fun markWalletInitialized() =
        prefs().edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putString(KEY_SEED_FP, seedFingerprint())
            .apply()

    private fun seedFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seedBytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private suspend fun estimateBirthday(isRestore: Boolean): BlockHeight? {
        val floor = ZcashNetwork.Mainnet.saplingActivationHeight.value
        val checkpoint = runCatching {
            BlockHeight.ofLatestCheckpoint(context, ZcashNetwork.Mainnet)
        }.getOrNull()
        if (!isRestore) return checkpoint // new wallet: tip (null lets the SDK pick its default)
        if (seedCreationTimeSeconds <= 0L || checkpoint == null) {
            // Unknown seed age (or no checkpoint available): scan the full shielded history.
            return BlockHeight.new(floor)
        }
        val ageSeconds = System.currentTimeMillis() / 1000L - seedCreationTimeSeconds
        if (ageSeconds <= 0) {
            // Device clock is behind the recorded creation time: degrade to the safe full scan,
            // never to the tip (which would silently skip the wallet's history).
            return BlockHeight.new(floor)
        }
        val blocksBack = ageSeconds / ZCASH_BLOCK_TIME_SECONDS
        return BlockHeight.new(maxOf(checkpoint.value - blocksBack, floor))
    }

    // Non-blocking: invoked from the service main thread; all work runs on Dispatchers.IO.
    override fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            stopJob?.join() // wait for any in-flight teardown before re-creating the synchronizer
            loading = true
            if (servers.isEmpty()) {
                // Should be unreachable: Constants.DEFAULT_COINS_SERVERS always lists ZEC servers.
                loading = false
                connected = false
                lastError = "No Zcash servers configured"
                notifyUpdated()
                return@launch
            }
            var sync: CloseableSynchronizer? = null
            var lastException: Throwable? = null
            for (server in servers) {
                try {
                    sync = openSynchronizer(server)
                    break
                } catch (e: CancellationException) {
                    throw e // never swallow coroutine cancellation
                } catch (e: SeedDatabaseException) {
                    // Server-independent failure (stale DB erase failed): don't try other servers.
                    lastException = e
                    break
                } catch (t: Throwable) {
                    // Throwable, not Exception: dependency/linkage Errors (e.g. a library
                    // version conflict) must degrade to "connection failed", not kill the app.
                    lastException = t
                    Log.e(TAG, "ZEC init failed on ${server.host}: ${t.javaClass.simpleName}")
                }
            }
            if (sync == null) {
                loading = false
                connected = false
                lastError = lastException
                    ?.let { "${it.javaClass.simpleName}: ${it.message}".take(200) }
                    ?: "Unable to reach any Zcash server"
                notifyUpdated()
                return@launch
            }
            lastError = null
            synchronizer = sync
            attachCollectors(sync)
        }
    }

    private suspend fun openSynchronizer(server: HostPort): CloseableSynchronizer {
        val endpoint = LightWalletEndpoint(server.host, server.port, isSecure = true)

        // If the app's seed changed (wallet restored/recreated), the SDK database
        // belongs to the old seed — erase it before initializing.
        val fp = seedFingerprint()
        val storedFp = prefs().getString(KEY_SEED_FP, null)
        if (storedFp != null && storedFp != fp) {
            Log.i(TAG, "Seed changed since last init; erasing ZEC SDK database")
            val eraseFailed = runCatching {
                Synchronizer.erase(appContext = context,
                    network = ZcashNetwork.Mainnet, alias = SDK_ALIAS)
            }.onFailure {
                Log.e(TAG, "Failed to erase stale ZEC DB before reinit: ${it.javaClass.simpleName}")
            }.isFailure
            if (eraseFailed) {
                // Fail closed: initializing over a stale DB for a different seed
                // risks a corrupted balance/tx view. Retry on the next startSync().
                throw SeedDatabaseException("Failed to erase stale ZEC database")
            }
            prefs().edit().clear().apply()
        }

        val alreadyInitialized = isWalletInitialized()
        // creation time 0 = restored/unknown age; recent (<1 day) = genuinely new wallet
        // >1 day old and never seen by the SDK: an existing wallet adding ZEC, not a fresh one
        val isRestore = seedCreationTimeSeconds <= 0L ||
                (System.currentTimeMillis() / 1000L - seedCreationTimeSeconds) > ONE_DAY_SECONDS
        val initMode = when {
            alreadyInitialized -> WalletInitMode.ExistingWallet
            isRestore -> WalletInitMode.RestoreWallet
            else -> WalletInitMode.NewWallet
        }
        Log.d(TAG, "Init mode=$initMode")
        val birthday: BlockHeight? =
            if (!alreadyInitialized) estimateBirthday(isRestore)
                .also { Log.d(TAG, "Birthday=$it") }
            else null

        val setup = AccountCreateSetup(
            accountName = "OpenWallet ZEC",
            keySource = null,
            seed = FirstClassByteArray(seedBytes),
        )
        val sync = Synchronizer.new(
            alias = SDK_ALIAS,
            birthday = birthday,
            context = context,
            lightWalletEndpoint = endpoint,
            setup = setup,
            walletInitMode = initMode,
            zcashNetwork = ZcashNetwork.Mainnet,
        )
        if (!alreadyInitialized) markWalletInitialized()

        val accounts = sync.getAccounts()
        val account = accounts.firstOrNull()
        cachedAccount = account
        if (account != null) {
            cachedAddress = sync.getUnifiedAddress(account)
            cachedTAddress = sync.getTransparentAddress(account)
            cachedSaplingAddress = runCatching { sync.getSaplingAddress(account) }.getOrNull()
            Log.d(TAG, "Addresses derived: ua=${cachedAddress != null} t=${cachedTAddress != null} sapling=${cachedSaplingAddress != null}")
        }

        return sync
    }

    /**
     * Must be called from within syncJob's coroutine scope — stopSync() cancels syncJob
     * to tear these collectors down together with the synchronizer.
     */
    private fun CoroutineScope.attachCollectors(sync: CloseableSynchronizer) {
        launch {
            sync.walletBalances.collectLatest { balances ->
                if (balances != null) {
                    cachedBalance = balances.values.sumOf { b ->
                        b.sapling.total.value + b.orchard.total.value + b.unshielded.value
                    }
                    connected = true
                    loading = false
                    notifyUpdated()
                }
            }
        }

        launch {
            sync.transactions.collectLatest { txList ->
                cachedTransactions = txList.mapNotNull { mapTransaction(it) }
                notifyUpdated()
            }
        }

        launch {
            sync.progress.collectLatest { pct ->
                val newPct = (pct.decimal * 100f).toInt().coerceIn(0, 100)
                if (newPct != syncProgressPercent) {
                    syncProgressPercent = newPct
                    notifyUpdated()
                }
            }
        }
    }

    override fun stopSync() {
        val jobToStop = syncJob
        syncJob = null
        val syncToClose = synchronizer
        synchronizer = null
        cachedAccount = null
        cachedSaplingAddress = null
        connected = false
        loading = false
        // Teardown is async: cancel() alone doesn't wait for the coroutine to finish,
        // and closing the synchronizer while collectors still run races the SDK.
        // startSync() joins stopJob before creating a new Synchronizer for the alias.
        stopJob = scope.launch {
            jobToStop?.cancelAndJoin()
            runCatching { syncToClose?.close() }
        }
    }

    override fun getReceiveAddress(): String? = cachedAddress ?: cachedTAddress
    override fun getTransparentAddress(): String? = cachedTAddress
    override fun getSaplingAddress(): String? = cachedSaplingAddress
    override fun getBalanceZatoshi(): Long = cachedBalance
    override fun isConnected(): Boolean = connected
    override fun isLoading(): Boolean = loading
    override fun getSyncProgressPercent(): Int = syncProgressPercent
    override fun getTransactions(): List<ZcashSdkTransaction> = cachedTransactions

    private fun mapTransaction(tx: TransactionOverview): ZcashSdkTransaction? {
        return try {
            val txIdHex = tx.rawId.byteArray.joinToString("") { "%02x".format(it) }
            val blockHeight = tx.minedHeight?.value?.toInt() ?: -1
            val timestampMs = (tx.blockTimeEpochSeconds ?: 0L) * 1_000L
            val isIncoming = !tx.isSentTransaction
            val zatoshi = Math.abs(tx.netValue.value)
            val feeSatoshis = tx.feePaid?.value ?: 0L
            ZcashSdkTransaction(
                ZcashMain.get(),
                txIdHex,
                zatoshi,
                feeSatoshis,
                if (timestampMs > 0L) timestampMs else System.currentTimeMillis(),
                blockHeight,
                isIncoming,
                null,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to map tx: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun sendTo(
        recipient: String,
        zatoshi: Long,
        memo: String?,
        callback: ZcashBackendDelegate.SendCallback,
    ) {
        val sync = synchronizer
        if (sync == null) {
            callback.onError(IllegalStateException("Synchronizer not running"))
            return
        }
        scope.launch {
            try {
                val account = sync.getAccounts().firstOrNull()
                    ?: throw IllegalStateException("No ZEC accounts found")

                val hdIndex = account.hdAccountIndex ?: Zip32AccountIndex.new(0L)
                val spendingKey = DerivationTool.getInstance().deriveUnifiedSpendingKey(
                    seed = seedBytes,
                    network = ZcashNetwork.Mainnet,
                    accountIndex = hdIndex,
                )

                val memoStr = memo ?: ""
                val proposal = sync.proposeTransfer(account, recipient, Zatoshi(zatoshi), memoStr)

                val resultsFlow = sync.createProposedTransactions(proposal, spendingKey)
                val firstResult = resultsFlow.first()
                val txId = firstResult.txIdString()

                Log.i(TAG, "ZEC send successful txId=$txId")
                callback.onSuccess(txId)

            } catch (e: Exception) {
                Log.e(TAG, "ZEC send failed: ${e.javaClass.simpleName}")
                callback.onError(e)
            }
        }
    }
}