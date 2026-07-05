package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.BitSendRequest;
import com.google.common.collect.ImmutableList;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    /** The external index-0 key of a branch. */
    DeterministicKey branchKey(List<org.bitcoinj.crypto.ChildNumber> accPath) {
        SimpleHDKeyChain kc = new SimpleHDKeyChain(hierarchy.get(accPath, false, true));
        return kc.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
    }

    @Test
    public void findsKeysAndOwnershipAcrossAllBranches() throws Exception {
        WalletPocketHD acct = bundled(BTC, 7);
        acct.maybeInitializeAllKeys();

        DeterministicKey k44 = branchKey(BTC.getBip44Path(7));
        DeterministicKey k49 = branchKey(BTC.getBip49Path(7));
        DeterministicKey k84 = branchKey(BTC.getBip84Path(7));

        // Key lookup must reach every branch, not just the receive (native) branch.
        assertNotNull("legacy 44' key not found", acct.findKeyFromPubHash(k44.getPubKeyHash()));
        assertNotNull("P2SH 49' key not found", acct.findKeyFromPubHash(k49.getPubKeyHash()));
        assertNotNull("native 84' key not found", acct.findKeyFromPubHash(k84.getPubKeyHash()));
        assertNotNull("findKeyFromPubKey missed 44'", acct.findKeyFromPubKey(k44.getPubKey()));

        // Ownership checks for all three script types.
        BitAddress p2sh = (BitAddress) BTC.newAddress(
                expected(BTC, BTC.getBip49Path(7), AddressType.COMPATIBLE));
        assertTrue("P2SH script not recognised as mine",
                acct.isPayToScriptHashMine(p2sh.getHash160()));
        assertTrue(acct.isAddressMine(BTC.newAddress(
                expected(BTC, BTC.getBip44Path(7), AddressType.LEGACY))));
        assertTrue(acct.isAddressMine(BTC.newAddress(
                expected(BTC, BTC.getBip49Path(7), AddressType.COMPATIBLE))));
        assertTrue(acct.isAddressMine(BTC.newAddress(
                expected(BTC, BTC.getBip84Path(7), AddressType.NATIVE_SEGWIT))));
    }

    @Test
    public void markingP2shAddressAdvancesOnlyItsOwnBranch() throws Exception {
        WalletPocketHD acct = bundled(BTC, 7);
        acct.maybeInitializeAllKeys();
        List<SimpleHDKeyChain> kcs = acct.getKeychains(); // [44', 49', 84']
        assertEquals(0, kcs.get(1).getNumIssuedExternalKeys());

        // Mark the 49' branch's index-0 P2SH address used.
        acct.markAddressAsUsed(BTC.newAddress(
                expected(BTC, BTC.getBip49Path(7), AddressType.COMPATIBLE)));

        assertEquals("49' branch should have advanced",
                1, acct.getKeychains().get(1).getNumIssuedExternalKeys());
        assertEquals("44' branch should be untouched",
                0, acct.getKeychains().get(0).getNumIssuedExternalKeys());
        assertEquals("84' branch should be untouched",
                0, acct.getKeychains().get(2).getNumIssuedExternalKeys());
    }

    @Test
    public void spendsUtxoOnP2shBranch() throws Exception {
        WalletPocketHD acct = bundled(BTC, 7);
        acct.maybeInitializeAllKeys();

        // Fund the 49' P2SH-segwit index-0 address.
        BitAddress p2sh = (BitAddress) BTC.newAddress(
                expected(BTC, BTC.getBip49Path(7), AddressType.COMPATIBLE));
        Transaction funding = new Transaction(BTC);
        funding.addOutput(BTC.oneCoin().toCoin(), p2sh);
        funding.getConfidence().setSource(TransactionConfidence.Source.SELF);
        acct.addNewTransactionIfNeeded(funding);
        assertEquals(BTC.value("1"), acct.getBalance());

        // Spend it to an external address.
        BitAddress dest = BitAddress.from(BTC, "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2");
        BitSendRequest req = acct.sendCoinsOffline(dest, BTC.value("0.5"));
        req.feePerTxSize = BTC.value("0.0001");
        acct.completeAndSignTx(req);

        // The P2SH-P2WPKH input is signed via the witness [signature, pubkey]. If the
        // 49' key had not been found across branches, no witness would be produced.
        assertNotNull("no witness data — signing key not found across branches",
                req.tx.getWitnessData());
        assertFalse("no witness produced for the P2SH input",
                req.tx.getWitnessData().isEmpty());
        byte[][] witness = req.tx.getWitnessData().get(0);
        assertNotNull("input 0 has no witness", witness);
        assertEquals("witness must carry signature + pubkey", 2, witness.length);
    }
}
