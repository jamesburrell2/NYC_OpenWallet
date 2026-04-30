package com.openwallet.wallet.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.openwallet.wallet.R;

import java.util.concurrent.Executor;

/**
 * Lock screen shown when the app is resumed after being in the background.
 *
 * Uses the AndroidX Biometric API (biometric:1.1.0) which:
 *   - Falls back to device PIN/pattern/password if biometrics are unavailable.
 *   - Works on API 23+; our minSdk 26 guarantees availability.
 *
 * The activity is transparent — it sits over the app while authentication is pending
 * and finishes immediately on success, returning the user to their previous screen.
 *
 * Usage: call {@link #lockIfNeeded(AppCompatActivity)} from every BaseWalletActivity.onResume()
 * when the app was backgrounded for longer than LOCK_AFTER_MS.
 */
public class BiometricLockActivity extends AppCompatActivity {

    /** How long the app can be in the background before requiring re-authentication (30 s). */
    public static final long LOCK_AFTER_MS = 30_000L;

    /** Extra key carrying the intent to launch after successful unlock. */
    public static final String EXTRA_TARGET_INTENT = "target_intent";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // FLAG_SECURE ensures the lock screen itself can't be screen-captured.
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_biometric_lock);
        showBiometricPrompt();
    }

    private void showBiometricPrompt() {
        // Check whether any authenticator is available before showing the prompt.
        BiometricManager bm = BiometricManager.from(this);
        int canAuthenticate = bm.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
                | BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device has no secure lock screen — unlock immediately.
            // This should not happen on a Play Store device, but handle gracefully.
            onUnlocked();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(this);

        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        onUnlocked();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                            @NonNull CharSequence errString) {
                        // User cancelled or hit the error limit — close the app.
                        Toast.makeText(BiometricLockActivity.this,
                                errString, Toast.LENGTH_SHORT).show();
                        finishAffinity();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Single failed attempt — the prompt stays open; do nothing here.
                    }
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_prompt_title))
                .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        prompt.authenticate(info);
    }

    private void onUnlocked() {
        Intent target = getIntent().getParcelableExtra(EXTRA_TARGET_INTENT);
        if (target != null) {
            startActivity(target);
        }
        finish();
    }
}
