package com.openwallet.wallet.ui.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.openwallet.core.coins.CoinID;
import com.openwallet.core.coins.CoinType;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.Constants;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;
import com.openwallet.wallet.ui.DialogBuilder;

import butterknife.Bind;
import butterknife.ButterKnife;

import static com.openwallet.core.Preconditions.checkState;

/**
 * Dialog for editing the ElectrumX server address for a coin.
 * The user enters "hostname:port".  The "Default" button clears the custom value.
 */
public class EditServerDialog extends DialogFragment {
    @Bind(R.id.server_description) TextView description;
    @Bind(R.id.server_address) EditText serverAddressInput;

    private Configuration configuration;

    public static EditServerDialog newInstance(CoinType type) {
        EditServerDialog dialog = new EditServerDialog();
        Bundle args = new Bundle();
        args.putString(Constants.ARG_COIN_ID, type.getId());
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        WalletApplication application = (WalletApplication) activity.getApplication();
        configuration = application.getConfiguration();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        checkState(getArguments().containsKey(Constants.ARG_COIN_ID), "Must provide coin id");
        View view = View.inflate(getActivity(), R.layout.edit_server_dialog, null);
        ButterKnife.bind(this, view);

        final CoinType type = CoinID.typeFromId(getArguments().getString(Constants.ARG_COIN_ID));

        description.setText(getString(R.string.server_dialog_description, type.getName()));

        String currentServer = configuration.getCoinElectrumServer(type);
        if (currentServer != null && !currentServer.isEmpty()) {
            serverAddressInput.setText(currentServer);
        }

        final DialogBuilder builder = new DialogBuilder(getActivity());
        builder.setTitle(getString(R.string.server_dialog_title, type.getName()));
        builder.setView(view);

        DialogInterface.OnClickListener onClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        String input = serverAddressInput.getText().toString().trim();
                        configuration.setCoinElectrumServer(type, input.isEmpty() ? null : input);
                        break;
                    case DialogInterface.BUTTON_NEUTRAL:
                        configuration.setCoinElectrumServer(type, null);
                        break;
                }
            }
        };
        builder.setNegativeButton(R.string.button_cancel, onClickListener);
        builder.setNeutralButton(R.string.button_default, onClickListener);
        builder.setPositiveButton(R.string.button_ok, onClickListener);

        return builder.create();
    }
}
