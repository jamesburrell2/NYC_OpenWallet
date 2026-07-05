package com.openwallet.wallet.ui;

import android.app.Activity;
import android.os.Bundle;
import androidx.fragment.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.wallet.Constants;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;
import com.openwallet.wallet.util.QrUtils;
import com.openwallet.wallet.util.UiUtils;

import static com.openwallet.core.Preconditions.checkNotNull;

/**
 * @author John L. Jegutanis
 */
public class AccountDetailsFragment extends Fragment {
    private String publicKeySerialized;
    private String derivationPath;

    public static AccountDetailsFragment newInstance(WalletAccount account) {
        AccountDetailsFragment fragment = new AccountDetailsFragment();
        Bundle args = new Bundle();
        args.putSerializable(Constants.ARG_ACCOUNT_ID, account.getId());
        fragment.setArguments(args);
        return fragment;
    }

    public AccountDetailsFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkNotNull(getArguments(), "Must provide arguments with an account id.");

        WalletApplication application = (WalletApplication) getActivity().getApplication();
        WalletAccount account =
                application.getAccount(getArguments().getString(Constants.ARG_ACCOUNT_ID));
        if (account == null) {
            Toast.makeText(getActivity(), R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
            getActivity().finish();
            return;
        }

        publicKeySerialized = account.getPublicKeySerialized();

        if (account instanceof com.openwallet.core.wallet.WalletPocketHD) {
            derivationPath = ((com.openwallet.core.wallet.WalletPocketHD) account).getDerivationPath();
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_account_details, container, false);

        TextView publicKey = (TextView) view.findViewById(R.id.public_key);
        publicKey.setOnClickListener(getPubKeyOnClickListener());
        publicKey.setText(publicKeySerialized);

        TextView pathLabel = (TextView) view.findViewById(R.id.derivation_path_label);
        TextView pathView = (TextView) view.findViewById(R.id.derivation_path);
        if (derivationPath != null && !derivationPath.isEmpty()) {
            pathView.setText(derivationPath);
        } else {
            pathLabel.setVisibility(View.GONE);
            pathView.setVisibility(View.GONE);
        }

        ImageView qrView = (ImageView) view.findViewById(R.id.qr_code_public_key);
        QrUtils.setQr(qrView, getResources(), publicKeySerialized);

        return view;
    }

    private View.OnClickListener getPubKeyOnClickListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Activity activity = getActivity();
                UiUtils.startCopyShareActionMode(publicKeySerialized, activity);
            }
        };
    }
}
