package com.openwallet.wallet.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import com.openwallet.core.coins.CoinType;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;
import com.openwallet.wallet.ui.adaptors.ServerListAdapter;
import com.openwallet.wallet.ui.dialogs.EditServerDialog;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnItemClick;

/**
 * Fragment that lists all supported coins with their current ElectrumX server.
 * Tapping a coin opens an edit dialog where the user can enter a custom server or restore default.
 */
public class ServerSettingsFragment extends Fragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String EDIT_SERVER_DIALOG = "edit_server_dialog";

    @Bind(R.id.coins_list) ListView coinList;

    private Configuration config;
    private Context context;
    private ServerListAdapter adapter;

    public ServerSettingsFragment() {}

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        this.context = context;
        WalletApplication application = (WalletApplication) context.getApplicationContext();
        config = application.getConfiguration();
        config.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        adapter = new ServerListAdapter(context, config);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_fees_settings_list, container, false);
        ButterKnife.bind(this, view);
        coinList.setAdapter(adapter);
        return view;
    }

    @OnItemClick(R.id.coins_list)
    void editServer(int position) {
        CoinType type = (CoinType) coinList.getItemAtPosition(position);
        DialogFragment editServerDialog = EditServerDialog.newInstance(type);
        editServerDialog.show(getFragmentManager(), EDIT_SERVER_DIALOG);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        ButterKnife.unbind(this);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        config.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null && key.startsWith("coin_server_")) {
            adapter.update();
        }
    }
}
