package com.openwallet.wallet.ui;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Message;
import androidx.fragment.app.DialogFragment;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.Loader;
import androidx.appcompat.view.ActionMode;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.FiatType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.coins.families.NxtFamily;
import com.openwallet.core.uri.CoinURI;
import com.openwallet.core.util.ExchangeRate;
import com.openwallet.core.util.GenericUtils;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.Wallet;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.core.wallet.WalletPocketHD;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDUtils;

import java.util.ArrayList;
import java.util.List;

import com.openwallet.wallet.AddressBookProvider;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.Constants;
import com.openwallet.wallet.ExchangeRatesProvider;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;
import com.openwallet.wallet.ui.dialogs.CreateNewAddressDialog;
import com.openwallet.wallet.ui.widget.AmountEditView;
import com.openwallet.wallet.util.QrUtils;
import com.openwallet.wallet.util.ThrottlingWalletChangeListener;
import com.openwallet.wallet.util.UiUtils;
import com.openwallet.wallet.util.WeakHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

import static com.openwallet.wallet.ExchangeRatesProvider.getRate;

/**
 *
 */
public class AddressRequestFragment extends WalletFragment {
    private static final Logger log = LoggerFactory.getLogger(AddressRequestFragment.class);

    private static final int UPDATE_VIEW = 0;
    private static final int UPDATE_EXCHANGE_RATE = 1;

    // Loader IDs
    private static final int ID_RATE_LOADER = 0;

    // Fragment tags
    private static final String NEW_ADDRESS_TAG = "new_address_tag";

    private CoinType type;
    @Nullable private AbstractAddress showAddress;
    private AbstractAddress receiveAddress;
    private Value amount;
    private String label;
    private String accountId;
    private WalletAccount account;
    private String message;

    // Zcash receive-address type selection (session-scoped, defaults to Unified)
    private static final String ZEC_TYPE_UNIFIED = "unified";
    private static final String ZEC_TYPE_TRANSPARENT = "transparent";
    private static final String ZEC_TYPE_SHIELDED = "shielded";
    private String selectedZecAddressType = ZEC_TYPE_UNIFIED;
    private RadioButton[] zecTypeButtons;

    @Bind(R.id.request_address_label) TextView addressLabelView;
    @Bind(R.id.request_address) TextView addressView;
    @Bind(R.id.request_coin_amount) AmountEditView sendCoinAmountView;
    @Bind(R.id.view_previous_addresses) View previousAddressesLink;
    @Bind(R.id.qr_code) ImageView qrView;
    @Bind(R.id.address_type_radio_group) RadioGroup addressTypeRadioGroup;
    @Bind(R.id.derivation_path_view)     TextView derivationPathView;
    @Bind(R.id.custom_path_toggle)       TextView customPathToggle;
    @Bind(R.id.custom_path_row)          View customPathRow;
    @Bind(R.id.custom_path_input)        EditText customPathInput;
    private AddressType selectedAddressType = AddressType.NATIVE_SEGWIT;
    private WalletApplication walletApplication;
    String lastQrContent;
    CurrencyCalculatorLink amountCalculatorLink;
    ContentResolver resolver;

    private final MyHandler handler = new MyHandler(this);
    private final ContentObserver addressBookObserver = new AddressBookObserver(handler);
    private Configuration config;

    private static class MyHandler extends WeakHandler<AddressRequestFragment> {
        public MyHandler(AddressRequestFragment ref) { super(ref); }

        @Override
        protected void weakHandleMessage(AddressRequestFragment ref, Message msg) {
            switch (msg.what) {
                case UPDATE_VIEW:
                    ref.updateView();
                    break;
                case UPDATE_EXCHANGE_RATE:
                    ref.updateExchangeRate((ExchangeRate) msg.obj);
                    break;
            }
        }
    }

    static class AddressBookObserver extends ContentObserver {
        private final MyHandler handler;

        public AddressBookObserver(MyHandler handler) {
            super(handler);
            this.handler = handler;
        }

        @Override
        public void onChange(final boolean selfChange) {
            handler.sendEmptyMessage(UPDATE_VIEW);
        }
    }

