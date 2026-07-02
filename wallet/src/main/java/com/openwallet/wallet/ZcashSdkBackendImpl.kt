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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ZcashSdkBackendImpl(
    private val context: Context,
    private val seedBytes: ByteArray,
    private val primaryHost: String = "zec.rocks",
    private val primaryPort: Int = 443,
) : ZcashBackendDelegate {

    companion object {
        private const val TAG = "ZcashSdkBackend"
        private const val SDK_ALIAS = "openwallet_zec"
        private const val PREFS_NAME = "zec_sdk_prefs"
        private const val KEY_INITIALIZED = "wallet_initialized"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

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

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun isWalletInitialized() = prefs().getBoolean(KEY_INITIALIZED, false)
    private fun markWalletInitialized() = prefs().edit().putBoolean(KEY_INITIALIZED, true).apply()

    override fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            try {
                loading = true
                val endpoint = LightWalletEndpoint(primaryHost, primaryPort, isSecure = true)
                val alreadyInitialized = isWalletInitialized()
                val initMode = if (alreadyInitialized) {
                    Log.d(TAG, "Resuming existing wallet")
                    WalletInitMode.ExistingWallet
                } else {
                    Log.d(TAG, "First launch creating new wallet")
                    WalletInitMode.NewWallet
                }
                val birthday: BlockHeight? = if (!alreadyInitialized) {
                    runCatching { BlockHeight.ofLatestCheckpoint(context, ZcashNetwork.Mainnet) }
                        .getOrNull().also { Log.d(TAG, "Birthday=$it") }
                } else null

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
                        }
                    }
                }

                launch {
                    sync.transactions.collectLatest { txList ->
                        cachedTransactions = txList.mapNotNull { mapTransaction(it) }
                    }
                }

                launch {
                    sync.progress.collectLatest { pct ->
                        syncProgressPercent = (pct.decimal * 100f).toInt().coerceIn(0, 100)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Sync startup error: ${e.message}", e)
                loading = false
                connected = false
            }
        }
    }

    override fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        synchronizer?.close()
        synchronizer = null
        cachedAccount = null
        cachedSaplingAddress = null
        connected = false
        loading = false
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