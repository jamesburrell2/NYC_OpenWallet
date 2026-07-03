package com.openwallet.core.coins;

import com.openwallet.core.wallet.AbstractAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZcashTransparentMainTest {
    private final CoinType type = ZcashTransparentMain.get();

    @Test
    public void parametersMatchZcashTransparentLayer() {
        assertEquals("zcashtransparent.main", type.getId());
        assertEquals(133, (int) type.getBip44Index());
        assertEquals(0x1CB8, (int) type.getAddressHeader());
        assertEquals(0x1CBD, (int) type.getP2SHHeader());
        assertEquals("ZEC", type.getSymbol());
    }

    @Test
    public void parsesRealTransparentAddress() throws Exception {
        AbstractAddress a = type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi");
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi", a.toString());
    }

    @Test
    public void registeredInCoinId() {
        assertTrue(CoinID.typeFromId("zcashtransparent.main") instanceof ZcashTransparentMain);
    }
}
