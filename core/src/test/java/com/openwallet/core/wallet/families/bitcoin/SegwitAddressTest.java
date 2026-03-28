package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.LitecoinMain;

import org.junit.Test;

import static org.junit.Assert.*;

public class SegwitAddressTest {

    private static final CoinType BTC = BitcoinMain.get();
    private static final CoinType LTC = LitecoinMain.get();

    // 20-byte hash160 value
    private static final byte[] HASH160 = hexToBytes("aabbccddeeff00112233445566778899aabbccdd");

    @Test
    public void segwitAddress_fromHash160_btc() {
        SegwitAddress addr = SegwitAddress.fromHash160(BTC, HASH160);
        String s = addr.toString();
        assertTrue("Expected bc1q prefix", s.startsWith("bc1q"));
        assertEquals(BTC, addr.getType());
        assertArrayEquals(HASH160, addr.getHash160());
    }

    @Test
    public void segwitAddress_fromHash160_ltc() {
        SegwitAddress addr = SegwitAddress.fromHash160(LTC, HASH160);
        String s = addr.toString();
        assertTrue("Expected ltc1q prefix", s.startsWith("ltc1q"));
    }

    @Test
    public void segwitAddress_roundTrip() {
        SegwitAddress addr = SegwitAddress.fromHash160(BTC, HASH160);
        String encoded = addr.toString();
        // Decode via Bech32 and verify
        com.openwallet.core.util.Bech32.DecodedBech32 decoded =
                com.openwallet.core.util.Bech32.decode(encoded);
        assertEquals("bc", decoded.hrp);
        assertEquals(0, decoded.witnessVersion);
        assertArrayEquals(HASH160, decoded.program);
        assertEquals(com.openwallet.core.util.Bech32.Variant.BECH32, decoded.variant);
    }

    @Test
    public void segwitAddress_wrongLength_throws() {
        try {
            SegwitAddress.fromHash160(BTC, new byte[19]);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    @Test
    public void taprootAddress_fromXOnlyKey() {
        // 32-byte x-only key
        byte[] xOnly = hexToBytes("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798");
        TaprootAddress addr = TaprootAddress.fromXOnlyKey(BTC, xOnly);
        String s = addr.toString();
        assertTrue("Expected bc1p prefix for taproot", s.startsWith("bc1p"));
        assertEquals(BTC, addr.getType());
        assertEquals(32, addr.getOutputKey().length);
    }

    @Test
    public void taprootAddress_ltc() {
        byte[] xOnly = hexToBytes("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798");
        TaprootAddress addr = TaprootAddress.fromXOnlyKey(LTC, xOnly);
        assertTrue("Expected ltc1p prefix for taproot", addr.toString().startsWith("ltc1p"));
    }

    @Test
    public void coinType_addressFromKey_segwit_typesPresent() {
        // Verify BTC supports all four address types
        assertTrue(BTC.getSupportedAddressTypes().contains(AddressType.LEGACY));
        assertTrue(BTC.getSupportedAddressTypes().contains(AddressType.COMPATIBLE));
        assertTrue(BTC.getSupportedAddressTypes().contains(AddressType.NATIVE_SEGWIT));
        assertTrue(BTC.getSupportedAddressTypes().contains(AddressType.TAPROOT));
        assertEquals("bc", BTC.getBech32Hrp());
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
