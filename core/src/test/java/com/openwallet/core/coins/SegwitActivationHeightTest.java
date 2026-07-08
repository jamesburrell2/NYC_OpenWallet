package com.openwallet.core.coins;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class SegwitActivationHeightTest {

    @Test
    public void nycGatedBelowActivationHeight() {
        CoinType nyc = NewYorkCoinMain.get();
        assertEquals(13_500_000, nyc.getSegwitActivationHeight());
        assertFalse("unsynced (-1) must be legacy-only", nyc.isSegwitActivatedAt(-1));
        assertFalse(nyc.isSegwitActivatedAt(13_499_999));
        assertTrue(nyc.isSegwitActivatedAt(13_500_000));
        assertTrue(nyc.isSegwitActivatedAt(20_000_000));
    }

    @Test
    public void nycEffectiveTypesLegacyOnlyBeforeActivation() {
        CoinType nyc = NewYorkCoinMain.get();
        Set<AddressType> before = nyc.effectiveAddressTypes(13_499_999);
        assertTrue(before.contains(AddressType.LEGACY));
        assertFalse(before.contains(AddressType.COMPATIBLE));
        assertFalse(before.contains(AddressType.NATIVE_SEGWIT));

        Set<AddressType> after = nyc.effectiveAddressTypes(13_500_000);
        assertTrue(after.contains(AddressType.LEGACY));
        assertTrue(after.contains(AddressType.COMPATIBLE));
        assertTrue(after.contains(AddressType.NATIVE_SEGWIT));
    }

    @Test
    public void nycStaticFlagFalseForAddCoinGate() {
        assertFalse(NewYorkCoinMain.get().isSegwitActivated());
    }

    @Test
    public void bitcoinAlwaysActivated() {
        CoinType btc = BitcoinMain.get();
        assertEquals(0, btc.getSegwitActivationHeight());
        assertTrue(btc.isSegwitActivatedAt(-1));
        assertTrue(btc.isSegwitActivatedAt(0));
        assertTrue(btc.isSegwitActivated());
        assertEquals(btc.getSupportedAddressTypes(), btc.effectiveAddressTypes(-1));
    }
}
