package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ZcashFamily;

/**
 * Zcash's TRANSPARENT layer as a Bitcoin-family coin, used as a hidden companion
 * account to the SDK-backed ZEC account. Riding ZcashFamily (extends BitFamily)
 * gives it the full ElectrumX stack — including WalletPocketHD's BIP-44 gap-limit
 * address discovery — so ALL t-addresses with history are found, not just the
 * single t-address the zcash-android-sdk watches.
 *
 * bip44Index deliberately equals ZcashMain's (133): account 0's first external
 * key here derives the exact same t-address the SDK shows, making discovery a
 * strict superset of the SDK's transparent view.
 *
 * NOTE: symbol is intentionally "ZEC", shared with {@link ZcashMain}. CoinID's
 * static initializer enforces symbol uniqueness for its symbolLookup map, so it
 * special-cases this companion type and does NOT register its symbol — leaving
 * typeFromSymbol("ZEC") resolving to the user-facing ZcashMain (SDK) coin. The
 * companion is never surfaced to users directly (not in Constants.SUPPORTED_COINS;
 * hidden from the nav drawer) — it is auto-managed and fused into the ZEC screen.
 */
public class ZcashTransparentMain extends ZcashFamily {
    private ZcashTransparentMain() {
        id = "zcashtransparent.main";

        addressHeader = 7352;         // 0x1CB8 -> t1 (P2PKH); 2-byte, split by ZcashAddress
        p2shHeader = 7357;            // 0x1CBD -> t3 (P2SH)
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 128;

        addressPrefix = "";
        name = "Zcash Transparent";
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

    private static ZcashTransparentMain instance = new ZcashTransparentMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
