package com.openwallet.wallet.ui.adaptors;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.network.CoinAddress;
import com.openwallet.wallet.Configuration;
import com.openwallet.wallet.Constants;
import com.openwallet.wallet.R;
import com.openwallet.wallet.ui.widget.CoinListItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for the Coin Servers settings screen.
 * Shows each supported coin with its currently effective ElectrumX server address.
 */
public class ServerListAdapter extends BaseAdapter {
    private final Context context;
    private final Configuration config;
    private final List<CoinType> coins;

    public ServerListAdapter(Context context, Configuration config) {
        this.context = context;
        this.config = config;
        this.coins = new ArrayList<>(Constants.SUPPORTED_COINS);
    }

    public void update() {
        notifyDataSetChanged();
    }

    @Override
    public int getCount() { return coins.size(); }

    @Override
    public CoinType getItem(int position) { return coins.get(position); }

    @Override
    public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) view = new CoinListItem(context);
        CoinListItem row = (CoinListItem) view;
        CoinType type = getItem(position);
        row.setCoin(type);

        String userServer = config.getCoinElectrumServer(type);
        if (userServer != null && !userServer.isEmpty()) {
            row.setSubtitle(userServer + " " + context.getString(R.string.server_custom_label));
        } else {
            row.setSubtitle(getDefaultServer(type));
        }
        return row;
    }

    private String getDefaultServer(CoinType type) {
        for (CoinAddress addr : Constants.DEFAULT_COINS_SERVERS) {
            if (addr.getType() == type) {
                return addr.getAddresses().get(0).getHost()
                        + ":" + addr.getAddresses().get(0).getPort();
            }
        }
        return context.getString(R.string.server_not_configured);
    }
}
