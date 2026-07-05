package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.openwallet.core.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Part B: a single Bitcoin account that bundles the three Coinomi derivation
 * branches (44' legacy, 49' P2SH-segwit, 84' native-segwit) under one account
 * index, so it shows the complete history like Coinomi's "account N".
 */
public class BundledAccountTest {
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

    /** Builds a bundled pocket with 44'/49'/84' roots at the given account index. */
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

    /** The external index-0 receive address of a given branch, in the given script type. */
    String expected(CoinType coin, List<org.bitcoinj.crypto.ChildNumber> accPath, AddressType t) {
        SimpleHDKeyChain kc = new SimpleHDKeyChain(hierarchy.get(accPath, false, true));
        DeterministicKey k = kc.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        return coin.addressFromKey(k, t).toString();
    }

    @Test
    public void bundledActiveAddressesCoverAllThreeBranches() {
        WalletPocketHD acct = bundled(BTC, 7);
        Set<String> active = new HashSet<>();
        for (AbstractAddress a : acct.getActiveAddresses()) active.add(a.toString());

        String legacy44 = expected(BTC, BTC.getBip44Path(7), AddressType.LEGACY);
        String p2sh49 = expected(BTC, BTC.getBip49Path(7), AddressType.COMPATIBLE);
        String bech32_84 = expected(BTC, BTC.getBip84Path(7), AddressType.NATIVE_SEGWIT);

        assertTrue("legacy 44' branch missing: " + legacy44, active.contains(legacy44));
        assertTrue("P2SH 49' branch missing: " + p2sh49, active.contains(p2sh49));
        assertTrue("bech32 84' branch missing: " + bech32_84, active.contains(bech32_84));
    }

    @Test
    public void bundledAccountReportsSharedIndex() {
        WalletPocketHD acct = bundled(BTC, 7);
        assertEquals(7, acct.getAccountIndex());
    }
}
