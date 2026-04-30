package com.openwallet.wallet.tasks;

import android.os.AsyncTask;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;

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

            List<ChildNumber> customPath = parseCustomPath(customDerivationPath);
            if (customPath != null) {
                newAccount = wallet.createAccountAtCustomPath(type, customPath, true, key);
            } else {
                newAccount = wallet.createAccount(type, true, key);
            }

            if (description != null && !description.trim().isEmpty()) {
                newAccount.setDescription(description);
            }
            wallet.saveNow();
        } catch (Exception e) {
            exception = e;
        }

        return null;
    }

    @Override
    final protected void onPostExecute(Void aVoid) {
        listener.onAddCoinTaskFinished(exception, newAccount);
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
