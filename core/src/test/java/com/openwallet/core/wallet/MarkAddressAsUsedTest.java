package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import org.bitcoinj.core.ECKey;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies that markAddressAsUsed() accepts SegwitAddress without throwing.
 * We test the type-dispatch logic by subclassing WalletPocketHD is not practical
 * (it requires a full wallet setup), so we verify directly that SegwitAddress
 * has a getHash160() method returning the expected 20-byte value — the
 * prerequisite for the markAddressAsUsed fix to work correctly.
 */
public class MarkAddressAsUsedTest {

    private static final CoinType BTC = BitcoinMain.get();

    @Test
    public void segwitAddress_getHash160_returns20Bytes() {
        ECKey key = new ECKey();
        SegwitAddress addr = SegwitAddress.fromKey(BTC, key);
        byte[] hash160 = addr.getHash160();
        assertNotNull(hash160);
        assertEquals("hash160 must be 20 bytes", 20, hash160.length);
        assertArrayEquals("hash160 must match key.getPubKeyHash()",
                key.getPubKeyHash(), hash160);
    }

    @Test
    public void segwitAddress_hash160_matchesBitAddressHash160_forSameKey() {
        ECKey key = new ECKey();
        SegwitAddress segwit = SegwitAddress.fromKey(BTC, key);
        BitAddress legacy = BitAddress.from(BTC, key);
        assertArrayEquals(
            "SegwitAddress and BitAddress must share the same hash160 for the same key",
            legacy.getHash160(), segwit.getHash160());
    }
}
