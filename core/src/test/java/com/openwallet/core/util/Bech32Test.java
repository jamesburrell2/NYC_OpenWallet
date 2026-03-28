package com.openwallet.core.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class Bech32Test {

    // Round-trip: encode then decode must recover original program
    @Test
    public void bech32EncodeDecode_roundTrip_p2wpkh() {
        byte[] program = hexToBytes("aabbccddeeff00112233445566778899aabbccdd");
        String encoded = Bech32.encode("bc", 0, program);
        assertTrue("P2WPKH must start with bc1q", encoded.startsWith("bc1q"));

        Bech32.DecodedBech32 decoded = Bech32.decode(encoded);
        assertEquals("bc", decoded.hrp);
        assertEquals(0, decoded.witnessVersion);
        assertArrayEquals(program, decoded.program);
        assertEquals(Bech32.Variant.BECH32, decoded.variant);
    }

    // Decode the canonical BIP173 reference address, verify shape
    @Test
    public void bech32Decode_btcReferenceAddress() {
        // bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4 is a known BIP173 address
        Bech32.DecodedBech32 decoded = Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4");
        assertEquals("bc", decoded.hrp);
        assertEquals(0, decoded.witnessVersion);
        assertEquals(20, decoded.program.length);
        assertEquals(Bech32.Variant.BECH32, decoded.variant);
        // Re-encode must reproduce the original address
        String reEncoded = Bech32.encode("bc", 0, decoded.program);
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", reEncoded);
    }

    // BIP350: bech32m for witness version 1 (Taproot)
    @Test
    public void bech32mEncodeDecode_roundTrip_p2tr() {
        byte[] prog32 = hexToBytes("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        String encoded = Bech32.encode("bc", 1, prog32);
        assertTrue("P2TR must start with bc1p", encoded.startsWith("bc1p"));

        Bech32.DecodedBech32 decoded = Bech32.decode(encoded);
        assertEquals("bc", decoded.hrp);
        assertEquals(1, decoded.witnessVersion);
        assertArrayEquals(prog32, decoded.program);
        assertEquals(Bech32.Variant.BECH32M, decoded.variant);
    }

    @Test
    public void ltcBech32RoundTrip() {
        byte[] program = hexToBytes("aabbccddeeff00112233445566778899aabbccdd");
        String encoded = Bech32.encode("ltc", 0, program);
        assertTrue(encoded.startsWith("ltc1q"));
        Bech32.DecodedBech32 decoded = Bech32.decode(encoded);
        assertEquals("ltc", decoded.hrp);
        assertArrayEquals(program, decoded.program);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidBech32_badChecksum() {
        Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"); // last char changed
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidBech32_noSeparator() {
        Bech32.decode("notabech32string");
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
