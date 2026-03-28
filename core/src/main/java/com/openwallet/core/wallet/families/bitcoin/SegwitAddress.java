package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.ECKey;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Native SegWit P2WPKH address — bech32 encoded, witness version 0.
 * Encodes a 20-byte key hash using the coin's bech32 HRP.
 */
public final class SegwitAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;

    private final CoinType coinType;
    private final byte[] hash160;

    private SegwitAddress(CoinType coinType, byte[] hash160) {
        this.coinType = coinType;
        this.hash160 = Arrays.copyOf(hash160, 20);
    }

    public static SegwitAddress fromHash160(CoinType type, byte[] hash160) {
        if (hash160.length != 20)
            throw new IllegalArgumentException("hash160 must be 20 bytes, got: " + hash160.length);
        return new SegwitAddress(type, hash160);
    }

    public static SegwitAddress fromKey(CoinType type, ECKey key) {
        return fromHash160(type, key.getPubKeyHash());
    }

    public byte[] getHash160() { return Arrays.copyOf(hash160, 20); }

    @Override
    public String toString() {
        String hrp = coinType.getBech32Hrp();
        if (hrp == null) throw new IllegalStateException("Coin has no bech32 HRP: " + coinType.getName());
        return Bech32.encode(hrp, 0, hash160);
    }

    @Override
    public CoinType getType() { return coinType; }

    @Override
    public long getId() { return ByteBuffer.wrap(hash160).getLong(); }
}
