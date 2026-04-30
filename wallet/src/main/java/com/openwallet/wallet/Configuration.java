package com.openwallet.wallet;

import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.text.format.DateUtils;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.wallet.util.WalletUtils;
import com.google.common.collect.ImmutableMap;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * @author John L. Jegutanis
 * @author Andreas Schildbach
 */
public class Configuration {

    public final int lastVersionCode;

    private final SharedPreferences prefs;

    private static final String PREFS_KEY_LAST_VERSION = "last_version";
    private static final String PREFS_KEY_LAST_USED = "last_used";
    @Deprecated
    private static final String PREFS_KEY_LAST_POCKET = "last_pocket";
    private static final String PREFS_KEY_LAST_ACCOUNT = "last_account";


    /* Preference keys. Check also res/xml/preferences.xml */
    public static final String PREFS_KEY_BTC_PRECISION = "btc_precision";
    public static final String PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification";
    public static final String PREFS_KEY_EXCHANGE_CURRENCY = "exchange_currency";
    public static final String PREFS_KEY_FEES = "fees";
    public static final String PREFS_KEY_DISCLAIMER = "disclaimer";
    public static final String PREFS_KEY_SELECTED_ADDRESS = "selected_address";

    private static final String PREFS_KEY_LABS_QR_PAYMENT_REQUEST = "labs_qr_payment_request";

    private static final String PREFS_KEY_CACHED_EXCHANGE_LOCAL_CURRENCY = "cached_exchange_local_currency";
    private static final String PREFS_KEY_CACHED_EXCHANGE_RATES_JSON = "cached_exchange_rates_json";

    private static final String PREFS_KEY_LAST_EXCHANGE_DIRECTION = "last_exchange_direction";
    private static final String PREFS_KEY_CHANGE_LOG_VERSION = "change_log_version";
    public static final String PREFS_KEY_REMIND_BACKUP = "remind_backup";

    public static final String PREFS_KEY_MANUAL_RECEIVING_ADDRESSES = "manual_receiving_addresses";

    public static final String PREFS_KEY_DEVICE_COMPATIBLE = "device_compatible";

    public static final String PREFS_KEY_TERMS_ACCEPTED = "terms_accepted";

    private static final String PREFS_KEY_NYC_ELECTRUM_SERVER = "nyc_electrum_server";
    /** Coin type IDs for EVM/Solana/Cardano/Chia accounts that are not stored in the wallet protobuf. */
    private static final String PREFS_KEY_ENABLED_HD_COINS = "enabled_hd_coin_ids";

    public static final String PREFS_KEY_DARK_MODE = "dark_mode";
    public static final String PREFS_KEY_HIDE_BALANCES = "hide_balances";
    public static final String PREFS_KEY_SCREEN_LOCK_ENABLED = "screen_lock_enabled";

    private static final int PREFS_DEFAULT_BTC_SHIFT = 3;
    private static final int PREFS_DEFAULT_BTC_PRECISION = 2;

    private static final Logger log = LoggerFactory.getLogger(Configuration.class);

    public Configuration(final SharedPreferences prefs) {
        this.prefs = prefs;

        this.lastVersionCode = prefs.getInt(PREFS_KEY_LAST_VERSION, 0);
    }

    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public void updateLastVersionCode(final int currentVersionCode) {
        if (currentVersionCode != lastVersionCode) {
            prefs.edit().putInt(PREFS_KEY_LAST_VERSION, currentVersionCode).apply();
        }

        if (currentVersionCode > lastVersionCode)
            log.info("detected app upgrade: " + lastVersionCode + " -> " + currentVersionCode);
        else if (currentVersionCode < lastVersionCode)
            log.warn("detected app downgrade: " + lastVersionCode + " -> " + currentVersionCode);

        applyUpdates();
    }

    private void applyUpdates() {
        if (prefs.contains(PREFS_KEY_LAST_POCKET)) {
            prefs.edit().remove(PREFS_KEY_LAST_POCKET).apply();
        }
    }

    public long getLastUsedAgo() {
        final long now = System.currentTimeMillis();

        return now - prefs.getLong(PREFS_KEY_LAST_USED, 0);
    }

    public void touchLastUsed() {
        final long prefsLastUsed = prefs.getLong(PREFS_KEY_LAST_USED, 0);
        final long now = System.currentTimeMillis();
        prefs.edit().putLong(PREFS_KEY_LAST_USED, now).apply();

        log.info("just being used - last used {} minutes ago", (now - prefsLastUsed) / DateUtils.MINUTE_IN_MILLIS);
    }

