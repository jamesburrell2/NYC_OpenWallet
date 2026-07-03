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
    private val primaryHost: String = "zec.rocks",
    private val primaryPort: Int = 443,
    private val seedCreationTimeSeconds: Long = 0L,
) : ZcashBackendDelegate {

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
        val checkpoint = runCatching {
            BlockHeight.ofLatestCheckpoint(context, ZcashNetwork.Mainnet)
        }.getOrNull() ?: return null
        if (!isRestore) return checkpoint
        val ageSeconds = System.currentTimeMillis() / 1000L - seedCreationTimeSeconds
        if (ageSeconds <= 0) return checkpoint
        val blocksBack = ageSeconds / ZCASH_BLOCK_TIME_SECONDS
        val target = checkpoint.value - blocksBack
        val floor = ZcashNetwork.Mainnet.saplingActivationHeight.value
        return BlockHeight.new(maxOf(target, floor))
    }

    // Non-blocking: invoked from the service main thread; all work runs on Dispatchers.IO.
    override fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            stopJob?.join() // wait for any in-flight teardown before re-creating the synchronizer
            try {
                loading = true
                val endpoint = LightWalletEndpoint(primaryHost, primaryPort, isSecure = true)

                // If the app's seed changed (wallet restored/recreated), the SDK database
                // belongs to the old seed — erase it before initializing.
                val fp = seedFingerprint()
                val storedFp = prefs().getString(KEY_SEED_FP, null)
                if (storedFp != null && storedFp != fp) {
                    Log.i(TAG, "Seed changed since last init; erasing ZEC SDK database")
                    runCatching {
                        Synchronizer.erase(appContext = context,
                            network = ZcashNetwork.Mainnet, alias = SDK_ALIAS)
                    }
                    prefs().edit().clear().apply()
                }

                val alreadyInitialized = isWalletInitialized()
                // A seed older than a day that the SDK has never seen is a restore,
                // not a brand-new wallet — scan history from an estimated birthday.
                val isRestore = seedCreationTimeSeconds > 0L &&
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
                synchronizer = sync
                if (!alreadyInitialized) markWalletInitialized()

                val accounts = sync.getAccounts()
                val account = accounts.firstOrNull()
                cachedAccount = account
                if (account != null) {
                    cachedAddress = sync.getUnifiedAddress(account)
                    cachedTAddress = sync.getTransparentAddress(account)
                    cachedSaplingAddress = runCatching { sync.getSaplingAddress(account) }.getOrNull()
                    Log.d(TAG, "UA=$cachedAddress t=$cachedTAddress sapling=$cachedSaplingAddress")
                }

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

            } catch (e: Exception) {
                Log.e(TAG, "Sync startup error: ${e.message}", e)
                loading = false
                connected = false
                notifyUpdated()
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
            Log.w(TAG, "Failed to map tx: ${e.message}")
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
                Log.e(TAG, "ZEC send failed: ${e.message}", e)
                callback.onError(e)
            }
        }
    }
}