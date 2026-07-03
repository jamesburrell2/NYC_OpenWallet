package com.openwallet.core.wallet;

import com.openwallet.core.protos.Protos;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The 64-byte BIP-39 seed handed to the Zcash SDK must be identical before and
 * after a wallet save/load round-trip. Before this fix, readWallet reconstructed
 * the seed as {@code new DeterministicSeed(new byte[16], mnemonic, 0)}, so
 * getSeedBytes() returned a 16-byte dummy array after every reload — the ZEC
 * backend would have initialized from a constant, publicly-known seed.
 */
public class WalletSeedRoundTripTest {
    private static final List<String> MNEMONIC = Arrays.asList(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about");

    @Test
    public void seedBytesSurviveSaveLoadRoundTrip() throws Exception {
        Wallet original = new Wallet(MNEMONIC);
        byte[] originalSeed = original.getSeedBytes();
        assertNotNull(originalSeed);
        assertEquals("BIP-39 seed must be 64 bytes", 64, originalSeed.length);

        Protos.Wallet proto = WalletProtobufSerializer.toProtobuf(original);
        Wallet reloaded = WalletProtobufSerializer.readWallet(proto);

        byte[] reloadedSeed = reloaded.getSeedBytes();
        assertNotNull("seed must be accessible after reload", reloadedSeed);
        assertEquals("seed must still be 64 bytes after reload", 64, reloadedSeed.length);
        assertArrayEquals("seed must be identical after reload", originalSeed, reloadedSeed);
    }

    @Test
    public void mnemonicSurvivesRoundTrip() throws Exception {
        Wallet original = new Wallet(MNEMONIC);
        Protos.Wallet proto = WalletProtobufSerializer.toProtobuf(original);
        Wallet reloaded = WalletProtobufSerializer.readWallet(proto);
        assertEquals(MNEMONIC, reloaded.getMnemonicCode());
    }
}