    @Nullable
    public String getLastAccountId() {
        return prefs.getString(PREFS_KEY_LAST_ACCOUNT, null);
    }

    public void touchLastAccountId(String accountId) {
        String lastAccountId = prefs.getString(PREFS_KEY_LAST_ACCOUNT, Constants.DEFAULT_COIN.getId());
        if (!lastAccountId.equals(accountId)) {
            prefs.edit().putString(PREFS_KEY_LAST_ACCOUNT, accountId).apply();
            log.info("last used wallet account id: {}", accountId);
        }
    }

    public Map<CoinType, Value> getFeeValues() {
        JSONObject feesJson = getFeesJson();
        ImmutableMap.Builder<CoinType, Value> feesMapBuilder = ImmutableMap.builder();

        for (CoinType type : Constants.SUPPORTED_COINS) {
            Value fee = getFeeFromJson(feesJson, type);
            feesMapBuilder.put(type, fee);
        }

        return feesMapBuilder.build();
    }

    public Value getFeeValue(CoinType type) {
        return getFeeFromJson(getFeesJson(), type);
    }

    public void resetFeeValue(CoinType type) {
        JSONObject feesJson = getFeesJson();
        feesJson.remove(type.getId());
        prefs.edit().putString(PREFS_KEY_FEES, feesJson.toString()).apply();

    }

    public void setFeeValue(final Value feeValue) {
        JSONObject feesJson = getFeesJson();
        try {
            feesJson.put(feeValue.type.getId(), feeValue.toUnitsString());
        } catch (JSONException e) {
            // Should not happen
            log.error("Error setting fee value", e);
        }
        prefs.edit().putString(PREFS_KEY_FEES, feesJson.toString()).apply();
    }

    private Value getFeeFromJson(JSONObject feesJson, CoinType type) {
        String feeStr = feesJson.optString(type.getId());
        if (feeStr.isEmpty()) {
            return type.getDefaultFeeValue();
        } else {
            return Value.valueOf(type, feeStr);
        }
    }

