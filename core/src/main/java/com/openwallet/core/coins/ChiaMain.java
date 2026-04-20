package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ChiaFamily;

/**
 * Chia (XCH) — proof-of-space-and-time blockchain.
 * Uses 12 decimal places (mojos), BIP-44 coin type 8444, and bech32m addresses (xch1...).
 */
public class ChiaMain extends ChiaFamily {

    private ChiaMain() {
        id = "chia.main";

        name = "Chia";
        symbol = "XCH";
        uriScheme = "chia";
        bip44Index = 8444;
        unitExponent = 12;
        addressPrefix = "xch1";
        feeValue = value(0); // Chia fees are optional
        minNonDust = value(1);
        feePolicy = FeePolicy.FLAT_FEE;
    }

    private static ChiaMain instance = new ChiaMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
