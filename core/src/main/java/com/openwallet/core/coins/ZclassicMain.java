package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ZcashFamily;

/**
 * Zclassic (ZCL) — a transparent-only Equihash Zcash fork on the ElectrumX stack
 * (no lightwalletd / mobile SDK exists, so unlike ZEC there is no shielded path).
 * Rides {@link ZcashFamily} for 2-byte t1/t3 Base58Check addresses and the
 * WalletPocketHD gap-limit discovery.
 *
 * Parameters verified against github.com/ZclassicCommunity/zclassic chainparams.cpp
 * (2026-07-04): PUBKEY_ADDRESS 0x1CB8 (t1), SCRIPT_ADDRESS 0x1CBD (t3), bip44 147.
 * Sapling consensus branch id 0x76B809BB (see ZcashTxSigner.BRANCH_ID_ZCL_SAPLING).
 */
public class ZclassicMain extends ZcashFamily {
    private ZclassicMain() {
        id = "zclassic.main";

        addressHeader = 7352;         // 0x1CB8 -> t1 (P2PKH)
        p2shHeader = 7357;            // 0x1CBD -> t3 (P2SH)
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 128;

        addressPrefix = "";
        name = "Zclassic";
        symbol = "ZCL";
        uriScheme = "zclassic";
        bip44Index = 147;
        unitExponent = 8;
        feeValue = value(10000);
        minNonDust = value(1000);
        softDustLimit = value(1000);
        softDustPolicy = SoftDustPolicy.BASE_FEE_FOR_EACH_SOFT_DUST_TXO;
        signedMessageHeader = toBytes("Zcash Signed Message:\n");
    }

    private static ZclassicMain instance = new ZclassicMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
