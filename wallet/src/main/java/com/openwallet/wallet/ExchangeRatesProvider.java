package com.openwallet.wallet;

/*
 * Copyright 2011-2014 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.BaseColumns;

import android.text.TextUtils;

import com.openwallet.core.coins.CoinID;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.FiatValue;
import com.openwallet.core.coins.Value;
import com.openwallet.core.util.ExchangeRateBase;
import com.openwallet.wallet.util.NetworkUtils;
import com.google.common.collect.ImmutableMap;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * @author Andreas Schildbach
 */
public class ExchangeRatesProvider extends ContentProvider {

    public static class ExchangeRate {
        @Nonnull public final ExchangeRateBase rate;
        public final String currencyCodeId;
        @Nullable public final String source;

        public ExchangeRate(@Nonnull final ExchangeRateBase rate,
                            final String currencyCodeId, @Nullable final String source) {
            this.rate = rate;
            this.currencyCodeId = currencyCodeId;
            this.source = source;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + '[' + rate.value1 + " ~ " + rate.value2 + ']';
        }
    }

    public static final String KEY_CURRENCY_ID = "currency_id";
    private static final String KEY_RATE_COIN = "rate_coin";
    private static final String KEY_RATE_FIAT = "rate_fiat";
    private static final String KEY_RATE_COIN_CODE = "rate_coin_code";
    private static final String KEY_RATE_FIAT_CODE = "rate_fiat_code";
    private static final String KEY_SOURCE = "source";

    private static final String QUERY_PARAM_OFFLINE = "offline";

    private ConnectivityManager connManager;
    private Configuration config;

    private Map<String, ExchangeRate> localToCryptoRates = null;
    private long localToCryptoLastUpdated = 0;
    private String lastLocalCurrency = null;

    private Map<String, ExchangeRate> cryptoToLocalRates = null;
    private long cryptoToLocalLastUpdated = 0;
    private String lastCryptoCurrency = null;

    private static final String COINGECKO_API_URL =
            "https://api.coingecko.com/api/v3/simple/price";
    /** Fiat currencies to fetch when a "to-local" query is made for a specific crypto. */
    private static final String FIAT_CURRENCIES =
            "usd,eur,gbp,jpy,cny,cad,aud,chf,inr,brl,rub,mxn,krw,sgd,hkd,nok,sek,dkk,nzd";
    private static final String OPENWALLET_SOURCE = "openwallet.xyz";

    // CoinGecko coin ID map — symbol → gecko ID. Unknown coins are silently skipped.
    private static final Map<String, String> SYMBOL_TO_GECKO_ID;
    private static final Map<String, String> GECKO_ID_TO_SYMBOL;
    static {
        Map<String, String> s2g = new HashMap<>();
        s2g.put("NYC",  "newyorkcoin");
        s2g.put("BTC",  "bitcoin");
        s2g.put("LTC",  "litecoin");
        s2g.put("DOGE", "dogecoin");
        s2g.put("ZEC",  "zcash");
        s2g.put("DASH", "dash");
        s2g.put("DGB",  "digibyte");
        s2g.put("VTC",  "vertcoin");
        s2g.put("XVG",  "verge");
        s2g.put("NMC",  "namecoin");
        s2g.put("FTC",  "feathercoin");
        s2g.put("RDD",  "reddcoin");
        s2g.put("BLK",  "blackcoin");
        s2g.put("MONA", "monacoin");
        s2g.put("PPC",  "peercoin");
        s2g.put("POT",  "potcoin");
        s2g.put("NEOS", "neoscoin");
        s2g.put("OK",   "okcash");
        s2g.put("NVC",  "novacoin");
        s2g.put("AUR",  "auroracoin");
        s2g.put("NSR",  "nushares");
        s2g.put("NBT",  "nubits");
        s2g.put("IXC",  "ixcoin");
        s2g.put("NXT",  "nxt");
        s2g.put("RBY",  "rubycoin");
        s2g.put("DGC",  "digitalcoin");
        s2g.put("VPN",  "vpncoin");
        s2g.put("CDN",  "canadaecoin");
        s2g.put("PKB",  "parkbyte");
        s2g.put("CLAM", "clams");
        s2g.put("JBS",  "jumbucks");
        SYMBOL_TO_GECKO_ID = Collections.unmodifiableMap(s2g);

        Map<String, String> g2s = new HashMap<>();
        for (Map.Entry<String, String> e : s2g.entrySet()) g2s.put(e.getValue(), e.getKey());
        GECKO_ID_TO_SYMBOL = Collections.unmodifiableMap(g2s);
    }

