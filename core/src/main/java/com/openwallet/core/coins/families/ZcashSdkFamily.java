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

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        if (addressStr == null || addressStr.isEmpty()) {
            throw new AddressMalformedException("ZEC address must not be empty");
        }
        return new ZcashSdkAddress(this, addressStr);
    }
}
