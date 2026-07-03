package com.openwallet.core.crypto;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class Blake2bTest {
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    @Test
    public void unkeyedEmptyInput512() {
        // RFC 7693 appendix / official BLAKE2b vector: BLAKE2b-512("")
        Blake2b d = new Blake2b(64, null);
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
            "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(d.digest()));
    }

    @Test
    public void unkeyedAbc512() {
        // BLAKE2b-512("abc")
        Blake2b d = new Blake2b(64, null);
        d.update("abc".getBytes(), 0, 3);
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
            "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(d.digest()));
    }

    @Test
    public void personalizedDigest32() {
        // Personalization changes the output; verify determinism + length.
        byte[] person = "ZcashSigHash0000".getBytes(); // 16 bytes
        Blake2b a = new Blake2b(32, person);
        Blake2b b = new Blake2b(32, person);
        a.update(new byte[]{1,2,3}, 0, 3);
        b.update(new byte[]{1,2,3}, 0, 3);
        assertEquals(hex(a.digest()), hex(b.digest()));
        assertEquals(64, hex(a.digest()).length());
    }
}
