package com.openwallet.wallet.ui;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.openwallet.core.wallet.Wallet;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows all wallet slots and lets the user switch, rename, or add wallets.
 */
public class ManageWalletsActivity extends AppCompatActivity {

    private WalletApplication app;
    private ListView listView;
    private WalletListAdapter adapter;
    private List<WalletItem> items = new ArrayList<>();

    // ---- Data model ----

    private static class WalletItem {
        final int index;
        final String name;
        final boolean active;
        final boolean exists;

        WalletItem(int index, String name, boolean active, boolean exists) {
            this.index = index;
            this.name  = name;
            this.active = active;
            this.exists = exists;
        }
    }

    // ---- Adapter ----

    private class WalletListAdapter extends ArrayAdapter<WalletItem> {
        WalletListAdapter() {
            super(ManageWalletsActivity.this, android.R.layout.simple_list_item_2, items);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(android.R.layout.simple_list_item_2, parent, false);
            }
            WalletItem item = getItem(position);
            if (item == null) return convertView;

            TextView text1 = convertView.findViewById(android.R.id.text1);
            TextView text2 = convertView.findViewById(android.R.id.text2);

            text1.setText(item.active ? item.name + " ✓" : item.name);
            if (!item.exists) {
                text2.setText(getString(R.string.manage_wallets_empty_slot));
            } else if (item.active) {
                text2.setText(getString(R.string.manage_wallets_active_label));
            } else {
                text2.setText(getString(R.string.manage_wallets_tap_for_options));
            }
            return convertView;
        }
    }

    // ---- Lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = (WalletApplication) getApplication();

        // Simple layout: a ListView + an "Add Wallet" button
        setContentView(R.layout.activity_manage_wallets);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_activity_manage_wallets);
        }

        listView = findViewById(R.id.manage_wallets_list);
        adapter  = new WalletListAdapter();
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            WalletItem item = items.get(position);
            if (!item.exists) return;
            showWalletOptionsDialog(item);
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            WalletItem item = items.get(position);
            if (!item.exists || item.active) return false;
            showDeleteDialog(item);
            return true;
        });

        Button addButton = findViewById(R.id.manage_wallets_add_button);
        addButton.setOnClickListener(v -> {
            if (app.getWalletCount() >= 5) {
                Toast.makeText(this, R.string.manage_wallets_max_reached, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, IntroActivity.class);
            intent.putExtra(IntroActivity.ARG_ADD_NEW_WALLET, true);
            startActivity(intent);
        });

        refreshList();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- Helpers ----

    private void refreshList() {
        items.clear();
        int count  = app.getWalletCount();
        int active = app.getActiveWalletIndex();
        for (int i = 0; i < count; i++) {
            Wallet w = app.getWalletAtIndex(i);
            items.add(new WalletItem(i, app.getWalletName(i), i == active, w != null));
        }
        adapter.notifyDataSetChanged();
    }

    private void switchToWallet(WalletItem item) {
        app.switchToWallet(item.index);
        Toast.makeText(this,
                getString(R.string.manage_wallets_switched, item.name),
                Toast.LENGTH_SHORT).show();
        // Restart WalletActivity so all fragments reload from the new wallet.
        Intent intent = new Intent(this, WalletActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    private void showWalletOptionsDialog(final WalletItem item) {
        final java.util.List<Runnable> actions = new ArrayList<>();
        final java.util.List<String> labels = new ArrayList<>();

        if (!item.active) {
            labels.add(getString(R.string.manage_wallets_switch_action));
            actions.add(() -> switchToWallet(item));
        }
        labels.add(getString(R.string.manage_wallets_rename_action));
        actions.add(() -> showRenameDialog(item));
        if (!item.active) {
            labels.add(getString(R.string.manage_wallets_delete_action));
            actions.add(() -> showDeleteDialog(item));
        }

        String title = item.active
                ? getString(R.string.manage_wallets_options_active, item.name)
                : item.name;

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showRenameDialog(final WalletItem item) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(item.name);
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_wallets_rename_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        app.setWalletName(item.index, newName);
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeleteDialog(final WalletItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_wallets_delete_title)
                .setMessage(getString(R.string.manage_wallets_delete_message, item.name))
                .setPositiveButton(R.string.button_delete, (dialog, which) -> {
                    if (app.deleteWallet(item.index)) {
                        Toast.makeText(this,
                                getString(R.string.manage_wallets_deleted, item.name),
                                Toast.LENGTH_SHORT).show();
                        refreshList();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
