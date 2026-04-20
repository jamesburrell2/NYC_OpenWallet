package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.solana.SolanaAddress;

/**
 * Coins that belong to this family are: Solana (SOL) and SPL tokens.
 * Solana uses Ed25519 keypairs and base58-encoded addresses.
 */
public abstract class SolanaFamily extends CoinType {
    {
        family = Families.SOLANA;
    }

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        return SolanaAddress.from(this, addressStr);
    }
}
