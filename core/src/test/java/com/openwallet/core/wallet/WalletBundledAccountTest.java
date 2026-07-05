package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.utils.BriefLogFormatter;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Part B4: {@link Wallet#createBundledBitcoinAccount} creates a single account bundling the
 * three Coinomi branches (44'/49'/84') at a chosen account index.
 */
public class WalletBundledAccountTest {
    static final CoinType BTC = BitcoinMain.get();
    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever",
            "scale", "nurse", "brief", "round", "ski", "fiction", "car",
            "fitness", "pluck", "act");

    Wallet wallet;

    @Before
    public void setup() throws Exception {
        BriefLogFormatter.init();
        wallet = new Wallet(MNEMONIC);
    }

    @Test
    public void createsBundledBitcoinAccountAtIndex() {
        WalletAccount account = wallet.createBundledBitcoinAccount(BTC, 7, null);
        assertTrue(account instanceof WalletPocketHD);
        WalletPocketHD pocket = (WalletPocketHD) account;

        assertTrue("account must be bundled", pocket.isBundled());
        assertEquals("three branches", 3, pocket.getKeychains().size());
        assertEquals("shared account index", 7, pocket.getAccountIndex());

        // Roots at 44'/0'/7', 49'/0'/7', 84'/0'/7'.
        List<SimpleHDKeyChain> kcs = pocket.getKeychains();
        assertEquals(44, kcs.get(0).getRootKey().getPath().get(0).num());
        assertEquals(49, kcs.get(1).getRootKey().getPath().get(0).num());
        assertEquals(84, kcs.get(2).getRootKey().getPath().get(0).num());
        for (SimpleHDKeyChain kc : kcs) {
            List<ChildNumber> path = kc.getRootKey().getPath();
            assertEquals("account index in path", 7, path.get(path.size() - 1).num());
        }

        // The created account is registered in the wallet.
        assertTrue(wallet.isAccountExists(pocket.getId()));
    }
}
