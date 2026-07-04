package com.openwallet.core.coins;

import com.openwallet.core.wallet.AbstractAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZclassicMainTest {
    private final CoinType type = ZclassicMain.get();

    @Test
    public void parametersMatchZclassic() {
        assertEquals("zclassic.main", type.getId());
        assertEquals(147, (int) type.getBip44Index());
        assertEquals(0x1CB8, (int) type.getAddressHeader());
        assertEquals(0x1CBD, (int) type.getP2SHHeader());
        assertEquals("ZCL", type.getSymbol());
        assertEquals("zclassic", type.getUriScheme());
    }

    @Test
    public void parsesRealTransparentAddress() throws Exception {
        // A ZCL t1-address (same 0x1CB8 prefix as ZEC t1). Round-trips through ZcashAddress.
        AbstractAddress a = type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi");
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi", a.toString());
    }

    @Test
    public void registeredInCoinId() {
        assertTrue(CoinID.typeFromId("zclassic.main") instanceof ZclassicMain);
    }
}
