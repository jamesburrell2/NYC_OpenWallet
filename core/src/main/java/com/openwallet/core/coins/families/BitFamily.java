package com.openwallet.core.coins.families;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.AbstractAddress;
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;

/**
 * @author John L. Jegutanis
 *
 * This is the classical Bitcoin family that includes Litecoin, Dogecoin, Dash, etc
 */
public abstract class BitFamily extends CoinType {
    {
        family = Families.BITCOIN;
    }

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        // Try base58check first (Legacy P2PKH and P2SH-SegWit addresses)
        try { return BitAddress.from(this, addressStr); }
        catch (AddressMalformedException ignored) {}

        // Try bech32/bech32m for coins that have a configured HRP
        if (getBech32Hrp() != null) {
            try {
                Bech32.DecodedBech32 decoded = Bech32.decode(addressStr);
                if (decoded.witnessVersion == 0) {
                    // P2WPKH: program is the 20-byte key hash
                    return SegwitAddress.fromHash160(this, decoded.program);
                }
                if (decoded.witnessVersion == 1) {
                    // P2TR: program is the already-tweaked 32-byte x-only output key.
                    // fromOutputKey() does NOT apply the BIP341 tweak (key is already tweaked).
                    return TaprootAddress.fromOutputKey(this, decoded.program);
                }
            } catch (IllegalArgumentException ignored) {}
        }

        throw new AddressMalformedException("Unsupported address: " + addressStr);
    }
}
