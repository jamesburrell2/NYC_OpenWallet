package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.ECKey;

import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Taproot P2TR address — bech32m encoded, witness version 1.
 * Key-path tweak per BIP341: Q = lift_x(x(P)) + t·G
 * where t = int(hash_taptweak(bytes(P))).
 * Receive display only — no spending path in this release.
 */
public final class TaprootAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;

    private static final String TAPTWEAK_TAG = "TapTweak";
    private static final byte[] TAPTWEAK_HASH = taggedHashPrefix(TAPTWEAK_TAG);

    // secp256k1 field prime p = 2^256 - 2^32 - 977
    private static final BigInteger P_FIELD =
        new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    // secp256k1 group order
    private static final BigInteger N_ORDER =
        new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    // Generator point x-coordinate
    private static final BigInteger GX =
        new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);

    private final CoinType coinType;
    private final byte[] outputKey32; // 32-byte x-only tweaked output key

    private TaprootAddress(CoinType coinType, byte[] outputKey32) {
        this.coinType = coinType;
        this.outputKey32 = Arrays.copyOf(outputKey32, 32);
    }

    /** Derive from a 32-byte x-only public key — applies BIP341 key-path tweak. */
    public static TaprootAddress fromXOnlyKey(CoinType type, byte[] xOnlyKey32) {
        if (xOnlyKey32.length != 32)
            throw new IllegalArgumentException("x-only key must be 32 bytes");
        byte[] tweakedKey = applyTaprootTweak(xOnlyKey32);
        return new TaprootAddress(type, tweakedKey);
    }

    /** Derive from an ECKey — extracts x-only pubkey then applies BIP341 key-path tweak. */
    public static TaprootAddress fromKey(CoinType type, ECKey key) {
        byte[] compressed = key.getPubKey(); // 33 bytes
        byte[] xOnly = Arrays.copyOfRange(compressed, 1, 33); // strip prefix byte
        return fromXOnlyKey(type, xOnly);
    }

    /**
     * Create from an already-tweaked 32-byte x-only output key
     * (e.g. decoded directly from a bech32m address string).
     * Does NOT apply the BIP341 key-path tweak.
     */
    public static TaprootAddress fromOutputKey(CoinType type, byte[] outputKey32) {
        if (outputKey32.length != 32)
            throw new IllegalArgumentException("output key must be 32 bytes, got: " + outputKey32.length);
        return new TaprootAddress(type, outputKey32);
    }

    public byte[] getOutputKey() { return Arrays.copyOf(outputKey32, 32); }

    @Override
    public String toString() {
        String hrp = coinType.getBech32Hrp();
        if (hrp == null) throw new IllegalStateException("Coin has no bech32 HRP: " + coinType.getName());
        return Bech32.encode(hrp, 1, outputKey32);
    }

    @Override
    public CoinType getType() { return coinType; }

    @Override
    public long getId() { return ByteBuffer.wrap(outputKey32).getLong(); }

    /**
     * BIP341 key-path tweak:
     * t = int(hash_taptweak(bytes(P)))
     * Q = lift_x(x(P)) + t·G  (return x-coordinate as 32 bytes)
     */
    private static byte[] applyTaprootTweak(byte[] xOnly32) {
        byte[] tweak = taggedHash(TAPTWEAK_HASH, xOnly32);
        BigInteger t = new BigInteger(1, tweak);
        if (t.compareTo(N_ORDER) >= 0)
            throw new IllegalArgumentException("Tweak scalar is out of range");

        BigInteger x = new BigInteger(1, xOnly32);
        BigInteger y = liftX(x);

        BigInteger[] G = { GX, liftX(GX) };
        BigInteger[] P_pt = { x, y };
        BigInteger[] tG = scalarMul(G, t);
        BigInteger[] Q = pointAdd(P_pt, tG);

        byte[] qx = Q[0].toByteArray();
        if (qx.length == 33) qx = Arrays.copyOfRange(qx, 1, 33);
        byte[] out = new byte[32];
        System.arraycopy(qx, 0, out, 32 - qx.length, qx.length);
        return out;
    }

    private static BigInteger liftX(BigInteger x) {
        BigInteger y2 = x.pow(3).add(BigInteger.valueOf(7)).mod(P_FIELD);
        BigInteger y = y2.modPow(P_FIELD.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P_FIELD);
        if (y.testBit(0)) y = P_FIELD.subtract(y);
        return y;
    }

    private static BigInteger[] pointAdd(BigInteger[] P, BigInteger[] Q) {
        if (P == null) return Q;
        if (Q == null) return P;
        BigInteger px = P[0], py = P[1], qx = Q[0], qy = Q[1];
        if (px.equals(qx)) {
            if (!py.equals(qy)) return null;
            BigInteger lam = px.pow(2).multiply(BigInteger.valueOf(3))
                .multiply(py.multiply(BigInteger.TWO).modPow(P_FIELD.subtract(BigInteger.TWO), P_FIELD))
                .mod(P_FIELD);
            BigInteger rx = lam.pow(2).subtract(px.multiply(BigInteger.TWO)).mod(P_FIELD);
            return new BigInteger[]{ rx, lam.multiply(px.subtract(rx)).subtract(py).mod(P_FIELD) };
        }
        BigInteger lam = qy.subtract(py)
            .multiply(qx.subtract(px).modPow(P_FIELD.subtract(BigInteger.TWO), P_FIELD))
            .mod(P_FIELD);
        BigInteger rx = lam.pow(2).subtract(px).subtract(qx).mod(P_FIELD);
        return new BigInteger[]{ rx, lam.multiply(px.subtract(rx)).subtract(py).mod(P_FIELD) };
    }

    private static BigInteger[] scalarMul(BigInteger[] P, BigInteger k) {
        BigInteger[] R = null;
        BigInteger[] Q = P;
        while (k.signum() > 0) {
            if (k.testBit(0)) R = pointAdd(R, Q);
            Q = pointAdd(Q, Q);
            k = k.shiftRight(1);
        }
        return R;
    }

    private static byte[] taggedHash(byte[] tagHash, byte[] msg) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(tagHash);
            sha.update(tagHash);
            sha.update(msg);
            return sha.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] taggedHashPrefix(String tag) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(tag.getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
