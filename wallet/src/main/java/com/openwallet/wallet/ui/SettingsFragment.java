package com.openwallet.wallet.ui;

import android.os.Bundle;
import androidx.preference.PreferenceFragmentCompat;

import com.openwallet.wallet.R;

/**
 * @author John L. Jegutanis
 */
public class SettingsFragment extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);
    }
}
