package com.openwallet.core.coins;

import com.openwallet.core.coins.families.BitFamily;

public class NewYorkCoinMain extends BitFamily {
    private NewYorkCoinMain() {
        id = "newyorkcoin.main";

        addressHeader = 52;           // 0x34 → N... prefix
        p2shHeader = 22;              // 0x16
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 188; // 0xBC

        addressPrefix = "";
        name = "NewYorkCoin";
        symbol = "NYC";
        uriScheme = "newyorkcoin";
        bip44Index = 179;             // SLIP-0044 confirmed
        unitExponent = 8;
        feeValue = value(0);          // NYC is fee-free
        feePolicy = FeePolicy.FLAT_FEE;
        minNonDust = value(0);
        softDustLimit = value(0);
        softDustPolicy = SoftDustPolicy.NO_POLICY;
        signedMessageHeader = toBytes("NewYorkCoin Signed Message:\n");
    }

    private static NewYorkCoinMain instance = new NewYorkCoinMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
