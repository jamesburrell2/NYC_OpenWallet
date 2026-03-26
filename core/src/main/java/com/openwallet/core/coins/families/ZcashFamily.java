package com.openwallet.core.coins.families;

import com.openwallet.core.coins.ZcashAddress;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;

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
 */
public abstract class ZcashFamily extends BitFamily {

    @Override
    public BitAddress newAddress(String addressStr) throws AddressMalformedException {
        try {
            ZcashAddress addr = ZcashAddress.fromString(addressStr);
            // ZcashAddress is returned as AbstractAddress, which is compatible with BitAddress's contract
            return (BitAddress) (Object) addr;
        } catch (IllegalArgumentException e) {
            throw new AddressMalformedException(e);
        }
    }

    @Override
    public AbstractAddress addressFromKey(ECKey key) {
        return ZcashAddress.fromHash160(this, key.getPubKeyHash());
    }
}
