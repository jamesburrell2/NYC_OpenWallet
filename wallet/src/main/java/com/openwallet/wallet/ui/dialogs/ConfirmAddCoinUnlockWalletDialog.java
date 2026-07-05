package com.openwallet.wallet.ui.dialogs;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import androidx.fragment.app.DialogFragment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.openwallet.core.coins.CoinType;
import com.openwallet.wallet.R;
import com.openwallet.wallet.ui.DialogBuilder;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDUtils;

import java.util.List;

import butterknife.ButterKnife;

/**
 * @author John L. Jegutanis
 */
public class ConfirmAddCoinUnlockWalletDialog extends DialogFragment {
    private static final String ADD_COIN = "add_coin";
    private static final String ASK_PASSWORD = "ask_password";
    private Listener listener;

    public static DialogFragment getInstance(CoinType type, boolean askPassword) {
        DialogFragment dialog = new ConfirmAddCoinUnlockWalletDialog();
        dialog.setArguments(new Bundle());
        dialog.getArguments().putSerializable(ADD_COIN, type);
        dialog.getArguments().putBoolean(ASK_PASSWORD, askPassword);
        return dialog;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            listener = (Listener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.getClass() + " must implement " + Listener.class);
        }
    }

    @Override
    public void onDetach() {
        listener = null;
        super.onDetach();
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final CoinType type = (CoinType) getArguments().getSerializable(ADD_COIN);
        final boolean askPassword = getArguments().getBoolean(ASK_PASSWORD);
        final LayoutInflater inflater = LayoutInflater.from(getActivity());
        final View view = inflater.inflate(R.layout.add_account_dialog, null);
        final TextView passwordMessage = ButterKnife.findById(view, R.id.password_message);
        final EditText password = ButterKnife.findById(view, R.id.password);
        com.openwallet.wallet.util.PasswordVisibilityToggle.attach(password);
        final EditText description = ButterKnife.findById(view, R.id.edit_account_description);
        final TextView advancedToggle = ButterKnife.findById(view, R.id.advanced_settings_toggle);
        final View advancedPanel = ButterKnife.findById(view, R.id.advanced_settings_panel);
        final EditText customPathInput = ButterKnife.findById(view, R.id.custom_derivation_path);
        final TextView pathError = ButterKnife.findById(view, R.id.derivation_path_error);

        if (!askPassword) {
            passwordMessage.setVisibility(View.GONE);
            password.setVisibility(View.GONE);
        }

        final android.widget.RadioGroup addrTypeGroup = ButterKnife.findById(view, R.id.address_type_group);
        final android.widget.RadioButton addrLegacy = ButterKnife.findById(view, R.id.addr_type_legacy);
        final android.widget.RadioButton addrCompatible = ButterKnife.findById(view, R.id.addr_type_compatible);
        final android.widget.RadioButton addrNative = ButterKnife.findById(view, R.id.addr_type_native);
        final android.widget.RadioButton addrBundled = ButterKnife.findById(view, R.id.addr_type_bundled);
        final TextView segwitNote = ButterKnife.findById(view, R.id.segwit_not_activated_note);

        // Helper: compute the path hint for a given BIP purpose
        final int bip44Idx = (type != null) ? type.getBip44Index() : 0;
        final boolean segwitActivated = (type != null) && type.isSegwitActivated();
        final java.util.Set<com.openwallet.core.coins.AddressType> addrTypes =
                (type != null) ? type.getSupportedAddressTypes() : java.util.Collections.emptySet();

        // Hide address type rows the coin doesn't support
        if (addrCompatible != null) {
            boolean supportsCompatible = addrTypes.contains(com.openwallet.core.coins.AddressType.COMPATIBLE);
            addrCompatible.setVisibility(supportsCompatible ? View.VISIBLE : View.GONE);
            if (supportsCompatible && !segwitActivated) {
                addrCompatible.setEnabled(false);
                addrCompatible.setAlpha(0.4f);
            }
        }
        if (addrNative != null) {
            boolean supportsNative = addrTypes.contains(com.openwallet.core.coins.AddressType.NATIVE_SEGWIT);
            addrNative.setVisibility(supportsNative ? View.VISIBLE : View.GONE);
            if (supportsNative && !segwitActivated) {
                addrNative.setEnabled(false);
                addrNative.setAlpha(0.4f);
            }
        }
        // "All types (bundled / Coinomi-style)" only makes sense for coins that support both
        // P2SH-segwit and native-segwit in addition to legacy.
        if (addrBundled != null) {
            boolean supportsBundled =
                    addrTypes.contains(com.openwallet.core.coins.AddressType.COMPATIBLE)
                    && addrTypes.contains(com.openwallet.core.coins.AddressType.NATIVE_SEGWIT);
            addrBundled.setVisibility(supportsBundled ? View.VISIBLE : View.GONE);
            if (supportsBundled && !segwitActivated) {
                addrBundled.setEnabled(false);
                addrBundled.setAlpha(0.4f);
            }
        }

        // Show orange warning note if coin has SegWit types but activation is pending
        if (segwitNote != null) {
            boolean hasSegwitTypes = addrTypes.contains(com.openwallet.core.coins.AddressType.COMPATIBLE)
                    || addrTypes.contains(com.openwallet.core.coins.AddressType.NATIVE_SEGWIT);
            segwitNote.setVisibility((hasSegwitTypes && !segwitActivated) ? View.VISIBLE : View.GONE);
        }

        // Set initial path hint based on current (legacy) selection
        if (customPathInput != null) {
            customPathInput.setHint("m/44'/" + bip44Idx + "'/0'");
        }

        // Pre-select the best available address type for this coin
        if (addrTypeGroup != null && segwitActivated) {
            if (addrNative != null && addrNative.getVisibility() == View.VISIBLE) {
                addrNative.setChecked(true);
                if (customPathInput != null) customPathInput.setHint("m/84'/" + bip44Idx + "'/0'");
            } else if (addrCompatible != null && addrCompatible.getVisibility() == View.VISIBLE) {
                addrCompatible.setChecked(true);
                if (customPathInput != null) customPathInput.setHint("m/49'/" + bip44Idx + "'/0'");
            }
        }

        // Radio selection → update path hint to match the selected BIP purpose
        if (addrTypeGroup != null && customPathInput != null) {
            addrTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
                // Only update if user hasn't typed a custom path
                if (!customPathInput.getText().toString().trim().isEmpty()) return;
                if (checkedId == R.id.addr_type_bundled) {
                    // For a bundled account only the account index is used; the three purpose
                    // paths (44'/49'/84') are derived automatically.
                    customPathInput.setHint("m/{44',49',84'}/" + bip44Idx + "'/0'");
                    return;
                }
                int purpose = 44;
                if (checkedId == R.id.addr_type_compatible) {
                    purpose = 49;
                } else if (checkedId == R.id.addr_type_native) {
                    purpose = 84;
                }
                customPathInput.setHint("m/" + purpose + "'/" + bip44Idx + "'/0'");
            });
        }

        // Toggle advanced panel
        if (advancedToggle != null && advancedPanel != null) {
            advancedToggle.setOnClickListener(v -> {
                boolean expanded = advancedPanel.getVisibility() == View.VISIBLE;
                advancedPanel.setVisibility(expanded ? View.GONE : View.VISIBLE);
                advancedToggle.setText(expanded
                        ? R.string.add_coin_advanced_settings_show
                        : R.string.add_coin_advanced_settings_hide);
            });
        }

        // Validate path as user types
        if (customPathInput != null && pathError != null) {
            customPathInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    String pathStr = s.toString().trim();
                    if (!pathStr.isEmpty() && parseUserPath(pathStr) == null) {
                        pathError.setVisibility(View.VISIBLE);
                    } else {
                        pathError.setVisibility(View.GONE);
                    }
                }
            });
        }

        return new DialogBuilder(getActivity())
                .setTitle(getString(R.string.adding_coin_confirmation_title, type.getName()))
                .setView(view)
                .setNegativeButton(R.string.button_cancel, null)
                .setPositiveButton(R.string.button_add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (listener != null) {
                            String customPath = (customPathInput != null)
                                    ? customPathInput.getText().toString().trim() : "";
                            // For a bundled account, signal it with a "bundled:N" sentinel where
                            // N is the account index parsed from the advanced field (default 0).
                            if (addrBundled != null && addrBundled.isChecked()) {
                                customPath = "bundled:" + parseAccountIndex(customPath);
                            }
                            listener.addCoin(type, description.getText().toString(),
                                    password.getText(), customPath);
                        }
                    }
                }).create();
    }

    /** Parse a user-entered path like "m/44'/0'/0'" into a ChildNumber list, or null if invalid. */
    private List<ChildNumber> parseUserPath(String pathStr) {
        try {
            String normalized = pathStr.trim()
                    .replaceAll("(?i)^m/", "")
                    .replace("'", "H");
            if (normalized.isEmpty()) return null;
            return HDUtils.parsePath(normalized);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the account index for a bundled account from the advanced field. Accepts a bare
     * number ("7") or a full path whose last hardened component is the index ("m/84'/0'/7'").
     * Defaults to 0 when empty or unparseable.
     */
    private int parseAccountIndex(String input) {
        if (input == null) return 0;
        String s = input.trim();
        if (s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // Not a bare number — try to read the last index of a full path.
        }
        List<ChildNumber> path = parseUserPath(s);
        if (path != null && !path.isEmpty()) {
            return path.get(path.size() - 1).num();
        }
        return 0;
    }

    public interface Listener {
        void addCoin(CoinType type, String description, CharSequence password, String customDerivationPath);
    }
}
