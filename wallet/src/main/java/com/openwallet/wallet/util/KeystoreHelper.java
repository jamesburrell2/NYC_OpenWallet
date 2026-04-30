package com.openwallet.wallet.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Manages an AES-256-GCM key stored in the Android Keystore.
 *
 * The wallet's AES encryption key (derived from the user's password via scrypt inside bitcoinj's
 * KeyCrypterScrypt) is NOT stored here; that key only exists in memory while the wallet is
 * unlocked. This class instead provides:
 *
 *   1. {@link #getOrCreateMasterKey(Context)} — a hardware-backed AES-256-GCM KeyStore key that
 *      can be used to wrap additional secrets (e.g. biometric-protected seed cache).
 *
 *   2. {@link #getEncryptedPrefs(Context)} — an EncryptedSharedPreferences instance backed by the
 *      AndroidKeyStore master key, used to store small secrets (e.g. the user-configured server
 *      hostname) without them being readable from the raw SharedPreferences XML on-disk.
 *
 * Security properties:
 *   - Key material never leaves the Keystore TEE/StrongBox.
 *   - FLAG_SECURE is enforced by all wallet activities (see BaseWalletActivity).
 *   - The wallet file itself is encrypted by bitcoinj's KeyCrypterScrypt + user password;
 *     this class does NOT replace that layer.
 *
 * Play Store compliance:
 *   - minSdk 26 (API 26) guarantees AndroidKeyStore + AES/GCM availability on all devices.
 */
public final class KeystoreHelper {

    private static final String KEYSTORE_PROVIDER  = "AndroidKeyStore";
    private static final String KEY_ALIAS           = "nyc_wallet_master_v1";
    private static final String ENCRYPTED_PREFS_FILE = "nyc_secure_prefs";

    private KeystoreHelper() {}

    /**
     * Returns (creating if necessary) a hardware-backed AES-256-GCM SecretKey stored in the
     * Android Keystore under {@link #KEY_ALIAS}.
     *
     * Requires API 23+; minSdk 26 guarantees availability.
     */
    @NonNull
    public static SecretKey getOrCreateMasterKey(@NonNull Context context)
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);

        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build();

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
        kg.init(spec);
        return kg.generateKey();
    }

    /**
     * Returns an {@link EncryptedSharedPreferences} backed by the AndroidKeyStore master key.
     * Safe to call multiple times; the underlying file is created on first access.
     */
    @NonNull
    public static SharedPreferences getEncryptedPrefs(@NonNull Context context)
            throws GeneralSecurityException, IOException {
        MasterKey masterKey = new MasterKey.Builder(context, KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        return EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    /**
     * Encrypts {@code plaintext} bytes with the AndroidKeyStore key using AES-256-GCM.
     * The returned blob is: [12-byte IV | ciphertext | 16-byte GCM tag].
     * Store the returned bytes alongside the wallet file for any per-slot secret you need to wrap.
     */
    @NonNull
    public static byte[] encrypt(@NonNull Context context, @NonNull byte[] plaintext)
            throws GeneralSecurityException, IOException {
        SecretKey key = getOrCreateMasterKey(context);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();           // 12 bytes, randomly generated
        byte[] ciphertext = cipher.doFinal(plaintext);

        // Prepend IV to ciphertext for storage
        byte[] blob = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
        return blob;
    }

    /**
     * Decrypts a blob previously produced by {@link #encrypt}.
     * Returns null if the alias no longer exists (e.g. after factory reset) — callers must
     * handle re-creation in that case.
     */
    @Nullable
    public static byte[] decrypt(@NonNull Context context, @NonNull byte[] blob)
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) return null;

        SecretKey key = getOrCreateMasterKey(context);
        byte[] iv         = new byte[12];
        byte[] ciphertext = new byte[blob.length - 12];
        System.arraycopy(blob, 0, iv, 0, 12);
        System.arraycopy(blob, 12, ciphertext, 0, ciphertext.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }
}
