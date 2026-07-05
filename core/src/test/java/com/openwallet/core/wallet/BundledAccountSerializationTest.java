package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.protos.Protos;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.openwallet.core.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Part B3: a bundled pocket must survive a protobuf round-trip with all three branches
 * intact, AND an already-saved single-path pocket (old format) must still load as a
 * 1-keychain pocket with identical addresses (Global Constraint: behave identically).
 */
public class BundledAccountSerializationTest {
    static final CoinType BTC = BitcoinMain.get();
    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever",
            "scale", "nurse", "brief", "round", "ski", "fiction", "car",
            "fitness", "pluck", "act");

    DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
    DeterministicKey masterKey =
            HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
    DeterministicHierarchy hierarchy = new DeterministicHierarchy(masterKey);

    @Before
    public void setup() {
        BriefLogFormatter.init();
    }

    WalletPocketHD bundled(CoinType coin, int index) {
        List<SimpleHDKeyChain> keychains = new ArrayList<>();
        keychains.add(new SimpleHDKeyChain(hierarchy.get(coin.getBip44Path(index), false, true)));
        keychains.add(new SimpleHDKeyChain(hierarchy.get(coin.getBip49Path(index), false, true)));
        keychains.add(new SimpleHDKeyChain(hierarchy.get(coin.getBip84Path(index), false, true)));
        List<AddressType> purposes = ImmutableList.of(
                AddressType.LEGACY, AddressType.COMPATIBLE, AddressType.NATIVE_SEGWIT);
        WalletPocketHD pocket = new WalletPocketHD(keychains, purposes, coin, null, null);
        for (SimpleHDKeyChain kc : pocket.getKeychains()) kc.setLookaheadSize(5);
        pocket.maybeInitializeAllKeys();
        return pocket;
    }

    static Set<String> activeStrings(WalletPocketHD p) {
        Set<String> s = new HashSet<>();
        for (AbstractAddress a : p.getActiveAddresses()) s.add(a.toString());
        return s;
    }

    @Test
    public void bundledPocketRoundTrips() throws Exception {
        WalletPocketHD original = bundled(BTC, 7);
        Set<String> before = activeStrings(original);

        Protos.WalletPocket proto = original.toProtobuf();
        WalletPocketHD restored = new WalletPocketProtobufSerializer().readWallet(proto, null);

        assertEquals("all three branches must survive", 3, restored.getKeychains().size());
        assertEquals(original.getId(), restored.getId());
        assertEquals("active addresses must match after round-trip",
                before, activeStrings(restored));
    }

    @Test
    public void singlePathPocketLoadsUnchanged() throws Exception {
        // Old-format pocket: a single keychain at a custom native-segwit path.
        WalletPocketHD original =
                new WalletPocketHD(hierarchy.get(BTC.getBip84Path(7), false, true), BTC, null, null);
        original.keys.setLookaheadSize(5);
        original.maybeInitializeAllKeys();
        Set<String> before = activeStrings(original);

        Protos.WalletPocket proto = original.toProtobuf();
        WalletPocketHD restored = new WalletPocketProtobufSerializer().readWallet(proto, null);

        assertEquals("must load as a single keychain", 1, restored.getKeychains().size());
        assertFalse("must not be treated as bundled", restored.isBundled());
        assertEquals(original.getId(), restored.getId());
        assertEquals(before, activeStrings(restored));
    }
}
