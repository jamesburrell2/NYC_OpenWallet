package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.crypto.Blake2b;

import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * ZIP-243 (Sapling v4) transparent SIGHASH and P2PKH signer for the Zcash family
 * (ZEC transparent, ZCL, YEC). Uses BLAKE2b-256 with per-field personalizations.
 *
 * <p>The {@link #sighashRawTx} primitive parses a fully-serialized v4 transaction
 * (including any shielded sections) and is verified byte-for-byte against ALL 10
 * transparent/shielded vectors in the official {@code zip_0243.json} test set
 * (see {@code Zip243SighashTest}). {@link #sighash(ZcashV4Transaction,int,int)}
 * builds on it for the transparent-only transactions this wallet constructs.
 *
 * SOC-2: never logs keys, signatures, or sighash preimages; fails closed on
 * malformed input (bounds-checked cursor throws rather than emitting a bad digest).
 */
public class ZcashTxSigner {

    // Consensus branch IDs — fetched from src/consensus/upgrades.cpp on GitHub 2026-07-03,
    // NOT from memory (a wrong id yields txs the network silently rejects).
    /** ZEC NU6.2 (active on mainnet since height 3,364,600, ~June 2026). Re-verify the
     *  NU6.1(0x4dec4df0)/NU6.2 name↔height mapping before broadcasting to ZEC mainnet. */
    public static final int BRANCH_ID_ZEC_CURRENT = 0x5437F330;
    /** Ycash latest network upgrade (ycashfoundation/ycash upgrades.cpp). */
    public static final int BRANCH_ID_YEC_CURRENT = 0xF919A198;
    /** Zclassic Sapling — verified: all official ZIP-243 vectors use this branch id. */
    public static final int BRANCH_ID_ZCL_SAPLING = 0x76B809BB;

    public static final int SIGHASH_ALL = 1;
    private static final int SIGHASH_NONE = 2;
    private static final int SIGHASH_SINGLE = 3;
    private static final int SIGHASH_ANYONECANPAY = 0x80;

    private static final byte[] P_PREVOUTS = ascii("ZcashPrevoutHash");
    private static final byte[] P_SEQUENCE = ascii("ZcashSequencHash");
    private static final byte[] P_OUTPUTS  = ascii("ZcashOutputsHash");
    private static final byte[] P_JSPLITS  = ascii("ZcashJSplitsHash");
    private static final byte[] P_SSPENDS  = ascii("ZcashSSpendsHash");
    private static final byte[] P_SOUTPUT  = ascii("ZcashSOutputHash");
    private static final byte[] ZERO32 = new byte[32];

    /** ZIP-243 SIGHASH_ALL digest for input {@code inputIndex} of a transparent-only tx. */
    public static byte[] sighash(ZcashV4Transaction tx, int inputIndex, int consensusBranchId) {
        ZcashV4Transaction.TxIn in = tx.getInputs().get(inputIndex);
        return sighashRawTx(tx.serialize(), inputIndex, in.scriptPubKey, in.valueZat,
                SIGHASH_ALL, consensusBranchId);
    }

    /**
     * ZIP-243 sighash from a fully-serialized v4 transaction — the vector-verified core.
     * @param inputIndex transparent input being signed, or -1 for none (shielded sighash)
     */
    public static byte[] sighashRawTx(byte[] rawTx, int inputIndex, byte[] scriptCode,
                                      long amount, int hashType, int consensusBranchId) {
        Cursor c = new Cursor(rawTx);
        byte[] header = c.take(4);
        byte[] versionGroupId = c.take(4);

        // vin
        long nin = c.varint();
        ByteArrayOutputStream prevouts = new ByteArrayOutputStream();
        ByteArrayOutputStream sequences = new ByteArrayOutputStream();
        byte[][] inOutpoints = new byte[(int) nin][];
        byte[][] inSequences = new byte[(int) nin][];
        for (int i = 0; i < nin; i++) {
            byte[] outpoint = c.take(36);
            long slen = c.varint();
            c.skip(slen);           // scriptSig — not part of the sighash preimage
            byte[] seq = c.take(4);
            prevouts.write(outpoint, 0, 36);
            sequences.write(seq, 0, 4);
            inOutpoints[i] = outpoint;
            inSequences[i] = seq;
        }

        // vout
        long nout = c.varint();
        ByteArrayOutputStream outs = new ByteArrayOutputStream();
        byte[][] outSerialized = new byte[(int) nout][];
        for (int i = 0; i < nout; i++) {
            byte[] value = c.take(8);
            long slen = c.varint();
            byte[] spk = c.take((int) slen);
            byte[] ser = concat(value, varintBytes(slen), spk);
            outs.write(ser, 0, ser.length);
            outSerialized[i] = ser;
        }

        byte[] lockTime = c.take(4);
        byte[] expiryHeight = c.take(4);
        byte[] valueBalance = c.take(8);

        // shielded spends (SpendDescription = 384 bytes; hashed portion excludes 64-byte spendAuthSig)
        long nsp = c.varint();
        ByteArrayOutputStream spendsHashData = new ByteArrayOutputStream();
        for (int i = 0; i < nsp; i++) {
            byte[] hashed = c.take(320);   // cv||anchor||nullifier||rk||zkproof
            c.skip(64);                    // spendAuthSig
            spendsHashData.write(hashed, 0, 320);
        }
        // shielded outputs (OutputDescription = 948 bytes, fully hashed)
        long nso = c.varint();
        ByteArrayOutputStream outputsHashData = new ByteArrayOutputStream();
        for (int i = 0; i < nso; i++) {
            byte[] od = c.take(948);
            outputsHashData.write(od, 0, 948);
        }
        // joinsplits (Sapling/Groth JSDescription = 1698 bytes) + joinSplitPubKey(32)
        long njs = c.varint();
        ByteArrayOutputStream jsHashData = new ByteArrayOutputStream();
        for (int i = 0; i < njs; i++) {
            byte[] js = c.take(1698);
            jsHashData.write(js, 0, 1698);
        }
        if (njs > 0) {
            byte[] jsPubKey = c.take(32);
            jsHashData.write(jsPubKey, 0, 32);
        }

        boolean anyoneCanPay = (hashType & SIGHASH_ANYONECANPAY) != 0;
        int base = hashType & 0x1f;

        byte[] hashPrevouts = anyoneCanPay ? ZERO32 : blake2b(P_PREVOUTS, prevouts.toByteArray());
        byte[] hashSequence = (!anyoneCanPay && base != SIGHASH_SINGLE && base != SIGHASH_NONE)
                ? blake2b(P_SEQUENCE, sequences.toByteArray()) : ZERO32;
        byte[] hashOutputs;
        if (base != SIGHASH_SINGLE && base != SIGHASH_NONE) {
            hashOutputs = blake2b(P_OUTPUTS, outs.toByteArray());
        } else if (base == SIGHASH_SINGLE && inputIndex >= 0 && inputIndex < nout) {
            hashOutputs = blake2b(P_OUTPUTS, outSerialized[inputIndex]);
        } else {
            hashOutputs = ZERO32;
        }
        byte[] hashJoinSplits      = njs > 0 ? blake2b(P_JSPLITS, jsHashData.toByteArray()) : ZERO32;
        byte[] hashShieldedSpends  = nsp > 0 ? blake2b(P_SSPENDS, spendsHashData.toByteArray()) : ZERO32;
        byte[] hashShieldedOutputs = nso > 0 ? blake2b(P_SOUTPUT, outputsHashData.toByteArray()) : ZERO32;

        ByteArrayOutputStream msg = new ByteArrayOutputStream();
        write(msg, header);
        write(msg, versionGroupId);
        write(msg, hashPrevouts);
        write(msg, hashSequence);
        write(msg, hashOutputs);
        write(msg, hashJoinSplits);
        write(msg, hashShieldedSpends);
        write(msg, hashShieldedOutputs);
        write(msg, lockTime);
        write(msg, expiryHeight);
        write(msg, valueBalance);
        write(msg, uint32le(hashType));
        if (inputIndex >= 0) {
            write(msg, inOutpoints[inputIndex]);
            write(msg, varintBytes(scriptCode.length));
            write(msg, scriptCode);
            write(msg, int64le(amount));
            write(msg, inSequences[inputIndex]);
        }
        return blake2b(sighashPersonal(consensusBranchId), msg.toByteArray());
    }

    /**
     * Signs every input as P2PKH with the matching key (by pubkey-hash order supplied),
     * fills each scriptSig, and returns the final raw transaction bytes.
     * SIGHASH_ALL only. {@code keys.get(i)} must sign {@code tx.getInputs().get(i)}.
     */
    public static byte[] signAll(ZcashV4Transaction tx, List<ECKey> keys, int consensusBranchId) {
        List<ZcashV4Transaction.TxIn> inputs = tx.getInputs();
        if (keys.size() != inputs.size()) {
            throw new IllegalArgumentException("one key required per input");
        }
        for (int i = 0; i < inputs.size(); i++) {
            byte[] digest = sighash(tx, i, consensusBranchId);
            ECKey key = keys.get(i);
            ECKey.ECDSASignature sig = key.sign(new Sha256Hash(digest)).toCanonicalised();
            byte[] der = sig.encodeToDER();
            byte[] sigPlusType = concat(der, new byte[]{(byte) SIGHASH_ALL});
            byte[] pubkey = key.getPubKey();
            inputs.get(i).scriptSig = concat(pushData(sigPlusType), pushData(pubkey));
        }
        return tx.serialize();
    }

    // ---- helpers ----

    private static byte[] sighashPersonal(int branchId) {
        byte[] p = new byte[16];
        System.arraycopy(ascii("ZcashSigHash"), 0, p, 0, 12); // 12 bytes
        p[12] = (byte) branchId;
        p[13] = (byte) (branchId >>> 8);
        p[14] = (byte) (branchId >>> 16);
        p[15] = (byte) (branchId >>> 24);
        return p;
    }

    private static byte[] blake2b(byte[] personal, byte[] data) {
        Blake2b d = new Blake2b(32, personal);
        d.update(data, 0, data.length);
        return d.digest();
    }

    private static byte[] pushData(byte[] data) {
        if (data.length < 0x4c) {
            return concat(new byte[]{(byte) data.length}, data);
        }
        // OP_PUSHDATA1 for 76..255 (DER sig / pubkey never exceed this, but be correct)
        return concat(new byte[]{(byte) 0x4c, (byte) data.length}, data);
    }

    private static byte[] uint32le(long v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] int64le(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    private static byte[] varintBytes(long n) {
        if (n < 0xfd) return new byte[]{(byte) n};
        if (n <= 0xffff) return new byte[]{(byte) 0xfd, (byte) n, (byte) (n >>> 8)};
        if (n <= 0xffffffffL) return new byte[]{(byte) 0xfe, (byte) n, (byte) (n >>> 8),
                (byte) (n >>> 16), (byte) (n >>> 24)};
        byte[] b = new byte[9];
        b[0] = (byte) 0xff;
        for (int i = 0; i < 8; i++) b[1 + i] = (byte) (n >>> (8 * i));
        return b;
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static void write(ByteArrayOutputStream s, byte[] b) { s.write(b, 0, b.length); }

    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }

    /** Bounds-checked little-endian byte cursor; fails closed on truncated input. */
    private static final class Cursor {
        private final byte[] b;
        private int i = 0;
        Cursor(byte[] b) { this.b = b; }

        byte[] take(int n) {
            if (n < 0 || i + n > b.length) throw new IllegalArgumentException("truncated tx");
            byte[] r = new byte[n];
            System.arraycopy(b, i, r, 0, n);
            i += n;
            return r;
        }
        void skip(long n) {
            if (n < 0 || i + n > b.length) throw new IllegalArgumentException("truncated tx");
            i += (int) n;
        }
        int u8() {
            if (i + 1 > b.length) throw new IllegalArgumentException("truncated tx");
            return b[i++] & 0xFF;
        }
        long varint() {
            int n = u8();
            if (n < 0xfd) return n;
            if (n == 0xfd) return u8() | (u8() << 8);
            if (n == 0xfe) return (u8() | (u8() << 8) | (u8() << 16) | ((long) u8() << 24));
            long r = 0;
            for (int k = 0; k < 8; k++) r |= ((long) u8()) << (8 * k);
            return r;
        }
    }
}
