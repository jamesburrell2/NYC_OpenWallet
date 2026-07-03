package com.openwallet.core.wallet.families.zcash;

import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Utils;
import org.bitcoinj.core.VarInt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializer for a Zcash v4 (Sapling-format) transaction restricted to the
 * TRANSPARENT case: no shielded spends/outputs/joinsplits, valueBalance = 0.
 *
 * Wire layout (ZIP-243 §"Transaction Format", v4 / Overwinter+Sapling):
 * <pre>
 *   4  header          = 0x80000004 LE (fOverwintered | version 4)
 *   4  nVersionGroupId = 0x892F2085 LE (Sapling)
 *   var vin  count, per input:  outpoint(32+4) + scriptSig(var+bytes) + sequence(4)
 *   var vout count, per output: value(8 LE) + scriptPubKey(var+bytes)
 *   4  nLockTime       = 0
 *   4  nExpiryHeight   LE
 *   8  valueBalance    = 0
 *   var nShieldedSpend  = 0
 *   var nShieldedOutput = 0
 *   var nJoinSplit      = 0
 *   (no bindingSig when all shielded counts are zero)
 * </pre>
 *
 * This class only serializes; the ZIP-243 sighash and signing live in
 * {@link ZcashTxSigner}, which mutates each {@link TxIn#scriptSig} before the
 * final {@link #serialize()}.
 */
public class ZcashV4Transaction {

    public static final long HEADER = 0x80000004L;            // fOverwintered | version 4
    public static final long VERSION_GROUP_ID = 0x892F2085L;  // Sapling
    public static final long SEQUENCE = 0xFFFFFFFEL;          // final (opt-out of RBF/locktime)

    public static class TxIn {
        public final byte[] prevTxId;      // 32 bytes, little-endian as on the wire
        public final int prevIndex;
        public byte[] scriptSig;           // filled after signing
        public final long valueZat;        // needed for the ZIP-243 amount field
        public final byte[] scriptPubKey;  // of the UTXO being spent (also the scriptCode)

        public TxIn(byte[] prevTxId, int prevIndex, long valueZat, byte[] scriptPubKey) {
            if (prevTxId == null || prevTxId.length != 32) {
                throw new IllegalArgumentException("prevTxId must be 32 bytes");
            }
            this.prevTxId = prevTxId;
            this.prevIndex = prevIndex;
            this.valueZat = valueZat;
            this.scriptPubKey = scriptPubKey;
            this.scriptSig = new byte[0];
        }
    }

    public static class TxOut {
        public final long valueZat;
        public final byte[] scriptPubKey;

        public TxOut(long valueZat, byte[] scriptPubKey) {
            this.valueZat = valueZat;
            this.scriptPubKey = scriptPubKey;
        }
    }

    private final int consensusBranchId;
    private final long expiryHeight;
    private final List<TxIn> inputs = new ArrayList<TxIn>();
    private final List<TxOut> outputs = new ArrayList<TxOut>();

    public ZcashV4Transaction(int consensusBranchId, long expiryHeight) {
        this.consensusBranchId = consensusBranchId;
        this.expiryHeight = expiryHeight;
    }

    public int getConsensusBranchId() { return consensusBranchId; }
    public long getExpiryHeight() { return expiryHeight; }
    public List<TxIn> getInputs() { return inputs; }
    public List<TxOut> getOutputs() { return outputs; }

    public void addInput(TxIn in) { inputs.add(in); }
    public void addOutput(TxOut out) { outputs.add(out); }

    /** Full v4 transparent transaction bytes (uses each input's current scriptSig). */
    public byte[] serialize() {
        try {
            ByteArrayOutputStream s = new ByteArrayOutputStream();
            Utils.uint32ToByteStreamLE(HEADER, s);
            Utils.uint32ToByteStreamLE(VERSION_GROUP_ID, s);

            s.write(new VarInt(inputs.size()).encode());
            for (TxIn in : inputs) {
                s.write(in.prevTxId);                          // 32 bytes, as-is
                Utils.uint32ToByteStreamLE(in.prevIndex & 0xFFFFFFFFL, s);
                writeVarBytes(s, in.scriptSig);
                Utils.uint32ToByteStreamLE(SEQUENCE, s);
            }

            s.write(new VarInt(outputs.size()).encode());
            for (TxOut out : outputs) {
                Utils.int64ToByteStreamLE(out.valueZat, s);
                writeVarBytes(s, out.scriptPubKey);
            }

            Utils.uint32ToByteStreamLE(0L, s);                 // nLockTime
            Utils.uint32ToByteStreamLE(expiryHeight & 0xFFFFFFFFL, s);
            Utils.int64ToByteStreamLE(0L, s);                  // valueBalance
            s.write(new VarInt(0).encode());                   // nShieldedSpend
            s.write(new VarInt(0).encode());                   // nShieldedOutput
            s.write(new VarInt(0).encode());                   // nJoinSplit
            return s.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e); // ByteArrayOutputStream never throws
        }
    }

    /** double-SHA256 of serialize(), byte-reversed for display (txid). */
    public byte[] txId() {
        return Utils.reverseBytes(Sha256Hash.createDouble(serialize()).getBytes());
    }

    private static void writeVarBytes(ByteArrayOutputStream s, byte[] bytes) throws IOException {
        byte[] b = bytes == null ? new byte[0] : bytes;
        s.write(new VarInt(b.length).encode());
        s.write(b);
    }
}