    private static final Logger log = LoggerFactory.getLogger(ExchangeRatesProvider.class);

    @Override
    public boolean onCreate() {
        final Context context = getContext();

        connManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        config = new Configuration(PreferenceManager.getDefaultSharedPreferences(context));

        lastLocalCurrency = config.getCachedExchangeLocalCurrency();
        if (lastLocalCurrency != null) {
            localToCryptoRates = parseExchangeRates(
                    config.getCachedExchangeRatesJson(), lastLocalCurrency, true);
            localToCryptoLastUpdated = 0;
        }

        return true;
    }

    private static Uri.Builder contentUri(@Nonnull final String packageName, final boolean offline) {
        final Uri.Builder builder =
                Uri.parse("content://" + packageName + ".exchange_rates").buildUpon();
        if (offline)
            builder.appendQueryParameter(QUERY_PARAM_OFFLINE, "1");
        return builder;
    }

    public static Uri contentUriToLocal(@Nonnull final String packageName,
                                  @Nonnull final String coinSymbol,
                                  final boolean offline) {
        final Uri.Builder uri = contentUri(packageName, offline);
        uri.appendPath("to-local").appendPath(coinSymbol);
        return uri.build();
    }

    public static Uri contentUriToCrypto(@Nonnull final String packageName,
                                  @Nonnull final String localSymbol,
                                  final boolean offline) {
        final Uri.Builder uri = contentUri(packageName, offline);
        uri.appendPath("to-crypto").appendPath(localSymbol);
        return uri.build();
    }

