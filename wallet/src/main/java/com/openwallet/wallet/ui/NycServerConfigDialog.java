package com.openwallet.wallet.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.openwallet.wallet.R;

import java.util.regex.Pattern;

/**
 * First-run dialog to configure the NYC ElectrumX server address.
 *
 * Shown once when the user adds the NYC coin for the first time,
 * before wallet pocket creation. The user can Save (persist host:port)
 * or Skip (wallet created without a server — banner shown in BalanceFragment).
 *
 * NOTE: Use android.support.v7.app.AlertDialog (not android.app.AlertDialog)
 * to inherit the app's Material theme consistently across API levels.
 *
 * NOTE: setPositiveButton() always auto-dismisses. To keep the dialog open on
 * invalid input and show an error, we pass null as the listener and override
 * the button's click listener in onStart() instead.
 *
 * NOTE: onAttach() checks the parent fragment first (for child-fragment usage
 * from BalanceFragment), then falls back to checking the Activity (for usage
 * from AddCoinsActivity). Both callers must implement OnNycServerConfiguredListener.
 */
public class NycServerConfigDialog extends DialogFragment {

    public interface OnNycServerConfiguredListener {
        /** Called with hostPort (e.g. "electrum.paywith.nyc:50002") or null if skipped. */
        void onNycServerConfigured(@Nullable String hostPort);
    }

    private static final Pattern HOST_PORT_PATTERN =
        Pattern.compile("^[^:]+:(\\d{1,5})$");

    private OnNycServerConfiguredListener listener;
    private EditText serverInput; // held across onCreateDialog → onStart

    public static NycServerConfigDialog newInstance() {
        return new NycServerConfigDialog();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // Check parent fragment first — this dialog may be shown as a child fragment
        // of BalanceFragment (via getChildFragmentManager()), in which case the Activity
        // is NOT the listener. Fall back to the Activity for AddCoinsActivity usage.
        Fragment parent = getParentFragment();
        if (parent instanceof OnNycServerConfiguredListener) {
            listener = (OnNycServerConfiguredListener) parent;
        } else if (context instanceof OnNycServerConfiguredListener) {
            listener = (OnNycServerConfiguredListener) context;
        } else {
            throw new IllegalStateException(
                "Parent fragment or activity must implement OnNycServerConfiguredListener");
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View view = inflater.inflate(R.layout.dialog_nyc_server_config, null);
        serverInput = view.findViewById(R.id.nyc_server_input);

        // Pass null for Save listener — real listener is set in onStart() to
        // prevent the dialog auto-dismissing before we can show a validation error.
        return new AlertDialog.Builder(getContext())
            .setTitle("Configure NYC ElectrumX Server")
            .setMessage("Enter the NYC ElectrumX server address. You can add this later from the wallet settings.")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    listener.onNycServerConfigured(null);
                }
            })
            .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Override positive button AFTER dialog.show() so the button widget exists.
        // This is the only safe pattern that keeps the dialog open on invalid input.
        Button saveBtn = ((AlertDialog) getDialog())
                .getButton(DialogInterface.BUTTON_POSITIVE);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = serverInput.getText().toString().trim();
                if (isValidHostPort(input)) {
                    listener.onNycServerConfigured(input);
                    dismiss();
                } else {
                    serverInput.setError(
                        "Enter a valid host:port (e.g. electrum.paywith.nyc:50002)");
                }
            }
        });
    }

    private boolean isValidHostPort(String input) {
        if (!HOST_PORT_PATTERN.matcher(input).matches()) return false;
        int port = Integer.parseInt(input.substring(input.lastIndexOf(':') + 1));
        return port >= 1 && port <= 65535;
    }
}
