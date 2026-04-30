package com.openwallet.wallet.ui;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AppCompatActivity;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.WalletApplication;

import java.util.List;

import javax.annotation.Nullable;

/**
 * @author John L. Jegutanis
 */
abstract public class BaseWalletActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    public WalletApplication getWalletApplication() {
        return (WalletApplication) getApplication();
    }

    @Nullable
    public WalletAccount getAccount(String accountId) {
        return getWalletApplication().getAccount(accountId);
    }

    public List<WalletAccount> getAllAccounts() {
        return getWalletApplication().getAllAccounts();
    }

    public List<WalletAccount> getAccounts(CoinType type) {
        return getWalletApplication().getAccounts(type);
    }

    public List<WalletAccount> getAccounts(List<CoinType> types) {
        return getWalletApplication().getAccounts(types);
    }

    public boolean isAccountExists(String accountId) {
        return getWalletApplication().isAccountExists(accountId);
    }

    public Configuration getConfiguration() {
        return getWalletApplication().getConfiguration();
    }

    public FragmentManager getFM() {
        return getSupportFragmentManager();
    }

    public void replaceFragment(Fragment fragment, int container) {
        replaceFragment(fragment, container, null);
    }

    public void replaceFragment(Fragment fragment, int container, @Nullable String tag) {
        FragmentTransaction transaction = getFM().beginTransaction();

        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        // Replace whatever is in the fragment_container view with this fragment,
        // and add the transaction to the back stack so the user can navigate back
        transaction.replace(container, fragment, tag);
        transaction.addToBackStack(null);

        // Commit the transaction
        transaction.commit();
    }

    @Nullable
    public Wallet getWallet() {
        return getWalletApplication().getWallet();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Show biometric lock if the app was backgrounded longer than LOCK_AFTER_MS,
        // but only if screen lock is enabled in settings.
        WalletApplication app = getWalletApplication();
        if (app.getConfiguration().isScreenLockEnabled()) {
            long lastStop = app.getLastStop();
            if (lastStop > 0
                    && (android.os.SystemClock.elapsedRealtime() - lastStop)
                            > BiometricLockActivity.LOCK_AFTER_MS) {
                android.content.Intent lock = new android.content.Intent(this, BiometricLockActivity.class);
                lock.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(lock);
            }
        }
        app.touchLastResume();
    }

    @Override
    protected void onStop() {
        super.onStop();
        getWalletApplication().touchLastStop();
    }
}
