package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.cardano.CardanoAddress;

/**
 * Coins that belong to this family are: Cardano (ADA).
 * Cardano uses Ed25519-BIP32 extended keys and bech32-encoded addresses (addr1...).
 */
public abstract class CardanoFamily extends CoinType {
    {
        family = Families.CARDANO;
    }

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        return CardanoAddress.from(this, addressStr);
    }
}
