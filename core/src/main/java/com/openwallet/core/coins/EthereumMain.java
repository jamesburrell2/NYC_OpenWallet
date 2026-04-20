package com.openwallet.core.coins;

import com.openwallet.core.coins.families.EvmFamily;

/**
 * Ethereum (ETH) — the primary EVM chain.
 * Uses 18 decimal places (wei), BIP-44 coin type 60, and 0x-prefixed addresses.
 */
public class EthereumMain extends EvmFamily {

    private EthereumMain() {
        id = "ethereum.main";

        name = "Ethereum";
        symbol = "ETH";
        uriScheme = "ethereum";
        bip44Index = 60;
        unitExponent = 18;
        addressPrefix = "0x";
        feeValue = value(21000000000000L); // 21000 gwei (typical gas limit * gas price)
        minNonDust = value(1);
        feePolicy = FeePolicy.FEE_PER_KB;
    }

    private static EthereumMain instance = new EthereumMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
