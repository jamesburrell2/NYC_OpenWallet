package com.openwallet.wallet.ui;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.openwallet.wallet.AddressBookProvider;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * @author John L. Jegutanis
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private Configuration config;
    private ActivityResultLauncher<Intent> importAddressBookLauncher;
    private ActivityResultLauncher<Intent> exportAddressBookLauncher;
    private ActivityResultLauncher<Intent> exportTransactionsLauncher;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);
        config = ((WalletApplication) requireActivity().getApplication()).getConfiguration();
        wirePreferences();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        importAddressBookLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) importAddressBook(uri);
                    }
                });
        exportAddressBookLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) writeAddressBookCsv(uri);
                    }
                });
        exportTransactionsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) writeTransactionsCsv(uri);
                    }
                });
    }

    private void wirePreferences() {
        // Dark mode
        CheckBoxPreference darkMode = findPreference(Configuration.PREFS_KEY_DARK_MODE);
        if (darkMode != null) {
            darkMode.setOnPreferenceChangeListener((pref, newValue) -> {
                boolean enabled = (Boolean) newValue;
                config.setDarkMode(enabled);
                AppCompatDelegate.setDefaultNightMode(
                        enabled ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
                return true;
            });
        }

        // Language
        Preference language = findPreference("language");
        if (language != null) {
            language.setOnPreferenceClickListener(pref -> {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        // Screen lock
        CheckBoxPreference screenLock = findPreference(Configuration.PREFS_KEY_SCREEN_LOCK_ENABLED);
        if (screenLock != null) {
            screenLock.setOnPreferenceChangeListener((pref, newValue) -> {
                config.setScreenLockEnabled((Boolean) newValue);
                return true;
            });
        }

        // Hide balances
        CheckBoxPreference hideBalances = findPreference(Configuration.PREFS_KEY_HIDE_BALANCES);
        if (hideBalances != null) {
            hideBalances.setOnPreferenceChangeListener((pref, newValue) -> {
                config.setHideBalances((Boolean) newValue);
                return true;
            });
        }

        // Export transactions
        Preference exportTx = findPreference("export_transactions");
        if (exportTx != null) {
            exportTx.setOnPreferenceClickListener(pref -> {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, "transactions_" + ts + ".csv");
                exportTransactionsLauncher.launch(intent);
                return true;
            });
        }

        // Export address book
        Preference exportAb = findPreference("export_address_book");
        if (exportAb != null) {
            exportAb.setOnPreferenceClickListener(pref -> {
                String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, "address_book_" + ts + ".csv");
                exportAddressBookLauncher.launch(intent);
                return true;
            });
        }

        // Import address book
        Preference importAb = findPreference("import_address_book");
        if (importAb != null) {
            importAb.setOnPreferenceClickListener(pref -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                importAddressBookLauncher.launch(intent);
                return true;
            });
        }
    }

    // ── Address book export ───────────────────────────────────────────────────

    private void writeAddressBookCsv(Uri uri) {
        String pkg = requireContext().getPackageName();
        Uri contentUri = AddressBookProvider.contentUri(pkg);
        Cursor cursor = requireContext().getContentResolver().query(contentUri, null, null, null, null);
        if (cursor == null) {
            Toast.makeText(requireContext(), getString(R.string.export_error, "no data"), Toast.LENGTH_SHORT).show();
            return;
        }
        try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("Cannot open output stream");
            StringBuilder sb = new StringBuilder("coin_id,address,label\n");
            while (cursor.moveToNext()) {
                String coinId = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_COIN_ID));
                String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
                String label = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
                sb.append(escapeCsv(coinId)).append(',')
                  .append(escapeCsv(address)).append(',')
                  .append(escapeCsv(label)).append('\n');
            }
            os.write(sb.toString().getBytes("UTF-8"));
            String name = getFileNameFromUri(uri);
            Toast.makeText(requireContext(), getString(R.string.address_book_exported, name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.export_error, e.getMessage()), Toast.LENGTH_LONG).show();
        } finally {
            cursor.close();
        }
    }

    // ── Address book import ───────────────────────────────────────────────────

    private void importAddressBook(Uri uri) {
        String pkg = requireContext().getPackageName();
        Uri contentUri = AddressBookProvider.contentUri(pkg);
        int imported = 0;
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header
                String[] parts = parseCsvLine(line);
                if (parts.length >= 3) {
                    ContentValues cv = new ContentValues();
                    cv.put(AddressBookProvider.KEY_COIN_ID, parts[0].trim());
                    cv.put(AddressBookProvider.KEY_ADDRESS, parts[1].trim());
                    cv.put(AddressBookProvider.KEY_LABEL, parts[2].trim());
                    requireContext().getContentResolver().insert(contentUri, cv);
                    imported++;
                }
            }
            Toast.makeText(requireContext(), getString(R.string.address_book_imported, imported), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.import_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ── Transactions export ───────────────────────────────────────────────────

    private void writeTransactionsCsv(Uri uri) {
        WalletApplication app = (WalletApplication) requireActivity().getApplication();
        if (app.getWallet() == null) {
            Toast.makeText(requireContext(), getString(R.string.export_error, "wallet not loaded"), Toast.LENGTH_SHORT).show();
            return;
        }
        try (OutputStream os = requireContext().getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IOException("Cannot open output stream");
            StringBuilder sb = new StringBuilder("coin,txid,date,amount\n");
            for (com.openwallet.core.wallet.WalletAccount account : app.getAllAccounts()) {
                String coinSymbol = account.getCoinType().getSymbol();
                if (!(account instanceof com.openwallet.core.wallet.AbstractWallet)) continue;
                com.openwallet.core.wallet.AbstractWallet abstractWallet =
                        (com.openwallet.core.wallet.AbstractWallet) account;
                for (Object txObj : abstractWallet.getTransactions().values()) {
                    com.openwallet.core.wallet.AbstractTransaction tx =
                            (com.openwallet.core.wallet.AbstractTransaction) txObj;
                    String txid = tx.getHashAsString();
                    long timeMs = tx.getTimestamp();
                    String date = timeMs > 0
                            ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(timeMs * 1000))
                            : "";
                    com.openwallet.core.coins.Value value = tx.getValue(abstractWallet);
                    String amount = value != null ? value.toFriendlyString() : "";
                    sb.append(escapeCsv(coinSymbol)).append(',')
                      .append(escapeCsv(txid)).append(',')
                      .append(escapeCsv(date)).append(',')
                      .append(escapeCsv(amount)).append('\n');
                }
            }
            os.write(sb.toString().getBytes("UTF-8"));
            String name = getFileNameFromUri(uri);
            Toast.makeText(requireContext(), getString(R.string.transactions_exported, name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(), getString(R.string.export_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ── CSV helpers ───────────────────────────────────────────────────────────

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String[] parseCsvLine(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
    }

    private String getFileNameFromUri(Uri uri) {
        String result = uri.getLastPathSegment();
        Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (idx >= 0) result = cursor.getString(idx);
            cursor.close();
        }
        return result != null ? result : "file";
    }
}