    private JSONObject getFeesJson() {
        try {
            return new JSONObject(prefs.getString(PREFS_KEY_FEES, ""));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /**
     * Returns the user selected currency. If defaultFallback is set to true it return a default
     * currency is no user selected setting found.
     */
    @Nullable
    public String getExchangeCurrencyCode(boolean useDefaultFallback) {
        String defaultCode = null;
        if (useDefaultFallback) {
            defaultCode = WalletUtils.localeCurrencyCode();
            defaultCode = defaultCode == null ? Constants.DEFAULT_EXCHANGE_CURRENCY : defaultCode;
        }
        return prefs.getString(PREFS_KEY_EXCHANGE_CURRENCY, defaultCode);
    }

    /**
     * Returns the user selected currency or if not set the default
     */
    public String getExchangeCurrencyCode() {
        return getExchangeCurrencyCode(true);
    }

    public void setExchangeCurrencyCode(final String exchangeCurrencyCode) {
        prefs.edit().putString(PREFS_KEY_EXCHANGE_CURRENCY, exchangeCurrencyCode).apply();
    }

    public JSONObject getCachedExchangeRatesJson() {
        try {
            return new JSONObject(prefs.getString(PREFS_KEY_CACHED_EXCHANGE_RATES_JSON, ""));
        } catch (JSONException e) {
            return null;
        }
    }

    public String getCachedExchangeLocalCurrency() {
        return prefs.getString(PREFS_KEY_CACHED_EXCHANGE_LOCAL_CURRENCY, null);
    }

    public void setCachedExchangeRates(String currency, JSONObject exchangeRatesJson) {
        final SharedPreferences.Editor edit = prefs.edit();
        edit.putString(PREFS_KEY_CACHED_EXCHANGE_LOCAL_CURRENCY, currency);
        edit.putString(PREFS_KEY_CACHED_EXCHANGE_RATES_JSON, exchangeRatesJson.toString());
        edit.apply();
    }

    public boolean getLastExchangeDirection() {
        return prefs.getBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, true);
    }

    public void setLastExchangeDirection(final boolean exchangeDirection) {
        prefs.edit().putBoolean(PREFS_KEY_LAST_EXCHANGE_DIRECTION, exchangeDirection).apply();
    }

    public boolean isManualAddressManagement() {
        return prefs.getBoolean(PREFS_KEY_MANUAL_RECEIVING_ADDRESSES, false);
    }

    public void setDeviceCompatible(final boolean isDeviceCompatible) {
        prefs.edit().putBoolean(PREFS_KEY_DEVICE_COMPATIBLE, isDeviceCompatible).apply();
    }

    public boolean isDeviceCompatible() {
        return prefs.getBoolean(PREFS_KEY_DEVICE_COMPATIBLE, false);
    }

    public boolean getTermsAccepted() {
        return prefs.getBoolean(PREFS_KEY_TERMS_ACCEPTED, false);
    }

    public void setTermAccepted(final boolean isTermsAccepted) {
        prefs.edit().putBoolean(PREFS_KEY_TERMS_ACCEPTED, isTermsAccepted).apply();
    }

    @Nullable
    public String getNycElectrumServer() {
        return prefs.getString(PREFS_KEY_NYC_ELECTRUM_SERVER, null);
    }

    public void setNycElectrumServer(final String hostPort) {
        prefs.edit().putString(PREFS_KEY_NYC_ELECTRUM_SERVER, hostPort).apply();
    }

    /**
     * Returns the user-configured ElectrumX server for the given coin type, or null if none is
     * set (meaning the app will use the built-in DEFAULT_COINS_SERVERS entry).
     *
     * For NewYorkCoin the legacy "nyc_electrum_server" preference is checked first so that
     * existing user settings are not lost after this API was generalised.
     */
    @Nullable
    public String getCoinElectrumServer(CoinType type) {
        // Backward-compat: NYC was stored under its own dedicated key before this API existed.
        if ("newyorkcoin.main".equals(type.getId())) {
            String legacy = prefs.getString(PREFS_KEY_NYC_ELECTRUM_SERVER, null);
            if (legacy != null && !legacy.isEmpty()) return legacy;
        }
        return prefs.getString("coin_server_" + type.getId(), null);
    }

    /**
     * Persists a user-configured ElectrumX server for a coin. Pass null or an empty string to
     * clear the override and fall back to the built-in DEFAULT_COINS_SERVERS entry.
     */
    public void setCoinElectrumServer(CoinType type, String hostPort) {
        SharedPreferences.Editor editor = prefs.edit();
        if (hostPort == null || hostPort.isEmpty()) {
            editor.remove("coin_server_" + type.getId());
        } else {
            editor.putString("coin_server_" + type.getId(), hostPort);
        }
        editor.apply();
    }

    /**
     * Record that an EVM/Solana/Cardano/Chia coin account was added to the wallet.
     * These coin families are not serialized in the wallet protobuf, so we track them
     * here and recreate them on load via {@link WalletApplication#restoreHdFamilyCoins}.
     */
    public void addEnabledHdCoinId(String coinId) {
        Set<String> current = new HashSet<>(prefs.getStringSet(PREFS_KEY_ENABLED_HD_COINS,
                Collections.<String>emptySet()));
        current.add(coinId);
        prefs.edit().putStringSet(PREFS_KEY_ENABLED_HD_COINS, current).apply();
    }

    /**
     * Remove a coin ID from the enabled-coins list (e.g. when the user removes the account).
     */
    public void removeEnabledHdCoinId(String coinId) {
        Set<String> current = new HashSet<>(prefs.getStringSet(PREFS_KEY_ENABLED_HD_COINS,
                Collections.<String>emptySet()));
        if (current.remove(coinId)) {
            prefs.edit().putStringSet(PREFS_KEY_ENABLED_HD_COINS, current).apply();
        }
    }

    /**
     * Returns the set of coin type IDs that should be present in the wallet but are not
     * persisted in the protobuf (EVM/Solana/Cardano/Chia families).
     */
    public Set<String> getEnabledHdCoinIds() {
        return Collections.unmodifiableSet(
                prefs.getStringSet(PREFS_KEY_ENABLED_HD_COINS, Collections.<String>emptySet()));
    }

    // ── Display ──────────────────────────────────────────────────────────────

    public boolean isDarkMode() {
        return prefs.getBoolean(PREFS_KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_DARK_MODE, enabled).apply();
    }

    // ── Privacy ──────────────────────────────────────────────────────────────

    public boolean isHideBalances() {
        return prefs.getBoolean(PREFS_KEY_HIDE_BALANCES, false);
    }

    public void setHideBalances(boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_HIDE_BALANCES, enabled).apply();
    }

    public boolean isScreenLockEnabled() {
        return prefs.getBoolean(PREFS_KEY_SCREEN_LOCK_ENABLED, true);
    }

    public void setScreenLockEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREFS_KEY_SCREEN_LOCK_ENABLED, enabled).apply();
    }

}
