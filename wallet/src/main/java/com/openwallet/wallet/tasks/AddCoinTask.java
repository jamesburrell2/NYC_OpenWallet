package com.openwallet.wallet.tasks;

import android.os.AsyncTask;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.families.ZcashSdkFamily;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.wallet.util.ZecSeedCache;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDUtils;
import org.spongycastle.crypto.params.KeyParameter;

import java.util.List;

import javax.annotation.Nullable;

/**
 * @author John L. Jegutanis
 */
public final class AddCoinTask extends AsyncTask<Void, Void, Void> {
    private final Listener listener;
    protected final CoinType type;
    private final Wallet wallet;
    @Nullable private final String description;
    @Nullable private final CharSequence password;
    @Nullable private final String customDerivationPath;
    private WalletAccount newAccount;
    private Exception exception;

    public interface Listener {
        void onAddCoinTaskStarted();
        void onAddCoinTaskFinished(Exception error, WalletAccount newAccount);
    }

    public AddCoinTask(Listener listener, CoinType type, Wallet wallet,
                       @Nullable String description, @Nullable CharSequence password) {
        this(listener, type, wallet, description, password, null);
    }

    public AddCoinTask(Listener listener, CoinType type, Wallet wallet,
                       @Nullable String description, @Nullable CharSequence password,
                       @Nullable String customDerivationPath) {
        this.listener = listener;
        this.type = type;
        this.wallet = wallet;
        this.description = description;
        this.password = password;
        this.customDerivationPath = customDerivationPath;
    }

    @Override
    protected void onPreExecute() {
        listener.onAddCoinTaskStarted();
    }

    @Override
    protected Void doInBackground(Void... params) {
        KeyParameter key = null;
        exception = null;
        try {
            if (wallet.isEncrypted() && wallet.getKeyCrypter() != null) {
                key = wallet.getKeyCrypter().deriveKey(password);
            }

            Integer bundledIndex = parseBundledIndex(customDerivationPath);
            List<ChildNumber> customPath = parseCustomPath(customDerivationPath);
            if (bundledIndex != null) {
                // Coinomi-style bundled account: 44'/49'/84' under one index.
                newAccount = wallet.createBundledBitcoinAccount(type, bundledIndex, key);
                newAccount.maybeInitializeAllKeys();
            } else if (customPath != null) {
                newAccount = wallet.createAccountAtCustomPath(type, customPath, true, key);
            } else {
                newAccount = wallet.createAccount(type, true, key);
            }

            if (description != null && !description.trim().isEmpty()) {
                newAccount.setDescription(description);
            }
            wallet.saveNow();

            // The user just proved their password: cache the decrypted seed in memory
            // so the ZEC SDK backend can start on this encrypted wallet. No-op for
            // unencrypted wallets (the service reads the seed directly).
            if (key != null && type instanceof ZcashSdkFamily) {
                ZecSeedCache.capture(wallet.getSeedBytes(key));
            }
        } catch (Exception e) {
            exception = e;
        }

        return null;
    }

    @Override
    final protected void onPostExecute(Void aVoid) {
        listener.onAddCoinTaskFinished(exception, newAccount);
    }

    /**
     * If the advanced field carries the bundled sentinel "bundled:N", return N (the account
     * index). Otherwise return null.
     */
    @Nullable
    private Integer parseBundledIndex(String value) {
        if (value == null) return null;
        String s = value.trim();
        if (!s.startsWith("bundled:")) return null;
        try {
            return Integer.parseInt(s.substring("bundled:".length()).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private List<ChildNumber> parseCustomPath(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) return null;
        try {
            String normalized = pathStr.trim()
                    .replaceAll("(?i)^m/", "")
                    .replace("'", "H");
            return HDUtils.parsePath(normalized);
        } catch (Exception e) {
            return null;
        }
    }
}
