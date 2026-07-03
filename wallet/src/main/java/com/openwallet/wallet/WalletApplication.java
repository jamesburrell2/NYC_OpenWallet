package com.openwallet.wallet;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.StrictMode;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatDelegate;

import com.openwallet.core.coins.CoinID;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.coins.families.CardanoFamily;
import com.openwallet.core.coins.families.ChiaFamily;
import com.openwallet.core.coins.families.EvmFamily;
import com.openwallet.core.coins.families.SolanaFamily;
import com.openwallet.core.coins.families.ZcashSdkFamily;
import com.openwallet.core.exchange.shapeshift.ShapeShift;
import com.openwallet.core.util.HardwareSoftwareCompliance;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.core.wallet.WalletProtobufSerializer;
import com.openwallet.wallet.service.CoinService;
import com.openwallet.wallet.service.CoinServiceImpl;
import com.openwallet.wallet.util.Fonts;
import com.openwallet.wallet.util.LinuxSecureRandom;
import com.openwallet.wallet.util.NetworkUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Files;

import org.acra.ACRA;
import org.acra.annotation.ReportsCrashes;
import org.acra.sender.HttpSender;
import org.bitcoinj.crypto.MnemonicCode;
import org.bitcoinj.store.UnreadableWalletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * @author John L. Jegutanis
 * @author Andreas Schildbach
 */
@ReportsCrashes(
        // Also uncomment ACRA.init(this) in onCreate
        httpMethod = HttpSender.Method.PUT,
        reportType = HttpSender.Type.JSON
)
public class WalletApplication extends Application {
    private static final Logger log = LoggerFactory.getLogger(WalletApplication.class);

    private static HashMap<String, Typeface> typefaces;
    private Configuration config;
    private ActivityManager activityManager;

    private Intent coinServiceIntent;
    private Intent coinServiceConnectIntent;
    private Intent coinServiceCancelCoinsReceivedIntent;

    private File walletFile;
    @Nullable
    private Wallet wallet;
    private PackageInfo packageInfo;
    private String versionString;

    private long lastStop;
    private ConnectivityManager connManager;
    private ShapeShift shapeShift;
    private File txCachePath;

    @Override
    public void onCreate() {
//        ACRA.init(this);

        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(this));

        // Apply saved dark mode preference before any activity launches
        AppCompatDelegate.setDefaultNightMode(config.isDarkMode()
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);

        new LinuxSecureRandom(); // init proper random number generator
        performComplianceTests();

        initLogging();

        // TODO review this
        StrictMode.setThreadPolicy(
                new StrictMode.ThreadPolicy.Builder().detectAll().permitDiskReads().permitDiskWrites().penaltyLog().build());

        super.onCreate();

        packageInfo = packageInfoFromContext(this);
        versionString = packageInfo.versionName.replace(" ", "_") + "__" +
                packageInfo.packageName + "_android";

        activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);

        coinServiceIntent = new Intent(this, CoinServiceImpl.class);
        coinServiceConnectIntent = new Intent(CoinService.ACTION_CONNECT_COIN,
                null, this, CoinServiceImpl.class);
        coinServiceCancelCoinsReceivedIntent = new Intent(CoinService.ACTION_CANCEL_COINS_RECEIVED,
                null, this, CoinServiceImpl.class);

        createTxCache();

        // Set MnemonicCode.INSTANCE if needed
        if (MnemonicCode.INSTANCE == null) {
            try {
                MnemonicCode.INSTANCE = new MnemonicCode();
            } catch (IOException e) {
                throw new RuntimeException("Could not set MnemonicCode.INSTANCE", e);
            }
        }

        config.updateLastVersionCode(packageInfo.versionCode);

        connManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        walletFile = getWalletFileForIndex(getActiveWalletIndex());
        loadWallet();

        afterLoadWallet();

        Fonts.initFonts(this.getAssets());
    }

    private void createTxCache() {
        txCachePath = new File(this.getCacheDir(), Constants.TX_CACHE_NAME);
        if (!txCachePath.exists()) {
            if (!txCachePath.mkdirs()) {
                txCachePath = null;
                log.error("Error creating transaction cache folder");
                return;
            }
        }

        // Make cache dirs for all coins
        for (CoinType type : Constants.SUPPORTED_COINS) {
            File coinCachePath = new File(txCachePath, type.getId());
            if (!coinCachePath.exists()) {
                if (!coinCachePath.mkdirs()) {
                    txCachePath = null;
                    log.error("Error creating transaction cache folder");
                    return;
                }
            }
        }
    }

    public boolean isConnected() {
        NetworkInfo activeInfo = connManager.getActiveNetworkInfo();
        return activeInfo != null && activeInfo.isConnected();
    }

    public ShapeShift getShapeShift() {
        if (shapeShift == null) {
            shapeShift = new ShapeShift(NetworkUtils.getHttpClient(getApplicationContext()));
        }
        return shapeShift;
    }

    public File getTxCachePath() {
        return txCachePath;
    }

    /**
     * Some devices have software bugs that causes the EC crypto to malfunction.
     */
    private void performComplianceTests() {
        if (!config.isDeviceCompatible()) {
            if (!HardwareSoftwareCompliance.isEllipticCurveCryptographyCompliant()) {
                config.setDeviceCompatible(false);
                ACRA.getErrorReporter().handleSilentException(
                        new Exception("Device failed EllipticCurveCryptographyCompliant test"));
            } else {
                config.setDeviceCompatible(true);
            }
        }
    }

    private void afterLoadWallet() {
        setupFeeProvider();
        restoreHdFamilyCoins();
//        wallet.autosaveToFile(walletFile, 1, TimeUnit.SECONDS, new WalletAutosaveEventListener());
//
        // clean up spam
//        wallet.cleanup();
//
//        ensureKey();
//
//        migrateBackup();
    }

    private void setupFeeProvider() {
        CoinType.setFeeProvider(new CoinType.FeeProvider() {
            @Override
            public Value getFeeValue(CoinType type) {
                return config.getFeeValue(type);
            }
        });
    }

    /**
     * Re-creates EVM/Solana/Cardano/Chia wallet accounts that were added by the user but are
     * not persisted in the wallet protobuf.  Only possible for unencrypted wallets; encrypted
     * wallets will recreate their accounts the next time the user explicitly adds the coin.
     */
    private void restoreHdFamilyCoins() {
        if (wallet == null || wallet.isEncrypted()) return;
        List<CoinType> missing = new ArrayList<>();

        // Always ensure DEFAULT_COINS (NYC) are present in the wallet.
        for (CoinType type : Constants.DEFAULT_COINS) {
            if (!wallet.isAccountExists(type)) {
                missing.add(type);
            }
        }

        // Also restore EVM/Solana/Cardano/Chia coins that the user previously added.
        Set<String> savedIds = config.getEnabledHdCoinIds();
        for (String id : savedIds) {
            try {
                CoinType type = CoinID.typeFromId(id);
                if (!wallet.isAccountExists(type)) {
                    boolean isHdFamily = type instanceof EvmFamily
                            || type instanceof SolanaFamily
                            || type instanceof CardanoFamily
                            || type instanceof ChiaFamily
                            || type instanceof ZcashSdkFamily;
                    if (isHdFamily && !missing.contains(type)) missing.add(type);
                }
            } catch (IllegalArgumentException ignored) {
                // Unknown coin ID — skip
            }
        }

        if (!missing.isEmpty()) {
            try {
                wallet.createAccounts(missing, false, null);
                log.info("Restored {} coin accounts after wallet load", missing.size());
            } catch (Exception e) {
                log.warn("Failed to restore coins: {}", e.getMessage());
            }
        }
    }

    private void initLogging() {
//        final File logDir = getDir("log", Constants.TEST ? Context.MODE_WORLD_READABLE : MODE_PRIVATE);
//        final File logFile = new File(logDir, "wallet.log");
//
//        final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
//
//        final PatternLayoutEncoder filePattern = new PatternLayoutEncoder();
//        filePattern.setContext(context);
//        filePattern.setPattern("%d{HH:mm:ss.SSS} [%thread] %logger{0} - %msg%n");
//        filePattern.start();
//
//        final RollingFileAppender<ILoggingEvent> fileAppender = new RollingFileAppender<ILoggingEvent>();
//        fileAppender.setContext(context);
//        fileAppender.setFile(logFile.getAbsolutePath());
//
//        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<ILoggingEvent>();
//        rollingPolicy.setContext(context);
//        rollingPolicy.setParent(fileAppender);
//        rollingPolicy.setFileNamePattern(logDir.getAbsolutePath() + "/wallet.%d.log.gz");
//        rollingPolicy.setMaxHistory(7);
//        rollingPolicy.start();
//
//        fileAppender.setEncoder(filePattern);
//        fileAppender.setRollingPolicy(rollingPolicy);
//        fileAppender.start();
//
//        final PatternLayoutEncoder logcatTagPattern = new PatternLayoutEncoder();
//        logcatTagPattern.setContext(context);
//        logcatTagPattern.setPattern("%logger{0}");
//        logcatTagPattern.start();
//
//        final PatternLayoutEncoder logcatPattern = new PatternLayoutEncoder();
//        logcatPattern.setContext(context);
//        logcatPattern.setPattern("[%thread] %msg%n");
//        logcatPattern.start();
//
//        final LogcatAppender logcatAppender = new LogcatAppender();
//        logcatAppender.setContext(context);
//        logcatAppender.setTagEncoder(logcatTagPattern);
//        logcatAppender.setEncoder(logcatPattern);
//        logcatAppender.start();
//
//        final ch.qos.logback.classic.Logger log = context.getLogger(Logger.ROOT_LOGGER_NAME);
//        log.addAppender(fileAppender);
//        log.addAppender(logcatAppender);
//        log.setLevel(Level.INFO);
    }


    public Configuration getConfiguration() {
        return config;
    }

    /**
     * Get the current wallet.
     */
    @Nullable
    public Wallet getWallet() {
        return wallet;
    }

    @Nullable
    public WalletAccount getAccount(@Nullable String accountId) {
        if (wallet != null) {
            return wallet.getAccount(accountId);
        } else {
            return null;
        }
    }

    public List<WalletAccount> getAccounts(CoinType type) {
        if (wallet != null) {
            return wallet.getAccounts(type);
        } else {
            return ImmutableList.of();
        }
    }


    public List<WalletAccount> getAccounts(List<CoinType> types) {
        if (wallet != null) {
            return wallet.getAccounts(types);
        } else {
            return ImmutableList.of();
        }
    }

    public List<WalletAccount> getAccounts(AbstractAddress address) {
        if (wallet != null) {
            return wallet.getAccounts(address);
        } else {
            return ImmutableList.of();
        }
    }

    public List<WalletAccount> getAllAccounts() {
        if (wallet != null) {
            return wallet.getAllAccounts();
        } else {
            return ImmutableList.of();
        }
    }

    /**
     * Check if account exists
     */
    public boolean isAccountExists(String accountId) {
        if (wallet != null) {
            return wallet.isAccountExists(accountId);
        } else {
            return false;
        }
    }

    /**
     * Check if accounts exists for the spesific coin type
     */
    public boolean isAccountExists(CoinType type) {
        return wallet != null && wallet.isAccountExists(type);
    }

    public void setEmptyWallet() {
        setWallet(null);
    }

    public void setWallet(@Nullable Wallet wallet) {
        // Disable auto-save of the previous wallet if exists, so it doesn't override the new one
        if (this.wallet != null) {
            this.wallet.shutdownAutosaveAndWait();
        }

        this.wallet = wallet;
        if (this.wallet != null) {
            this.wallet.autosaveToFile(walletFile, Constants.WALLET_WRITE_DELAY,
                    Constants.WALLET_WRITE_DELAY_UNIT, null);
        }
    }

    private void loadWallet() {
        if (walletFile.exists()) {
            final long start = System.currentTimeMillis();

            FileInputStream walletStream = null;

            try {
                walletStream = new FileInputStream(walletFile);

                setWallet(WalletProtobufSerializer.readWallet(walletStream));

                log.info("wallet loaded from: '" + walletFile + "', took " + (System.currentTimeMillis() - start) + "ms");
            } catch (final FileNotFoundException e) {
                ACRA.getErrorReporter().handleException(e);
                Toast.makeText(WalletApplication.this, R.string.error_could_not_read_wallet, Toast.LENGTH_LONG).show();
            } catch (final UnreadableWalletException e) {
                Toast.makeText(WalletApplication.this, R.string.error_could_not_read_wallet, Toast.LENGTH_LONG).show();
                ACRA.getErrorReporter().handleException(e);
            } finally {
                if (walletStream != null) {
                    try {
                        walletStream.close();
                    } catch (final IOException x) { /* ignore */ }
                }
            }
        }
    }


    public void saveWalletNow() {
        if (wallet != null) {
            wallet.saveNow();
        }
    }

    public void saveWalletLater() {
        if (wallet != null) {
            wallet.saveLater();
        }
    }

    public void startBlockchainService(CoinService.ServiceMode mode) {
        final Intent intent;
        switch (mode) {
            case CANCEL_COINS_RECEIVED:
                intent = coinServiceCancelCoinsReceivedIntent;
                break;
            case NORMAL:
            default:
                intent = coinServiceIntent;
                break;
        }
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            // Android 12+ (BackgroundServiceStartNotAllowedException extends this):
            // the app was briefly treated as background during a lifecycle edge (e.g.
            // resume right after process restart). Don't crash — the service starts on
            // the next foreground event (onResume/tick) instead.
            log.warn("Deferred CoinService start; app treated as background: {}",
                    e.getClass().getSimpleName());
        }
    }

    public void stopBlockchainService() {
        stopService(coinServiceIntent);
    }


    public static PackageInfo packageInfoFromContext(final Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (final PackageManager.NameNotFoundException x) {
            throw new RuntimeException(x);
        }
    }

    public PackageInfo packageInfo() {
        return packageInfo;
    }

    public String getVersionString() {
        return versionString;
    }

    public void touchLastResume() {
        lastStop = -1;
    }

    public void touchLastStop() {
        lastStop = SystemClock.elapsedRealtime();
    }

    public long getLastStop() {
        return lastStop;
    }

    public void maybeConnectAccount(WalletAccount account) {
        if (!account.isConnected()) {
            coinServiceConnectIntent.putExtra(Constants.ARG_ACCOUNT_ID, account.getId());
            startService(coinServiceConnectIntent);
        }
    }

    // -------------------------------------------------------------------------
    // Multi-wallet support (up to MAX_WALLETS slots)
    // -------------------------------------------------------------------------

    private static final int MAX_WALLETS = 5;
    private static final String MW_PREFS_ACTIVE  = "mw_active_index";
    private static final String MW_PREFS_COUNT   = "mw_count";
    private static final String MW_PREFS_NAME    = "mw_name_";

    /** Wallet file for the given slot index. Slot 0 reuses the legacy "wallet" file. */
    private File getWalletFileForIndex(int index) {
        if (index == 0) return getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF);
        return getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF + "_" + index);
    }

    private SharedPreferences mwPrefs() {
        return PreferenceManager.getDefaultSharedPreferences(this);
    }

    public int getWalletCount() {
        int stored = mwPrefs().getInt(MW_PREFS_COUNT, -1);
        if (stored < 0) {
            // First run: count = 1 if slot-0 wallet file already exists, else 0.
            // Always check slot 0's file (not the mutable walletFile field which may point to
            // a different slot after getActiveWalletIndex() was applied during startup).
            int inferred = getWalletFileForIndex(0).exists() ? 1 : 0;
            mwPrefs().edit().putInt(MW_PREFS_COUNT, inferred).apply();
            return inferred;
        }
        return stored;
    }

    public int getActiveWalletIndex() {
        return mwPrefs().getInt(MW_PREFS_ACTIVE, 0);
    }

    /** Returns the loaded Wallet object for the given slot, or null if the slot is not active / empty. */
    @Nullable
    public Wallet getWalletAtIndex(int index) {
        if (index == getActiveWalletIndex()) return wallet;
        // Other slots are not kept in memory; indicate existence via file presence.
        File f = getWalletFileForIndex(index);
        return f.exists() ? wallet : null; // non-null signals "slot exists" to the UI
    }

    public String getWalletName(int index) {
        return mwPrefs().getString(MW_PREFS_NAME + index, "Wallet " + (index + 1));
    }

    public void setWalletName(int index, String name) {
        mwPrefs().edit().putString(MW_PREFS_NAME + index, name).apply();
    }

    /**
     * Switch the active wallet to the given slot index.
     * Saves the current wallet first, then loads the wallet at {@code index}.
     */
    public void switchToWallet(int index) {
        if (index < 0 || index >= MAX_WALLETS) return;
        if (index == getActiveWalletIndex()) return;

        // Persist current wallet before switching
        saveWalletNow();
        stopBlockchainService();

        mwPrefs().edit().putInt(MW_PREFS_ACTIVE, index).apply();

        // Update walletFile reference to the new slot and reload
        walletFile = getWalletFileForIndex(index);
        loadWallet();
        afterLoadWallet();

        // Restart the blockchain service so it connects to the new wallet's accounts,
        // then send ACTION_RESET_WALLET to rebuild server connections and refresh UTXOs.
        startBlockchainService(CoinService.ServiceMode.NORMAL);
        Intent resetIntent = new Intent(CoinService.ACTION_RESET_WALLET, null,
                this, CoinServiceImpl.class);
        startService(resetIntent);
    }

    /**
     * Prepare a new wallet slot so the next wallet creation writes to a fresh file
     * rather than overwriting the current one. Saves the current wallet, advances the
     * active-slot pointer to the new slot, and returns the new slot index.
     * Returns -1 if no more slots are available.
     */
    public int prepareNewWalletSlot() {
        int newIndex = getWalletCount();
        if (newIndex >= MAX_WALLETS) return -1;

        // Persist current wallet before switching context
        saveWalletNow();

        // Shutdown autosave of current wallet so it does not race with the new one
        if (wallet != null) {
            wallet.shutdownAutosaveAndWait();
            wallet = null;
        }

        // Register and activate the new slot
        mwPrefs().edit()
                .putInt(MW_PREFS_COUNT, newIndex + 1)
                .putInt(MW_PREFS_ACTIVE, newIndex)
                .apply();

        // Point walletFile to the new slot file
        walletFile = newIndex == 0
                ? getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF)
                : getFileStreamPath(Constants.WALLET_FILENAME_PROTOBUF + "_" + newIndex);

        return newIndex;
    }

    /**
     * Delete the wallet at the given slot. Cannot delete the active wallet.
     * Returns true on success.
     */
    public boolean deleteWallet(int index) {
        if (index == getActiveWalletIndex()) return false; // refuse to delete active
        File f = getWalletFileForIndex(index);
        if (!f.exists()) return false;
        boolean deleted = f.delete();
        if (deleted) {
            int count = Math.max(0, getWalletCount() - 1);
            mwPrefs().edit()
                    .putInt(MW_PREFS_COUNT, count)
                    .remove(MW_PREFS_NAME + index)
                    .apply();
        }
        return deleted;
    }
}
