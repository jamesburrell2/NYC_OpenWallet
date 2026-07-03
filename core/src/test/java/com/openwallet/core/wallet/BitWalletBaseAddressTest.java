package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.utils.BriefLogFormatter;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.openwallet.core.Preconditions.checkNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the crash reported in device QA: rendering a transaction list
 * containing SegWit (P2WPKH) addresses threw IllegalArgumentException from
 * BitAddressUtils.isP2SHAddress because SegwitAddress is not a BitAddress.
 *
 * BitWalletBase.isAddressMine must special-case SegwitAddress instead of routing it
 * through the BitAddress-only helpers.
 */
public class BitWalletBaseAddressTest {
    static final CoinType BTC = BitcoinMain.get();
    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale", "nurse",
            "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    WalletPocketHD pocket;

    @Before
    public void setup() {
        BriefLogFormatter.init();

        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey masterKey = HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        DeterministicHierarchy hierarchy = new DeterministicHierarchy(masterKey);
        DeterministicKey rootKey = hierarchy.get(BTC.getBip44Path(0), false, true);

        pocket = new WalletPocketHD(rootKey, BTC, null, null);
    }

    @Test
    public void isAddressMine_segwitAddress_doesNotThrow() {
        SegwitAddress foreign = SegwitAddress.fromHash160(BTC, new byte[20]);
        // Must not throw IllegalArgumentException("This address cannot be a P2SH address")
        boolean mine = pocket.isAddressMine(foreign);
        assertFalse("A segwit address for an unrelated hash160 should not be considered ours", mine);
    }

    @Test
    public void isAddressMine_segwitAddress_matchingOwnKey_returnsTrue() {
        ECKey key = pocket.keys.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        SegwitAddress own = SegwitAddress.fromKey(BTC, key);

        assertTrue("A segwit address derived from the wallet's own key should be recognized as ours",
                pocket.isAddressMine(own));
    }

    @Test
    public void isAddressMine_segwitAddress_wrongCoinType_returnsFalse() {
        // Different coin type than the pocket -> type mismatch branch, must still not throw
        SegwitAddress wrongType = SegwitAddress.fromHash160(
                com.openwallet.core.coins.LitecoinMain.get(), new byte[20]);
        assertFalse(pocket.isAddressMine(wrongType));
    }
}
