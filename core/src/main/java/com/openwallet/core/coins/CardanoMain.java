package com.openwallet.core.coins;

import com.openwallet.core.coins.families.CardanoFamily;

/**
 * Cardano (ADA) — proof-of-stake blockchain.
 * Uses 6 decimal places (lovelace), BIP-44 coin type 1815, and bech32 addresses (addr1...).
 */
public class CardanoMain extends CardanoFamily {

    private CardanoMain() {
        id = "cardano.main";

        name = "Cardano";
        symbol = "ADA";
        uriScheme = "cardano";
        bip44Index = 1815;
        unitExponent = 6;
        addressPrefix = "addr1";
        feeValue = value(170000); // ~0.17 ADA minimum fee
        minNonDust = value(1000000); // 1 ADA minimum UTXO
        feePolicy = FeePolicy.FLAT_FEE;
    }

    private static CardanoMain instance = new CardanoMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
