package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class NycSegwitRevealTest {

    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale",
            "nurse", "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    private final CoinType nyc = NewYorkCoinMain.get();
    private DeterministicHierarchy hierarchy() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        return new DeterministicHierarchy(master);
    }
    private WalletPocketHD nycPocket() throws Exception {
        WalletPocketHD p = new WalletPocketHD(
                hierarchy().get(nyc.getBip44Path(0), false, true), nyc, null, null);
        p.maybeInitializeAllKeys();
        return p;
    }
    private Set<String> addrs(WalletPocketHD p) {
        Set<String> s = new HashSet<>();
        for (AbstractAddress a : p.getActiveAddresses()) s.add(a.toString());
        return s;
    }

    @Test
    public void belowActivationNoSegwitForUnusedKeys() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_499_999);
        Set<String> a = addrs(p);
        assertTrue("must still emit legacy addresses", a.stream().anyMatch(x -> x.startsWith("R")));
        assertTrue("no bech32 before activation", a.stream().noneMatch(x -> x.startsWith("nyc1")));
    }

    @Test
    public void atActivationSegwitRevealed() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_500_000);
        Set<String> a = addrs(p);
        assertTrue("bech32 revealed at activation", a.stream().anyMatch(x -> x.startsWith("nyc1")));
    }

    @Test
    public void belowActivationKeepsWatchingUsedSegwitAddress() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_499_999);

        // Derive index-0 external key; its legacy + native-segwit addresses share that key.
        SimpleHDKeyChain probe = new SimpleHDKeyChain(hierarchy().get(nyc.getBip44Path(0), false, true));
        DeterministicKey k0 = probe.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        String legacy0 = nyc.addressFromKey(k0, AddressType.LEGACY).toString();
        String segwit0 = nyc.addressFromKey(k0, AddressType.NATIVE_SEGWIT).toString();

        // Mark the index-0 address used (bumps the issued counter for key 0).
        AbstractAddress legacyAddr = null;
        for (AbstractAddress a : p.getActiveAddresses()) {
            if (a.toString().equals(legacy0)) { legacyAddr = a; break; }
        }
        assertNotNull("index-0 legacy address must be active", legacyAddr);
        p.markAddressAsUsed(legacyAddr);

        // Even below activation, the used key's segwit sibling stays watched.
        assertTrue("used key's segwit address must remain watched",
                addrs(p).contains(segwit0));
    }
}
