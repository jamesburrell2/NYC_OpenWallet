package com.openwallet.core.coins;

import org.bitcoinj.core.Base58;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ZcashTest {

    // Known Zcash transparent address (t1 = P2PKH, 0x1C 0xB8)
    // hash160 = all-zeros 20 bytes — used only to test encode/decode round-trip
    static final byte[] ZERO_HASH160 = new byte[20];

    // Known real ZEC t1 address to validate prefix
    // t1 addresses start with "t1" which encodes version 0x1C 0xB8
    static final String KNOWN_T1_ADDR = "t1KVGHzxCmVdmNBME6o7KFqBFMoGVQjqPBW";

    @Test
    public void encodeZeroHash160ProducesT1Prefix() {
        // Arrange: use null coin type — ZcashAddress only needs addressHeader
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        // Assert: encoded address starts with "t1"
        assertTrue("Expected t1 prefix, got: " + addr.toString(),
                addr.toString().startsWith("t1"));
    }

    @Test
    public void encodeP2SHProducesT3Prefix() {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CBD, ZERO_HASH160);
        assertTrue("Expected t3 prefix, got: " + addr.toString(),
                addr.toString().startsWith("t3"));
    }

    @Test
    public void roundTripPreservesHash160() throws Exception {
        byte[] hash160 = new byte[20];
        for (int i = 0; i < 20; i++) hash160[i] = (byte) (i + 1);
        ZcashAddress encoded = ZcashAddress.fromHash160(0x1CB8, hash160);
        ZcashAddress decoded = ZcashAddress.fromString(encoded.toString());
        assertArrayEquals(hash160, decoded.getHash160());
    }

    @Test
    public void roundTripPreservesVersion() throws Exception {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        ZcashAddress decoded = ZcashAddress.fromString(addr.toString());
        assertEquals(0x1CB8, decoded.getVersion());
    }

    @Test(expected = Exception.class)
    public void invalidChecksumThrows() throws Exception {
        // Corrupt the last character of a valid address
        String corrupted = KNOWN_T1_ADDR.substring(0, KNOWN_T1_ADDR.length() - 1) + "X";
        ZcashAddress.fromString(corrupted);
    }

    @Test
    public void addressLengthIsReasonable() {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        int len = addr.toString().length();
        assertTrue("Address length " + len + " not in expected range [34,36]",
                len >= 34 && len <= 36);
    }
}
