package com.openwallet.core.coins.families;

import com.openwallet.core.coins.ZcashAddress;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import org.bitcoinj.core.ECKey;

/**
 * Coin family for Zcash transparent addresses.
 *
 * Extends BitFamily to satisfy instanceof BitFamily checks in Wallet.java,
 * ServerClients.java, and WalletProtobufSerializer.java — all three dispatch
 * on instanceof BitFamily to route to the Bitcoin-family wallet stack.
 *
 * Overrides newAddress(String) and addressFromKey(ECKey) to use ZcashAddress
 * (2-byte Base58Check) instead of BitAddress (1-byte).
 *
 * Note: BitFamily.newAddress() return type was widened to AbstractAddress to
 * allow this override to return ZcashAddress (which does not extend BitAddress).
 */
public abstract class ZcashFamily extends BitFamily {

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        try {
            return ZcashAddress.fromString(addressStr);
        } catch (IllegalArgumentException e) {
            throw new AddressMalformedException(e);
        }
    }

    @Override
    public AbstractAddress addressFromKey(ECKey key) {
        return ZcashAddress.fromHash160(this, key.getPubKeyHash());
    }
}
