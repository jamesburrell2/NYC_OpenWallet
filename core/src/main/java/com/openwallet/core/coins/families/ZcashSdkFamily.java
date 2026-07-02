package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.zcash.ZcashSdkAddress;

/**
 * Marker class for Zcash coins that use the zcash-android-sdk (lightwalletd gRPC).
 * Unlike ZcashFamily (which extends BitFamily / Stratum), this family manages its own
 * connection lifecycle via ZcashBackendDelegate, bypassing ServerClients entirely.
 *
 * Transparent (t-addr), shielded (z-addr), and Unified Addresses (u-addr) are all
 * handled by the underlying SDK synchronizer.
 */
public abstract class ZcashSdkFamily extends CoinType {
    { family = Families.ZCASH_SDK; }

    private static final String BASE58 = "[1-9A-HJ-NP-Za-km-z]+";
    private static final String BECH32 = "[02-9ac-hj-np-z]+";

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        if (addressStr == null) {
            throw new AddressMalformedException("ZEC address must not be empty");
        }
        String addr = addressStr.trim();
        if (addr.isEmpty()) {
            throw new AddressMalformedException("ZEC address must not be empty");
        }
        if (addr.startsWith("t1") || addr.startsWith("t3")) {
            if (addr.length() != 35 || !addr.matches(BASE58)) {
                throw new AddressMalformedException("Invalid Zcash transparent address");
            }
        } else if (addr.startsWith("zs1")) {
            if (addr.length() != 78 || !addr.substring(3).matches(BECH32)) {
                throw new AddressMalformedException("Invalid Zcash Sapling address");
            }
        } else if (addr.startsWith("u1")) {
            if (addr.length() < 40 || !addr.substring(2).matches(BECH32)) {
                throw new AddressMalformedException("Invalid Zcash Unified Address");
            }
        } else {
            throw new AddressMalformedException(
                    "Unrecognized Zcash address: must start with t1, t3, zs1, or u1");
        }
        return new ZcashSdkAddress(this, addr);
    }
}
