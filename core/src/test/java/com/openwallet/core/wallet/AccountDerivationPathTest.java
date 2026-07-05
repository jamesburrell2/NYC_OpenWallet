package com.openwallet.core.wallet;

import com.google.common.collect.ImmutableList;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.CoinType;

import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Test;

import java.util.List;

import static com.openwallet.core.Preconditions.checkNotNull;
import static org.junit.Assert.assertEquals;

/**
 * The Account Details screen must display the account's BIP32 derivation path
 * (e.g. m/84'/0'/7' for the "StampHash" native-SegWit account) so it can be
 * compared against other wallets that hold the same keys.
 */
public class AccountDerivationPathTest {

    static final CoinType BTC = BitcoinMain.get();
    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale",
            "nurse", "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    private WalletPocketHD accountAt(List<org.bitcoinj.crypto.ChildNumber> path) {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey masterKey =
                HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        DeterministicHierarchy hierarchy = new DeterministicHierarchy(masterKey);
        DeterministicKey rootKey = hierarchy.get(path, false, true);
        return new WalletPocketHD(rootKey, BTC, null, null);
    }

    @Test
    public void nativeSegwitAccountPath() {
        assertEquals("m/84'/0'/7'", accountAt(BTC.getBip84Path(7)).getDerivationPath());
    }

    @Test
    public void legacyAccountPath() {
        assertEquals("m/44'/0'/0'", accountAt(BTC.getBip44Path(0)).getDerivationPath());
    }
}