    public static AddressRequestFragment newInstance(Bundle args) {
        AddressRequestFragment fragment = new AddressRequestFragment();
        fragment.setArguments(args);
        return fragment;
    }

    public static AddressRequestFragment newInstance(String accountId) {
        return newInstance(accountId, null);
    }

    public static AddressRequestFragment newInstance(String accountId,
                                                     @Nullable AbstractAddress showAddress) {
        Bundle args = new Bundle();
        args.putString(Constants.ARG_ACCOUNT_ID, accountId);
        if (showAddress != null) {
            args.putSerializable(Constants.ARG_ADDRESS, showAddress);
        }
        return newInstance(args);
    }
    public AddressRequestFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The onCreateOptionsMenu is handled in com.openwallet.wallet.ui.AccountFragment
        // or in com.openwallet.wallet.ui.PreviousAddressesActivity
        setHasOptionsMenu(true);

        WalletApplication walletApplication = (WalletApplication) getActivity().getApplication();
        Bundle args = getArguments();
        if (args != null) {
            accountId = args.getString(Constants.ARG_ACCOUNT_ID);
            if (args.containsKey(Constants.ARG_ADDRESS)) {
                showAddress = (AbstractAddress) args.getSerializable(Constants.ARG_ADDRESS);
            }
        }
        account = walletApplication.getAccount(accountId);
        if (account == null) {
            Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
            return;
        }
        type = account.getCoinType();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_request, container, false);
        ButterKnife.bind(this, view);

        if (type == null) return view;
        sendCoinAmountView.resetType(type, true);

        // Configure address type tab strip for SegWit-capable coins
        if (type.getSupportedAddressTypes().size() > 1) {
            addressTypeRadioGroup.setVisibility(View.VISIBLE);

            // Fixed display order: Default (NATIVE_SEGWIT), Compatibility (COMPATIBLE), Legacy (LEGACY)
            final AddressType[] displayOrder = {
                    AddressType.NATIVE_SEGWIT, AddressType.COMPATIBLE, AddressType.LEGACY };
            final String[] tabLabels = {
                    getString(R.string.address_type_default),
                    getString(R.string.address_type_compatible),
                    getString(R.string.address_type_legacy) };

            for (int i = 0; i < displayOrder.length; i++) {
                if (!type.getSupportedAddressTypes().contains(displayOrder[i])) continue;
                RadioButton btn = new RadioButton(getActivity());
                btn.setId(View.generateViewId());
                btn.setText(tabLabels[i]);
                btn.setTag(displayOrder[i]);
                btn.setLayoutParams(new RadioGroup.LayoutParams(
                        RadioGroup.LayoutParams.WRAP_CONTENT,
                        RadioGroup.LayoutParams.WRAP_CONTENT));
                btn.setButtonDrawable(android.R.color.transparent);
                btn.setBackgroundResource(R.drawable.address_tab_selector);
                btn.setTextColor(getResources().getColorStateList(R.color.address_tab_text_selector));
                btn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
                btn.setTextSize(13f);
                if (displayOrder[i] == selectedAddressType) btn.setChecked(true);
                addressTypeRadioGroup.addView(btn);
            }

            addressTypeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                View checkedBtn = group.findViewById(checkedId);
                if (checkedBtn != null) {
                    selectedAddressType = (AddressType) checkedBtn.getTag();
                    updateView();
                }
            });

            // Custom path toggle
            customPathToggle.setVisibility(View.VISIBLE);
            customPathToggle.setOnClickListener(v -> {
                boolean expanded = customPathRow.getVisibility() == View.VISIBLE;
                customPathRow.setVisibility(expanded ? View.GONE : View.VISIBLE);
                customPathToggle.setText(expanded
                        ? getString(R.string.address_type_custom_path_expand)
                        : getString(R.string.address_type_custom_path_collapse));
                if (expanded) {
                    customPathInput.setText("");
                    updateView();
                }
            });
            customPathInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { updateView(); }
            });
        } else if (account instanceof com.openwallet.core.wallet.families.zcash.ZcashSdkWallet) {
            // Zcash: Unified / Transparent / Shielded selector. Reuses the SegWit tab
            // strip styling; buttons for address types the SDK hasn't derived yet are
            // disabled and re-enabled by updateView() once sync starts.
            addressTypeRadioGroup.setVisibility(View.VISIBLE);
            final String[] zecTags = { ZEC_TYPE_UNIFIED, ZEC_TYPE_TRANSPARENT, ZEC_TYPE_SHIELDED };
            final String[] zecLabels = {
                    getString(R.string.address_type_zec_unified),
                    getString(R.string.address_type_zec_transparent),
                    getString(R.string.address_type_zec_shielded) };
            zecTypeButtons = new RadioButton[zecTags.length];
            for (int i = 0; i < zecTags.length; i++) {
                RadioButton btn = new RadioButton(getActivity());
                btn.setId(View.generateViewId());
                btn.setText(zecLabels[i]);
                btn.setTag(zecTags[i]);
                btn.setLayoutParams(new RadioGroup.LayoutParams(
                        RadioGroup.LayoutParams.WRAP_CONTENT,
                        RadioGroup.LayoutParams.WRAP_CONTENT));
                btn.setButtonDrawable(android.R.color.transparent);
                btn.setBackgroundResource(R.drawable.address_tab_selector);
                btn.setTextColor(getResources().getColorStateList(R.color.address_tab_text_selector));
                btn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
                btn.setTextSize(13f);
                if (zecTags[i].equals(selectedZecAddressType)) btn.setChecked(true);
                zecTypeButtons[i] = btn;
                addressTypeRadioGroup.addView(btn);
            }
            addressTypeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
                View checkedBtn = group.findViewById(checkedId);
                if (checkedBtn != null && checkedBtn.getTag() instanceof String) {
                    selectedZecAddressType = (String) checkedBtn.getTag();
                    updateView();
                }
            });
        }

        AmountEditView sendLocalAmountView = ButterKnife.findById(view, R.id.request_local_amount);
        sendLocalAmountView.setFormat(FiatType.FRIENDLY_FORMAT);

        amountCalculatorLink = new CurrencyCalculatorLink(sendCoinAmountView, sendLocalAmountView);

        return view;
    }

    @Override
    public void onViewStateRestored(@androidx.annotation.Nullable Bundle savedInstanceState) {
        if (type == null) { super.onViewStateRestored(savedInstanceState); return; }
        ExchangeRatesProvider.ExchangeRate rate = getRate(getContext(), type.getSymbol(), config.getExchangeCurrencyCode());
        if (rate != null) updateExchangeRate(rate.rate);
        updateView();
        super.onViewStateRestored(savedInstanceState);
    }

    @Override
    public void onDestroyView() {
        amountCalculatorLink = null;
        lastQrContent = null;
        ButterKnife.unbind(this);
        super.onDestroyView();
    }

    @OnClick(R.id.request_address_view)
    public void onAddressClick() {
        if (showAddress != null) {
            receiveAddress =  showAddress;
        }
        Activity activity = getActivity();
        ActionMode actionMode = UiUtils.startAddressActionMode(receiveAddress, activity,
                getFragmentManager());
        // Hack to dismiss this action mode when back is pressed
        if (activity != null && activity instanceof WalletActivity) {
            ((WalletActivity) activity).registerActionMode(actionMode);
        }
    }

    @OnClick(R.id.view_previous_addresses)
    public void onPreviousAddressesClick() {
        Intent intent = new Intent(getActivity(), PreviousAddressesActivity.class);
        intent.putExtra(Constants.ARG_ACCOUNT_ID, accountId);
        startActivity(intent);
    }

    @Override
    public void onResume() {
        super.onResume();

        if (account == null || type == null) return;

        account.addEventListener(walletListener);
        amountCalculatorLink.setListener(amountsListener);
        resolver.registerContentObserver(AddressBookProvider.contentUri(
                getActivity().getPackageName(), type), true, addressBookObserver);

        updateView();
    }

    @Override
    public void onPause() {
        resolver.unregisterContentObserver(addressBookObserver);
        amountCalculatorLink.setListener(null);
        if (account != null) {
            account.removeEventListener(walletListener);
            walletListener.removeCallbacks();
        }

        super.onPause();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_share:
                UiUtils.share(getActivity(), getUri());
                return true;
            case R.id.action_copy:
                UiUtils.copy(getActivity(), getUri());
                return true;
            case R.id.action_new_address:
                showNewAddressDialog();
                return true;
            case R.id.action_edit_label:
                EditAddressBookEntryFragment.edit(getFragmentManager(), type, receiveAddress);
                return true;
            default:
                // Not one of ours. Perform default menu processing
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onAttach(final Context  context) {
        super.onAttach(context);
        this.resolver = context.getContentResolver();
        walletApplication = (WalletApplication) context.getApplicationContext();
        this.config = walletApplication.getConfiguration();
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (account == null || type == null) return; // account not found; loader would NPE
        getLoaderManager().initLoader(ID_RATE_LOADER, null, rateLoaderCallbacks);
    }

    @Override
    public void onDetach() {
        getLoaderManager().destroyLoader(ID_RATE_LOADER);
        resolver = null;
        super.onDetach();
    }

    private void showNewAddressDialog() {
        if (!isVisible() || !isResumed()) return;
        Dialogs.dismissAllowingStateLoss(getFragmentManager(), NEW_ADDRESS_TAG);
        DialogFragment dialog = CreateNewAddressDialog.getInstance(account);
        dialog.show(getFragmentManager(), NEW_ADDRESS_TAG);
    }

    private void updateExchangeRate(ExchangeRate exchangeRate) {
        amountCalculatorLink.setExchangeRate((ExchangeRate) exchangeRate);
    }

    private static void setZecButtonAvailable(RadioButton btn, boolean available) {
        btn.setEnabled(available);
        btn.setAlpha(available ? 1f : 0.4f);
    }

    @Override
    public void updateView() {
        if (isRemoving() || isDetached()) return;
        receiveAddress = null;
        DeterministicKey displayKey = null;

        if (showAddress != null) {
            receiveAddress = showAddress;
            // Hide tab strip and custom path when showing a historical address
            if (type.getSupportedAddressTypes().size() > 1) {
                addressTypeRadioGroup.setVisibility(View.GONE);
                customPathToggle.setVisibility(View.GONE);
                customPathRow.setVisibility(View.GONE);
                derivationPathView.setVisibility(View.GONE);
            }
        } else if (account instanceof com.openwallet.core.wallet.families.zcash.ZcashSdkWallet) {
            com.openwallet.core.wallet.families.zcash.ZcashSdkWallet zec =
                    (com.openwallet.core.wallet.families.zcash.ZcashSdkWallet) account;
            AbstractAddress unified = zec.getUnifiedAddress();
            AbstractAddress transparent = zec.getTransparentAddress();
            AbstractAddress shielded = zec.getShieldedAddress();

            if (zecTypeButtons != null) {
                setZecButtonAvailable(zecTypeButtons[0], unified != null);
                setZecButtonAvailable(zecTypeButtons[1], transparent != null);
                setZecButtonAvailable(zecTypeButtons[2], shielded != null);
            }

            if (ZEC_TYPE_TRANSPARENT.equals(selectedZecAddressType)) {
                receiveAddress = transparent;
            } else if (ZEC_TYPE_SHIELDED.equals(selectedZecAddressType)) {
                receiveAddress = shielded;
            } else {
                receiveAddress = unified;
            }
            if (receiveAddress == null) {
                // Selected type not derived yet (SDK backend still starting): show the
                // best available address rather than an empty screen.
                receiveAddress = account.getReceiveAddress();
            }
        } else {
            AbstractAddress legacyAddr = account.getReceiveAddress();
            String customPathStr = (customPathInput != null)
                    ? customPathInput.getText().toString().trim() : "";
            boolean useCustomPath = !customPathStr.isEmpty();

            if (useCustomPath) {
                // Custom path: parse, derive, infer address type
                List<ChildNumber> path = parseUserPath(customPathStr);
                if (path != null && walletApplication != null) {
                    Wallet wallet = walletApplication.getWallet();
                    if (wallet != null) {
                        displayKey = wallet.deriveKeyAtFullPath(path);
                        if (displayKey != null) {
                            AddressType addrType = inferAddressType(path);
                            if (!type.getSupportedAddressTypes().contains(addrType)) {
                                addrType = AddressType.LEGACY;
                            }
                            try {
                                receiveAddress = type.addressFromKey(displayKey, addrType);
                            } catch (Exception e) {
                                displayKey = null;
                            }
                        }
                    }
                }
                if (receiveAddress == null) receiveAddress = legacyAddr;

            } else if (selectedAddressType == AddressType.LEGACY
                    || type.getSupportedAddressTypes().size() == 1) {
                receiveAddress = legacyAddr;
                // Capture the DK for path display
                if (type.getSupportedAddressTypes().size() > 1 && account instanceof WalletPocketHD) {
                    try {
                        byte[] hash160 = ((BitAddress) legacyAddr).getHash160();
                        ECKey rawKey = ((WalletPocketHD) account).findKeyFromPubHash(hash160);
                        if (rawKey instanceof DeterministicKey) displayKey = (DeterministicKey) rawKey;
                    } catch (Exception ignored) {}
                }

            } else {
                // NATIVE_SEGWIT (BIP84) or COMPATIBLE (BIP49): derive from proper purpose path
                receiveAddress = legacyAddr; // default fallback
                if (account instanceof WalletPocketHD && walletApplication != null) {
                    WalletPocketHD pocketHD = (WalletPocketHD) account;
                    Wallet wallet = walletApplication.getWallet();
                    if (wallet != null) {
                        int accountIndex = pocketHD.getAccountIndex();
                        List<ChildNumber> accountPath = (selectedAddressType == AddressType.NATIVE_SEGWIT)
                                ? type.getBip84Path(accountIndex)
                                : type.getBip49Path(accountIndex);
                        List<ChildNumber> fullPath = new ArrayList<>(accountPath);
                        fullPath.add(new ChildNumber(0, false)); // external chain
                        fullPath.add(new ChildNumber(0, false)); // address index 0
                        displayKey = wallet.deriveKeyAtFullPath(fullPath);
                        if (displayKey != null) {
                            try {
                                receiveAddress = type.addressFromKey(displayKey, selectedAddressType);
                            } catch (Exception e) {
                                displayKey = null;
                            }
                        }
                    }
                    if (displayKey == null) {
                        // Fallback for encrypted wallets: re-encode the BIP44 key
                        try {
                            byte[] hash160 = ((BitAddress) legacyAddr).getHash160();
                            ECKey key = ((WalletPocketHD) account).findKeyFromPubHash(hash160);
                            if (key != null) receiveAddress = type.addressFromKey(key, selectedAddressType);
                        } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Don't show previous addresses link if we are showing a specific address,
        // or if the account isn't a WalletPocketHD (e.g. Zcash accounts don't support
        // the previous-addresses view)
        if (showAddress == null && account instanceof WalletPocketHD && account.hasUsedAddresses()) {
            previousAddressesLink.setVisibility(View.VISIBLE);
        } else {
            previousAddressesLink.setVisibility(View.GONE);
        }

        updateLabel();
        updateQrCode(getUri());

        // Populate derivation path for multi-type coins
        if (type.getSupportedAddressTypes().size() > 1 && derivationPathView != null && showAddress == null) {
            String customPathStr = (customPathInput != null)
                    ? customPathInput.getText().toString().trim() : "";
            if (!customPathStr.isEmpty()) {
                List<ChildNumber> path = parseUserPath(customPathStr);
                if (path != null) {
                    StringBuilder sb = new StringBuilder("M");
                    for (ChildNumber c : path) sb.append('/').append(c.toString());
                    derivationPathView.setText(sb.toString());
                    derivationPathView.setVisibility(View.VISIBLE);
                } else {
                    derivationPathView.setText("Invalid path");
                    derivationPathView.setVisibility(View.VISIBLE);
                }
            } else if (displayKey != null) {
                StringBuilder path = new StringBuilder("M");
                for (ChildNumber child : displayKey.getPath()) {
                    path.append('/').append(child.toString());
                }
                derivationPathView.setText(path.toString());
                derivationPathView.setVisibility(View.VISIBLE);
            } else {
                derivationPathView.setVisibility(View.GONE);
            }
        }
    }

    /** Parse a user-entered path like "m/84'/0'/0'/0/0" into a ChildNumber list. */
    @Nullable
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

    /** Infer address type from BIP32 purpose index (first path element). */
    private AddressType inferAddressType(List<ChildNumber> path) {
        if (!path.isEmpty()) {
            int purpose = path.get(0).num();
            if (purpose == 84) return AddressType.NATIVE_SEGWIT;
            if (purpose == 49) return AddressType.COMPATIBLE;
        }
        return AddressType.LEGACY;
    }

    private String getUri() {
        if (receiveAddress == null) return "";
        if (type instanceof NxtFamily) {
            return CoinURI.convertToCoinURI(receiveAddress, amount, label, message,
                    account.getPublicKeySerialized());
        } else {
            // Works for BitFamily, EvmFamily, SolanaFamily, CardanoFamily, ChiaFamily, etc.
            // Each coin's uriScheme (e.g. "ethereum", "solana") is used automatically.
            return CoinURI.convertToCoinURI(receiveAddress, amount, label, message);
        }
    }

    /**
     * Update qr code if the content is different
     */
    private void updateQrCode(final String qrContent) {
        if (lastQrContent == null || !lastQrContent.equals(qrContent)) {
            QrUtils.setQr(qrView, getResources(), qrContent);
            lastQrContent = qrContent;
        }
    }

    private void updateLabel() {
        label = resolveLabel(receiveAddress);
        if (label != null) {
            addressLabelView.setText(label);
            addressLabelView.setTypeface(Typeface.DEFAULT);
            addressView.setText(
                    GenericUtils.addressSplitToGroups(receiveAddress));
            addressView.setVisibility(View.VISIBLE);
        } else {
            addressLabelView.setText(
                    GenericUtils.addressSplitToGroupsMultiline(receiveAddress));
            addressLabelView.setTypeface(Typeface.MONOSPACE);
            addressView.setVisibility(View.GONE);
        }
    }

    private final ThrottlingWalletChangeListener walletListener = new ThrottlingWalletChangeListener() {
        @Override
        public void onThrottledWalletChanged() {
            handler.sendEmptyMessage(UPDATE_VIEW);
        }
    };

    private String resolveLabel(@Nonnull final AbstractAddress address) {
        return AddressBookProvider.resolveLabel(getActivity(), address);
    }

    @Override
    public WalletAccount getAccount() {
        return account;
    }

    private final LoaderManager.LoaderCallbacks<Cursor> rateLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            String localSymbol = config.getExchangeCurrencyCode();
            String coinSymbol = type.getSymbol();
            return new ExchangeRateLoader(getActivity(), config, localSymbol, coinSymbol);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (data != null && data.getCount() > 0) {
                data.moveToFirst();
                final ExchangeRatesProvider.ExchangeRate exchangeRate = ExchangeRatesProvider.getExchangeRate(data);
                handler.sendMessage(handler.obtainMessage(UPDATE_EXCHANGE_RATE, exchangeRate.rate));
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private final AmountEditView.Listener amountsListener = new AmountEditView.Listener() {
        boolean isValid(Value amount) {
            return amount != null && amount.isPositive()
                    && amount.compareTo(type.getMinNonDust()) >= 0;
        }

        void checkAndUpdateAmount() {
            Value amountParsed = amountCalculatorLink.getPrimaryAmount();
            if (isValid(amountParsed)) {
                amount = amountParsed;
            } else {
                amount = null;
            }
            updateView();
        }

        @Override
        public void changed() {
            checkAndUpdateAmount();
        }

        @Override
        public void focusChanged(final boolean hasFocus) {
            if (!hasFocus) {
                checkAndUpdateAmount();
            }
        }
    };

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