    @Nullable
    public static ExchangeRate getRate(final Context context,
                                       @Nonnull final String coinSymbol,
                                       @Nonnull String localSymbol) {
        ExchangeRate rate = null;

        if (context != null) {
            final Uri uri = contentUriToCrypto(context.getPackageName(), localSymbol, true);
            final Cursor cursor = context.getContentResolver().query(uri, null,
                    ExchangeRatesProvider.KEY_CURRENCY_ID, new String[]{coinSymbol}, null);

            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    rate = getExchangeRate(cursor);
                }
                cursor.close();
            }
        }

        return rate;
    }

    public static Map<String, ExchangeRate> getRates(final Context context,
                                              @Nonnull String localSymbol) {
        ImmutableMap.Builder<String, ExchangeRate> builder = ImmutableMap.builder();

        if (context != null) {
            final Uri uri = contentUriToCrypto(context.getPackageName(), localSymbol, true);
            final Cursor cursor = context.getContentResolver().query(uri, null, null,
                    new String[]{null}, null);

            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    ExchangeRate rate = getExchangeRate(cursor);
                    builder.put(rate.currencyCodeId, rate);
                } while (cursor.moveToNext());
                cursor.close();
            }
        }

        return builder.build();
    }

    @Override
    public Cursor query(final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        final long now = System.currentTimeMillis();

        final List<String> pathSegments = uri.getPathSegments();
        if (pathSegments.size() != 2) {
            throw new IllegalArgumentException("Unrecognized URI: " + uri);
        }

        final boolean offline = uri.getQueryParameter(QUERY_PARAM_OFFLINE) != null;
        long lastUpdated;

        final String symbol;
        final boolean isLocalToCrypto;

        if (pathSegments.get(0).equals("to-crypto")) {
            isLocalToCrypto = true;
            symbol = pathSegments.get(1);
            lastUpdated = symbol.equals(lastLocalCurrency) ? localToCryptoLastUpdated : 0;
        } else if (pathSegments.get(0).equals("to-local")) {
            isLocalToCrypto = false;
            symbol = pathSegments.get(1);
            lastUpdated = symbol.equals(lastCryptoCurrency) ? cryptoToLocalLastUpdated : 0;
        } else {
            throw new IllegalArgumentException("Unrecognized URI path: " + uri);
        }

        if (!offline && (lastUpdated == 0 || now - lastUpdated > Constants.RATE_UPDATE_FREQ_MS)) {
            JSONObject newExchangeRatesJson = fetchCoinGeckoRates(symbol, isLocalToCrypto);
            Map<String, ExchangeRate> newExchangeRates =
                    parseExchangeRates(newExchangeRatesJson, symbol, isLocalToCrypto);

            if (newExchangeRates != null) {
                if (isLocalToCrypto) {
                    localToCryptoRates = newExchangeRates;
                    localToCryptoLastUpdated = now;
                    lastLocalCurrency = symbol;
                    config.setCachedExchangeRates(lastLocalCurrency, newExchangeRatesJson);
                } else {
                    cryptoToLocalRates = newExchangeRates;
                    cryptoToLocalLastUpdated = now;
                    lastCryptoCurrency = symbol;
                }
            }
        }

        Map<String, ExchangeRate> exchangeRates = isLocalToCrypto ? localToCryptoRates : cryptoToLocalRates;

        if (exchangeRates == null)
            return null;

        final MatrixCursor cursor = new MatrixCursor(new String[]{BaseColumns._ID,
                KEY_CURRENCY_ID, KEY_RATE_COIN, KEY_RATE_COIN_CODE, KEY_RATE_FIAT, KEY_RATE_FIAT_CODE, KEY_SOURCE});

        if (selection == null) {
            for (final Map.Entry<String, ExchangeRate> entry : exchangeRates.entrySet()) {
                final ExchangeRate exchangeRate = entry.getValue();
                addRow(cursor, exchangeRate);
            }
        } else if (selection.equals(KEY_CURRENCY_ID)) {
            final ExchangeRate exchangeRate = exchangeRates.get(selectionArgs[0]);
            if (exchangeRate != null) {
                addRow(cursor, exchangeRate);
            }
        }

        return cursor;
    }

    private void addRow(MatrixCursor cursor, ExchangeRate exchangeRate) {
        final ExchangeRateBase rate = exchangeRate.rate;
        final String codeId = exchangeRate.currencyCodeId;
        cursor.newRow().add(codeId.hashCode()).add(codeId)
                .add(rate.value1.value).add(rate.value1.type.getSymbol())
                .add(rate.value2.value).add(rate.value2.type.getSymbol())
                .add(exchangeRate.source);
    }

    public static String getCurrencyCodeId(@Nonnull final Cursor cursor) {
        return cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_CURRENCY_ID));
    }

    public static ExchangeRate getExchangeRate(@Nonnull final Cursor cursor) {
        final String codeId = getCurrencyCodeId(cursor);
        final CoinType type = CoinID.typeFromSymbol(cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN_CODE)));
        final Value rateCoin = Value.valueOf(type, cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_COIN)));
        final String fiatCode = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT_CODE));
        final Value rateFiat = FiatValue.valueOf(fiatCode, cursor.getLong(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_RATE_FIAT)));
        final String source = cursor.getString(cursor.getColumnIndexOrThrow(ExchangeRatesProvider.KEY_SOURCE));

        ExchangeRateBase rate = new ExchangeRateBase(rateCoin, rateFiat);
        return new ExchangeRate(rate, codeId, source);
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(final Uri uri) {
        throw new UnsupportedOperationException();
    }

    /**
     * Calls the CoinGecko /simple/price endpoint and transforms the response into the format
     * expected by {@link #parseExchangeRates}:
     *   to-crypto mode (symbol = fiat, e.g. "USD") → {"BTC": "45000.12", "LTC": "70.34", …}
     *   to-local  mode (symbol = crypto, e.g. "BTC") → {"USD": "45000.12", "EUR": "38000.00", …}
     */
    @Nullable
    private JSONObject fetchCoinGeckoRates(String symbol, boolean isLocalToCrypto) {
        final NetworkInfo activeInfo = connManager.getActiveNetworkInfo();
        if (activeInfo == null || !activeInfo.isConnected()) return null;

        final long start = System.currentTimeMillis();
        try {
            String urlStr;
            if (isLocalToCrypto) {
                String coinIds = TextUtils.join(",", SYMBOL_TO_GECKO_ID.values());
                urlStr = COINGECKO_API_URL + "?ids=" + coinIds
                        + "&vs_currencies=" + symbol.toLowerCase();
            } else {
                String coinId = SYMBOL_TO_GECKO_ID.get(symbol.toUpperCase());
                if (coinId == null) return null;
                urlStr = COINGECKO_API_URL + "?ids=" + coinId
                        + "&vs_currencies=" + FIAT_CURRENCIES;
            }

            OkHttpClient client = NetworkUtils.getHttpClient(getContext().getApplicationContext());
            Request request = new Request.Builder()
                    .url(new URL(urlStr))
                    .header("Accept", "application/json")
                    .build();

            Response response = client.newCall(request).execute();
            if (!response.isSuccessful()) {
                log.warn("CoinGecko returned HTTP {}", response.code());
                return null;
            }

            JSONObject raw = new JSONObject(response.body().string());
            log.info("Fetched CoinGecko rates in {} ms", System.currentTimeMillis() - start);

            // Transform {"bitcoin": {"usd": 45000.12}} → {"BTC": "45000.12"}
            JSONObject transformed = new JSONObject();
            if (isLocalToCrypto) {
                String fiatKey = symbol.toLowerCase();
                for (Iterator<String> it = raw.keys(); it.hasNext(); ) {
                    String geckoId = it.next();
                    String cryptoSymbol = GECKO_ID_TO_SYMBOL.get(geckoId);
                    if (cryptoSymbol == null) continue;
                    JSONObject prices = raw.optJSONObject(geckoId);
                    if (prices == null) continue;
                    double price = prices.optDouble(fiatKey, -1);
                    if (price > 0) transformed.put(cryptoSymbol, String.valueOf(price));
                }
            } else {
                String coinId = SYMBOL_TO_GECKO_ID.get(symbol.toUpperCase());
                JSONObject prices = coinId != null ? raw.optJSONObject(coinId) : null;
                if (prices != null) {
                    for (Iterator<String> it = prices.keys(); it.hasNext(); ) {
                        String fiatCode = it.next();
                        double price = prices.optDouble(fiatCode, -1);
                        if (price > 0) transformed.put(fiatCode.toUpperCase(), String.valueOf(price));
                    }
                }
            }
            return transformed.length() > 0 ? transformed : null;

        } catch (Exception e) {
            log.warn("Error fetching CoinGecko rates: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, ExchangeRate> parseExchangeRates(JSONObject json, String fromSymbol, boolean isLocalToCrypto) {
        if (json == null) return null;

        final Map<String, ExchangeRate> rates = new TreeMap<String, ExchangeRate>();
        try {
            CoinType type = isLocalToCrypto ? null : CoinID.typeFromSymbol(fromSymbol);
            for (final Iterator<String> i = json.keys(); i.hasNext(); ) {
                final String toSymbol = i.next();
                // Skip extras field
                if (!"extras".equals(toSymbol)) {
                    final String rateStr = json.optString(toSymbol, null);
                    if (rateStr != null) {
                        try {
                            if (isLocalToCrypto) type = CoinID.typeFromSymbol(toSymbol);
                            String localSymbol = isLocalToCrypto ? fromSymbol : toSymbol;
                            final Value rateCoin = type.oneCoin();
                            final Value rateLocal = FiatValue.parse(localSymbol, rateStr);

                            ExchangeRateBase rate = new ExchangeRateBase(rateCoin, rateLocal);
                            rates.put(toSymbol, new ExchangeRate(rate, toSymbol, OPENWALLET_SOURCE));
                        } catch (final Exception x) {
                            log.debug("ignoring {}/{}: {}", toSymbol, fromSymbol, x.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("problem parsing exchange rates: {}", e.getMessage());
        }

        if (rates.size() == 0) {
            return null;
        } else {
            return rates;
        }
    }
}
