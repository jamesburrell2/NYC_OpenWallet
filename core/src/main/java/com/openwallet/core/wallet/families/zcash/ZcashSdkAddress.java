package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.wallet.AbstractAddress;

import java.io.Serializable;

/**
 * Zcash SDK address — wraps any ZEC address string (t-addr, z-addr, or Unified Address).
 * Used by ZcashSdkWallet; does not attempt to parse or validate the string format
 * (the zcash-android-sdk validates at the network layer).
 */
public class ZcashSdkAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType type;
    private final String addressStr;

    public ZcashSdkAddress(CoinType type, String addressStr) {
        this.type = type;
        this.addressStr = addressStr;
    }

    @Override
    public CoinType getType() {
        return type;
    }

    @Override
    public String toString() {
        return addressStr;
    }

    @Override
    public long getId() {
        return (long) addressStr.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ZcashSdkAddress)) return false;
        ZcashSdkAddress other = (ZcashSdkAddress) o;
        return addressStr.equals(other.addressStr);
    }

    @Override
    public int hashCode() {
        return addressStr.hashCode();
    }
}
