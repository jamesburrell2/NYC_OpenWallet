package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.evm.EvmAddress;

/**
 * Coins that belong to this family are: Ethereum, Polygon, BNB Smart Chain, Avalanche, etc.
 * EVM-compatible chains share the same address format (0x-prefixed hex) and
 * use JSON-RPC for node communication.
 */
public abstract class EvmFamily extends CoinType {
    {
        family = Families.EVM;
    }

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        return EvmAddress.from(this, addressStr);
    }
}
