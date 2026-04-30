package com.openwallet.core.coins;

import com.openwallet.core.coins.families.EvmFamily;

/**
 * BNB Smart Chain (BNB) — EVM-compatible chain with chainId 56.
 * Uses 18 decimal places (wei), BIP-44 coin type 60 (shared with ETH per convention),
 * and 0x-prefixed addresses identical to Ethereum.
 */
public class BnbSmartChainMain extends EvmFamily {

    private BnbSmartChainMain() {
        id = "bnbsmartchain.main";

        name = "BNB Smart Chain";
        symbol = "BNB";
        uriScheme = "bnb";
        bip44Index = 60; // BNB Smart Chain uses coin type 60 (same as ETH) by convention
        unitExponent = 18;
        addressPrefix = "0x";
        feeValue = value(21000000000000L); // 21000 gwei (standard transfer gas * typical gas price)
        minNonDust = value(1);
        feePolicy = FeePolicy.FEE_PER_KB;
    }

    private static BnbSmartChainMain instance = new BnbSmartChainMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
