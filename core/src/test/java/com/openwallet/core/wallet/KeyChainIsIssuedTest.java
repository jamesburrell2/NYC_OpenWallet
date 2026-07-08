package com.openwallet.core.wallet;

import com.openwallet.core.coins.NewYorkCoinMain;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class KeyChainIsIssuedTest {

    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale",
            "nurse", "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    private SimpleHDKeyChain nycChain() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        DeterministicHierarchy h = new DeterministicHierarchy(master);
        return new SimpleHDKeyChain(h.get(NewYorkCoinMain.get().getBip44Path(0), false, true));
    }

    @Test
    public void issuedKeyIsIssuedUnusedIsNot() throws Exception {
        SimpleHDKeyChain kc = nycChain();
        DeterministicKey issued = kc.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS); // issues external index 0
        assertTrue("issued index-0 key must report issued", kc.isIssued(issued));

        DeterministicKey unused = kc.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS); // index 1
        assertFalse("next unused key must report not-issued", kc.isIssued(unused));
    }
}
