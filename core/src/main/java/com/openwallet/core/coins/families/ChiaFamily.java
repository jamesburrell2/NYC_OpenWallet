package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.chia.ChiaAddress;

/**
 * Coins that belong to this family are: Chia (XCH).
 * Chia uses BLS12-381 keys and bech32m addresses with the "xch" prefix.
 */
public abstract class ChiaFamily extends CoinType {
    {
        family = Families.CHIA;
    }

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        return ChiaAddress.from(this, addressStr);
    }
}
