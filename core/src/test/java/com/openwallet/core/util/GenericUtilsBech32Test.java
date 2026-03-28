package com.openwallet.core.util;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class GenericUtilsBech32Test {

    private static final CoinType BTC = BitcoinMain.get();

    // A known valid Bitcoin P2WPKH address (bc1q...)
    // Derived from 20-byte all-zeros pubkey hash — valid bech32 encoding
    // Generated via: Bech32.encode("bc", 0, new byte[20])
    private static final String BC1Q_ADDRESS = "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs";

    @Test
    public void getPossibleTypes_acceptsNativeSegWit() throws AddressMalformedException {
        List<CoinType> types = GenericUtils.getPossibleTypes(BC1Q_ADDRESS);
        assertFalse("Expected at least one matching type for bech32 address", types.isEmpty());
        assertEquals(BTC, types.get(0));
    }

    @Test
    public void getPossibleTypes_throwsForUnknownBech32() {
        try {
            // Generated via: Bech32.encode("xx", 0, new byte[20]) — valid bech32 but unknown HRP
            GenericUtils.getPossibleTypes("xx1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqfdk2wa");
            fail("Expected AddressMalformedException for unknown HRP");
        } catch (AddressMalformedException e) {
            assertTrue(e.getMessage().contains("Unsupported"));
        }
    }

    @Test
    public void bitFamilyNewAddress_returnsSegwitAddressForBech32() throws AddressMalformedException {
        AbstractAddress addr = BTC.newAddress(BC1Q_ADDRESS);
        assertTrue("Expected SegwitAddress for bc1q... input", addr instanceof SegwitAddress);
    }

    @Test
    public void bitFamilyNewAddress_stillWorksForLegacy() throws AddressMalformedException {
        // All-zeros hash160 → "1111111111111111111114oLvT2" (known valid BTC P2PKH address)
        AbstractAddress addr = BTC.newAddress("1111111111111111111114oLvT2");
        assertNotNull(addr);
    }

    @Test
    public void bitFamilyNewAddress_throwsForGarbage() {
        try {
            BTC.newAddress("notanaddressatall");
            fail("Expected AddressMalformedException");
        } catch (AddressMalformedException e) {
            // expected
        }
    }
}
