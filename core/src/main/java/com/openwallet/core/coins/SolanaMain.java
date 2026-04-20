package com.openwallet.core.coins;

import com.openwallet.core.coins.families.SolanaFamily;

/**
 * Solana (SOL) — high-performance blockchain.
 * Uses 9 decimal places (lamports), BIP-44 coin type 501, and base58 addresses.
 */
public class SolanaMain extends SolanaFamily {

    private SolanaMain() {
        id = "solana.main";

        name = "Solana";
        symbol = "SOL";
        uriScheme = "solana";
        bip44Index = 501;
        unitExponent = 9;
        addressPrefix = "";
        feeValue = value(5000); // 5000 lamports (typical priority fee)
        minNonDust = value(1);
        feePolicy = FeePolicy.FLAT_FEE;
    }

    private static SolanaMain instance = new SolanaMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
