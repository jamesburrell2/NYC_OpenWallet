package com.openwallet.core.wallet.families.zcash;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Minimal scriptable ZcashBackendDelegate for core-module unit tests. */
public class FakeZcashBackend implements ZcashBackendDelegate {
    public long balanceZatoshi = 0L;
    public boolean connected = true;
    public List<ZcashSdkTransaction> transactions = new ArrayList<>();
    public String lastRecipient;
    public long lastZatoshi;

    @Nullable private UpdateListener updateListener;

    @Override
    public void setUpdateListener(@Nullable UpdateListener listener) {
        this.updateListener = listener;
    }

    /** Test hook: simulate the SDK pushing new data. */
    public void fireUpdate() {
        if (updateListener != null) updateListener.onBackendUpdated();
    }

    @Override public void startSync() { }
    @Override public void stopSync() { }
    @Override public String getReceiveAddress() { return "u1" + fill(100); }
    @Override public String getTransparentAddress() { return "t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"; }
    @Nullable @Override public String getSaplingAddress() { return "zs1" + fill(75); }
    @Override public long getBalanceZatoshi() { return balanceZatoshi; }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isLoading() { return false; }
    @Override public int getSyncProgressPercent() { return 100; }
    @Override public List<ZcashSdkTransaction> getTransactions() { return transactions; }

    @Override
    public void sendTo(String recipient, long zatoshi, @Nullable String memo, SendCallback cb) {
        lastRecipient = recipient;
        lastZatoshi = zatoshi;
        cb.onSuccess(fill(64));
    }

    private static String fill(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('q');
        return b.toString();
    }
}
