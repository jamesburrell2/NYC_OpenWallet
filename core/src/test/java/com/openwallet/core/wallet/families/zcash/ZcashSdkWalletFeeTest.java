package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.wallet.SendRequest;
import com.openwallet.core.wallet.WalletAccount.WalletAccountException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ZcashSdkWalletFeeTest {
    private final CoinType type = ZcashMain.get();
    private ZcashSdkWallet wallet;
    private FakeZcashBackend backend;

    @Before
    public void setUp() {
        wallet = new ZcashSdkWallet(type, "zcash.main:0");
        backend = new FakeZcashBackend();
        wallet.setBackend(backend);
    }

    @Test
    public void feeConstantsMatchZip317() {
        assertEquals(15_000L, ZcashSdkWallet.ZIP317_STANDARD_FEE);
        assertEquals(20_000L, ZcashSdkWallet.SEND_ALL_FEE_MARGIN);
    }

    @Test
    public void sendAllSubtractsMargin() throws Exception {
        backend.balanceZatoshi = 1_000_000L;
        SendRequest req = wallet.getEmptyWalletRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"));
        ZcashSdkTransaction tx = (ZcashSdkTransaction) req.tx;
        assertEquals(1_000_000L - 20_000L, tx.getValueZatoshi());
    }

    @Test(expected = WalletAccountException.class)
    public void sendAllRejectsDustBalance() throws Exception {
        backend.balanceZatoshi = 20_000L; // == margin, nothing left to send
        wallet.getEmptyWalletRequest(type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"));
    }

    @Test(expected = WalletAccountException.class)
    public void completeRejectsAmountPlusFeeOverBalance() throws Exception {
        backend.balanceZatoshi = 100_000L;
        // 90_000 + 15_000 fee > 100_000 must fail
        SendRequest req = wallet.getSendToRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"), type.value(90_000L));
        wallet.completeTransaction(req);
    }

    @Test
    public void completeAcceptsAffordableSend() throws Exception {
        backend.balanceZatoshi = 100_000L;
        SendRequest req = wallet.getSendToRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"), type.value(80_000L));
        wallet.completeTransaction(req); // must not throw
    }
}
