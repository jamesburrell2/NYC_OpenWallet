package com.openwallet.wallet.ui;

import androidx.fragment.app.Fragment;

import com.openwallet.core.wallet.WalletAccount;

/**
 * @author John L. Jegutanis
 */
public abstract class WalletFragment extends Fragment implements ViewUpdateble {
    abstract public WalletAccount getAccount();
}
