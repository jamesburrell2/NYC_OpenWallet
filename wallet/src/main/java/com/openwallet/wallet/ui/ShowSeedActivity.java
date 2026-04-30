package com.openwallet.wallet.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.WindowManager;

import com.openwallet.wallet.R;

/**
 * @author John L. Jegutanis
 */
public class ShowSeedActivity extends BaseWalletActivity implements ShowSeedFragment.Listener {

    private static final String SHOW_SEED_TAG = "show_seed_tag";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Prevent screenshots and screen recording while seed is visible
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fragment_wrapper);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, new ShowSeedFragment(), SHOW_SEED_TAG)
                    .commit();
        }

        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(false);
    }

    @Override
    public void onSeedNotAvailable() {
        DialogBuilder.warn(this, R.string.seed_not_available_title)
                .setMessage(R.string.seed_not_available)
                .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .create().show();
    }

    @Override
    public void onPassword(CharSequence password) {
        ShowSeedFragment f = (ShowSeedFragment) getFM().findFragmentByTag(SHOW_SEED_TAG);
        if (f != null) {
            f.setPassword(password);
        }
    }
}
