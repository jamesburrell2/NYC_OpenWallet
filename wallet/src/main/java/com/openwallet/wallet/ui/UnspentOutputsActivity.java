package com.openwallet.wallet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.WalletPocketHD;
import com.openwallet.core.wallet.families.bitcoin.OutPointOutput;
import com.openwallet.core.wallet.families.bitcoin.TrimmedOutPoint;
import com.openwallet.wallet.Constants;
import com.openwallet.wallet.R;
import com.openwallet.wallet.WalletApplication;

import org.bitcoinj.script.Script;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Displays the list of unspent transaction outputs (UTXOs) for the current account,
 * similar to the "Unspent Outputs" view in Coinomi.
 */
public class UnspentOutputsActivity extends BaseWalletActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unspent_outputs);

        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowHomeEnabled(false);
            ab.setTitle(R.string.unspent_outputs_title);
        }

        String accountId = getIntent().getStringExtra(Constants.ARG_ACCOUNT_ID);
        WalletApplication app = (WalletApplication) getApplicationContext();
        WalletPocketHD pocket = (WalletPocketHD) app.getAccount(accountId);

        ListView listView = findViewById(R.id.utxo_list);
        TextView emptyView = findViewById(R.id.utxo_empty);
        listView.setEmptyView(emptyView);

        if (pocket == null) {
            Toast.makeText(this, R.string.no_such_pocket_error, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        CoinType coinType = pocket.getCoinType();
        Map<TrimmedOutPoint, OutPointOutput> utxoMap = pocket.getUnspentOutputs(true);

        List<UtxoItem> items = new ArrayList<>();
        for (OutPointOutput utxo : utxoMap.values()) {
            String address = resolveAddress(utxo, coinType);
            String amount = utxo.getValue().toFriendlyString();
            String txid = utxo.getTxHash().toString();
            long vout = utxo.getOutPoint().getIndex();
            items.add(new UtxoItem(amount, address, txid, vout));
        }

        UtxoAdapter adapter = new UtxoAdapter(this, items);
        listView.setAdapter(adapter);
    }

    private String resolveAddress(OutPointOutput utxo, CoinType coinType) {
        try {
            Script script = utxo.getOutput().getScriptPubKey();
            return script.getToAddress(coinType).toString();
        } catch (Exception e) {
            // Fallback: show txid:vout
            return utxo.getTxHash().toString().substring(0, 16) + "...:" +
                    utxo.getOutPoint().getIndex();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Data model ───────────────────────────────────────────────────────────

    static class UtxoItem {
        final String amount;
        final String address;
        final String txid;
        final long vout;

        UtxoItem(String amount, String address, String txid, long vout) {
            this.amount = amount;
            this.address = address;
            this.txid = txid;
            this.vout = vout;
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    static class UtxoAdapter extends ArrayAdapter<UtxoItem> {
        private final LayoutInflater inflater;

        UtxoAdapter(Context context, List<UtxoItem> items) {
            super(context, 0, items);
            inflater = LayoutInflater.from(context);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.list_item_utxo, parent, false);
            }
            UtxoItem item = getItem(position);
            if (item == null) return convertView;

            TextView amountView = convertView.findViewById(R.id.utxo_amount);
            TextView addressView = convertView.findViewById(R.id.utxo_address);

            amountView.setText(item.amount);
            addressView.setText(item.address);

            // Long-press copies address to clipboard
            final String address = item.address;
            final String txRef = item.txid + ":" + item.vout;
            convertView.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager)
                        v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("address", address));
                    Toast.makeText(v.getContext(),
                            R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            return convertView;
        }
    }
}
