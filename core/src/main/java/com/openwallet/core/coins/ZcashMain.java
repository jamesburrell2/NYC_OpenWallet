package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ZcashSdkFamily;

public class ZcashMain extends ZcashSdkFamily {
    private ZcashMain() {
        id = "zcash.main";

        // 0x1CB8 → t1... (P2PKH); 0x1CBD → t3... (P2SH)
        // Stored as int; ZcashFamily/ZcashAddress handle 2-byte split.
        // NOT passed to VersionedChecksummedBytes.
        addressHeader = 7352;         // 0x1CB8
        p2shHeader = 7357;            // 0x1CBD
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 128; // 0x80

        addressPrefix = "";
        name = "Zcash";
        symbol = "ZEC";
        uriScheme = "zcash";
        bip44Index = 133;
        unitExponent = 8;
        feeValue = value(1000);
        minNonDust = value(1000);
        softDustLimit = value(1000);
        softDustPolicy = SoftDustPolicy.BASE_FEE_FOR_EACH_SOFT_DUST_TXO;
        signedMessageHeader = toBytes("Zcash Signed Message:\n");
    }

    private static ZcashMain instance = new ZcashMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
