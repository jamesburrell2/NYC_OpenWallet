package com.openwallet.core.coins;

import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ZcashSdkAddressValidationTest {
    private final CoinType type = ZcashMain.get();

    @Test
    public void acceptsTransparentP2PKH() throws Exception {
        AbstractAddress a = type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi");
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi", a.toString());
    }

    @Test
    public void acceptsSaplingAddress() throws Exception {
        // structurally valid: zs1 + 75 bech32 chars = 78 total
        String zs = "zs1" + repeat("q", 75);
        assertEquals(zs, type.newAddress(zs).toString());
    }

    @Test
    public void acceptsUnifiedAddress() throws Exception {
        String ua = "u1" + repeat("q", 100);
        assertEquals(ua, type.newAddress(ua).toString());
    }

    @Test
    public void trimsWhitespace() throws Exception {
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi",
                type.newAddress("  t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi\n").toString());
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsEmpty() throws Exception { type.newAddress("   "); }

    @Test(expected = AddressMalformedException.class)
    public void rejectsBitcoinAddress() throws Exception {
        type.newAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa");
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsShortTransparent() throws Exception { type.newAddress("t1abc"); }

    @Test(expected = AddressMalformedException.class)
    public void rejectsBadBase58InTransparent() throws Exception {
        // 'O' is not in the Base58 alphabet; length stays a valid 35
        type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhOi");
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsUppercaseInBech32() throws Exception {
        type.newAddress("zs1" + repeat("Q", 75));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
