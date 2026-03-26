package com.openwallet.core.coins;

import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Test;

import static org.junit.Assert.*;

public class NewYorkCoinTest {

    // Standard BIP39 test mnemonic (all-zeros entropy)
    static final String MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void addressStartsWithN() throws MnemonicException, Exception {
        CoinType nyc = NewYorkCoinMain.get();
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
        DeterministicHierarchy h = new DeterministicHierarchy(master);
        DeterministicKey accountKey = h.get(nyc.getBip44Path(0), false, true);
        DeterministicKey receiveKey = HDKeyDerivation.deriveChildKey(accountKey, 0);
        DeterministicKey addrKey = HDKeyDerivation.deriveChildKey(receiveKey, 0);

        String address = nyc.addressFromKey(addrKey).toString();
        assertTrue("NYC address must start with N, got: " + address,
                address.startsWith("N"));
    }

    @Test
    public void coinIdEndsWithMain() {
        assertEquals("newyorkcoin.main", NewYorkCoinMain.get().getId());
    }

    @Test
    public void bip44IndexIs179() {
        assertEquals(179, NewYorkCoinMain.get().getBip44Index());
    }

    @Test
    public void feeIsZero() {
        assertEquals(0L, NewYorkCoinMain.get().getFeeValue().value);
    }

    @Test
    public void singletonReturnsSameInstance() {
        assertSame(NewYorkCoinMain.get(), NewYorkCoinMain.get());
    }
}
