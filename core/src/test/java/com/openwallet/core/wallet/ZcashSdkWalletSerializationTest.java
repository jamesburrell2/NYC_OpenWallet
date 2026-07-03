package com.openwallet.core.wallet;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.protos.Protos;
import com.openwallet.core.wallet.families.zcash.ZcashSdkWallet;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Zcash accounts must survive a wallet save/load round-trip. Before this fix,
 * WalletProtobufSerializer silently skipped ZcashSdkWallet pockets on save
 * (and on load), so the account vanished on every app restart.
 */
public class ZcashSdkWalletSerializationTest {
    private static final List<String> MNEMONIC = java.util.Arrays.asList(
            "abandon", "abandon", "abandon", "abandon", "abandon", "abandon",
            "abandon", "abandon", "abandon", "abandon", "abandon", "about");

    private final CoinType type = ZcashMain.get();

    private Wallet newWalletWithZec() throws Exception {
        Wallet wallet = new Wallet(MNEMONIC);
        wallet.createAccounts(java.util.Collections.singletonList(type), false, null);
        return wallet;
    }

    @Test
    public void zecAccountSurvivesRoundTrip() throws Exception {
        Wallet wallet = newWalletWithZec();
        WalletAccount original = wallet.getAccounts(type).get(0);
        assertTrue(original instanceof ZcashSdkWallet);
        String origId = original.getId();
        String origAddress = original.getReceiveAddress().toString();
        assertFalse("fixture should derive a t-address", origAddress.isEmpty());

        Protos.Wallet proto = WalletProtobufSerializer.toProtobuf(wallet);
        Wallet reloaded = WalletProtobufSerializer.readWallet(proto);

        List<WalletAccount> zecAccounts = reloaded.getAccounts(type);
        assertEquals("ZEC account must survive save/load", 1, zecAccounts.size());
        WalletAccount restored = zecAccounts.get(0);
        assertTrue(restored instanceof ZcashSdkWallet);
        assertEquals(origId, restored.getId());
        assertEquals(origAddress, restored.getReceiveAddress().toString());
        assertNotNull(restored.getCoinType());
        assertEquals(type, restored.getCoinType());
    }

    @Test
    public void otherStubFamiliesAreStillSkipped() throws Exception {
        // Regression guard: the serializer change must not break wallets with
        // only Bit-family accounts (normal path).
        Wallet wallet = new Wallet(MNEMONIC);
        wallet.createAccounts(java.util.Collections.<CoinType>singletonList(
                com.openwallet.core.coins.BitcoinMain.get()), false, null);
        Protos.Wallet proto = WalletProtobufSerializer.toProtobuf(wallet);
        Wallet reloaded = WalletProtobufSerializer.readWallet(proto);
        assertEquals(1, reloaded.getAllAccounts().size());
    }
}
