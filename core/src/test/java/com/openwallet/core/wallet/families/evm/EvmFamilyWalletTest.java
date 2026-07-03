package com.openwallet.core.wallet.families.evm;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.EthereumMain;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.junit.Test;

public class EvmFamilyWalletTest {
    @Test(expected = UnsupportedOperationException.class)
    public void keyBasedConstructorFailsClosedUntilKeccakExists() {
        CoinType type = EthereumMain.get();
        DeterministicKey key = HDKeyDerivation.createMasterPrivateKey(new byte[32]);
        new EvmFamilyWallet(type, "ethereum.main:0", key);
    }
}
