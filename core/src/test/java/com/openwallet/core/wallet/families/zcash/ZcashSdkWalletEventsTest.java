package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.core.wallet.WalletAccountEventListener;
import com.openwallet.core.wallet.WalletConnectivityStatus;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

/** Note: {@code ZcashMain.get()} is a process-wide singleton; tests must not mutate its state. */
public class ZcashSdkWalletEventsTest {
    private final CoinType type = ZcashMain.get();

    @Test
    public void backendUpdateFiresBalanceAndWalletChanged() {
        ZcashSdkWallet wallet = new ZcashSdkWallet(type, "zcash.main:0");
        FakeZcashBackend backend = new FakeZcashBackend();
        wallet.setBackend(backend);

        final AtomicReference<Value> gotBalance = new AtomicReference<>();
        final AtomicInteger walletChangedCount = new AtomicInteger();
        wallet.addEventListener(new WalletAccountEventListener() {
            @Override public void onNewBalance(Value newBalance) { gotBalance.set(newBalance); }
            @Override public void onWalletChanged(WalletAccount pocket) { walletChangedCount.incrementAndGet(); }
            @Override public void onNewBlock(WalletAccount pocket) { }
            @Override public void onTransactionConfidenceChanged(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastFailure(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastSuccess(WalletAccount p, AbstractTransaction t) { }
            @Override public void onConnectivityStatus(WalletConnectivityStatus status) { }
        }, Runnable::run);

        backend.balanceZatoshi = 123_456L;
        backend.fireUpdate();

        assertEquals(type.value(123_456L), gotBalance.get());
        assertEquals(1, walletChangedCount.get());
    }

    @Test
    public void registeringBackendDoesNotFireBeforeUpdate() {
        ZcashSdkWallet wallet = new ZcashSdkWallet(type, "zcash.main:0");
        FakeZcashBackend backend = new FakeZcashBackend();
        wallet.setBackend(backend);
        final AtomicInteger count = new AtomicInteger();
        wallet.addEventListener(new WalletAccountEventListener() {
            @Override public void onNewBalance(Value newBalance) { count.incrementAndGet(); }
            @Override public void onWalletChanged(WalletAccount pocket) { count.incrementAndGet(); }
            @Override public void onNewBlock(WalletAccount pocket) { }
            @Override public void onTransactionConfidenceChanged(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastFailure(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastSuccess(WalletAccount p, AbstractTransaction t) { }
            @Override public void onConnectivityStatus(WalletConnectivityStatus status) { }
        }, Runnable::run);
        assertEquals(0, count.get());
    }

    @Test
    public void removedListenerReceivesNothing() {
        ZcashSdkWallet wallet = new ZcashSdkWallet(type, "zcash.main:0");
        FakeZcashBackend backend = new FakeZcashBackend();
        wallet.setBackend(backend);
        final AtomicInteger count = new AtomicInteger();
        WalletAccountEventListener listener = new WalletAccountEventListener() {
            @Override public void onNewBalance(Value newBalance) { count.incrementAndGet(); }
            @Override public void onWalletChanged(WalletAccount pocket) { count.incrementAndGet(); }
            @Override public void onNewBlock(WalletAccount pocket) { }
            @Override public void onTransactionConfidenceChanged(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastFailure(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastSuccess(WalletAccount p, AbstractTransaction t) { }
            @Override public void onConnectivityStatus(WalletConnectivityStatus status) { }
        };
        wallet.addEventListener(listener, Runnable::run);
        wallet.removeEventListener(listener);
        backend.balanceZatoshi = 42L;
        backend.fireUpdate();
        assertEquals(0, count.get());
    }

    @Test
    public void unchangedBalanceSkipsOnNewBalanceButFiresWalletChanged() {
        ZcashSdkWallet wallet = new ZcashSdkWallet(type, "zcash.main:0");
        FakeZcashBackend backend = new FakeZcashBackend();
        wallet.setBackend(backend);
        final AtomicInteger balanceEvents = new AtomicInteger();
        final AtomicInteger walletChangedEvents = new AtomicInteger();
        wallet.addEventListener(new WalletAccountEventListener() {
            @Override public void onNewBalance(Value newBalance) { balanceEvents.incrementAndGet(); }
            @Override public void onWalletChanged(WalletAccount pocket) { walletChangedEvents.incrementAndGet(); }
            @Override public void onNewBlock(WalletAccount pocket) { }
            @Override public void onTransactionConfidenceChanged(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastFailure(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastSuccess(WalletAccount p, AbstractTransaction t) { }
            @Override public void onConnectivityStatus(WalletConnectivityStatus status) { }
        }, Runnable::run);
        backend.balanceZatoshi = 500L;
        backend.fireUpdate();  // balance changed: fires both
        backend.fireUpdate();  // balance unchanged: only walletChanged
        assertEquals(1, balanceEvents.get());
        assertEquals(2, walletChangedEvents.get());
    }
}
