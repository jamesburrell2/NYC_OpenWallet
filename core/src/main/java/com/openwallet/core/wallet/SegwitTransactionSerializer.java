package com.openwallet.core.wallet;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VarInt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * BIP141-compliant transaction serializer.
 * Self-detects: if witnessData is null or empty, delegates to tx.bitcoinSerialize().
 * Only emits marker+flag+witness fields when at least one input has witness data.
 */
public final class SegwitTransactionSerializer {

    private SegwitTransactionSerializer() {}

    public static byte[] serialize(Transaction tx, Map<Integer, byte[][]> witnessData) {
        if (witnessData == null || witnessData.isEmpty()) {
            return tx.bitcoinSerialize();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // nVersion (4 bytes, little-endian)
            writeInt32LE(out, (int) tx.getVersion());
            // Marker and flag (BIP141)
            out.write(0x00);
            out.write(0x01);
            // Inputs
            writeVarInt(out, tx.getInputs().size());
            for (TransactionInput input : tx.getInputs()) {
                out.write(input.bitcoinSerialize());
            }
            // Outputs
            writeVarInt(out, tx.getOutputs().size());
            for (TransactionOutput output : tx.getOutputs()) {
                out.write(output.bitcoinSerialize());
            }
            // Witness (one stack per input, empty stack for non-witness inputs)
            for (int i = 0; i < tx.getInputs().size(); i++) {
                byte[][] stack = witnessData.get(i);
                if (stack == null || stack.length == 0) {
                    out.write(0x00); // empty witness stack
                } else {
                    writeVarInt(out, stack.length);
                    for (byte[] item : stack) {
                        writeVarInt(out, item.length);
                        out.write(item);
                    }
                }
            }
            // nLocktime (4 bytes, little-endian)
            writeInt32LE(out, (int) tx.getLockTime());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize SegWit transaction", e);
        }
    }

    /**
     * Converts a BIP141 (SegWit) serialized transaction into its legacy
     * (pre-SegWit) serialization by removing the marker, flag and witness
     * fields, leaving version + inputs + outputs + locktime.
     *
     * The transaction id is defined as the double-SHA256 of exactly this
     * legacy serialization, so the result can be parsed by the bundled
     * pre-SegWit bitcoinj with the correct hash, inputs and outputs. The
     * witness stacks are not needed for received transactions (balances are
     * computed from outputs, and spends build their own witnesses).
     *
     * If {@code rawTx} is not SegWit-serialized, or does not parse as a
     * well-formed SegWit transaction, it is returned unchanged so the caller's
     * parser handles it as before.
     */
    public static byte[] stripWitness(byte[] rawTx) {
        // Need at least version(4) + marker(1) + flag(1)
        if (rawTx == null || rawTx.length < 6) return rawTx;
        // BIP144: a SegWit tx has marker byte 0x00 and a non-zero flag byte
        // immediately after the 4-byte version. A legacy tx would have a
        // (non-zero) input-count varint there instead.
        if ((rawTx[4] & 0xff) != 0x00 || (rawTx[5] & 0xff) == 0x00) return rawTx;

        try {
            int[] pos = {6}; // just past version + marker + flag
            int inputsStart = pos[0]; // start of the vin-count varint
            long nIn = readVarInt(rawTx, pos);
            for (long i = 0; i < nIn; i++) {
                pos[0] += 36; // 32-byte outpoint hash + 4-byte index
                long sLen = readVarInt(rawTx, pos);
                pos[0] += (int) sLen; // scriptSig
                pos[0] += 4; // sequence
            }
            long nOut = readVarInt(rawTx, pos);
            for (long i = 0; i < nOut; i++) {
                pos[0] += 8; // value
                long sLen = readVarInt(rawTx, pos);
                pos[0] += (int) sLen; // scriptPubKey
            }
            int outputsEnd = pos[0];
            // Skip the witness stacks (one per input)
            for (long i = 0; i < nIn; i++) {
                long items = readVarInt(rawTx, pos);
                for (long j = 0; j < items; j++) {
                    long itemLen = readVarInt(rawTx, pos);
                    pos[0] += (int) itemLen;
                }
            }
            // Exactly the 4-byte locktime must remain
            int locktimeStart = pos[0];
            if (locktimeStart + 4 != rawTx.length) return rawTx;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(rawTx, 0, 4);                                  // version
            out.write(rawTx, inputsStart, outputsEnd - inputsStart); // vin + vout
            out.write(rawTx, locktimeStart, 4);                      // locktime
            return out.toByteArray();
        } catch (Exception e) {
            // Malformed or unexpected layout: leave the bytes untouched and let
            // the caller's parser surface the problem rather than silently
            // corrupting the transaction.
            return rawTx;
        }
    }

    /** Reads a Bitcoin varint at {@code pos[0]}, advancing {@code pos[0]}. */
    private static long readVarInt(byte[] b, int[] pos) {
        int p = pos[0];
        int first = b[p++] & 0xff;
        long val;
        if (first < 0xfd) {
            val = first;
        } else if (first == 0xfd) {
            val = (b[p] & 0xffL) | ((b[p + 1] & 0xffL) << 8);
            p += 2;
        } else if (first == 0xfe) {
            val = (b[p] & 0xffL) | ((b[p + 1] & 0xffL) << 8)
                    | ((b[p + 2] & 0xffL) << 16) | ((b[p + 3] & 0xffL) << 24);
            p += 4;
        } else {
            val = 0;
            for (int k = 0; k < 8; k++) val |= (b[p + k] & 0xffL) << (8 * k);
            p += 8;
        }
        pos[0] = p;
        return val;
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeVarInt(ByteArrayOutputStream out, long value) throws IOException {
        out.write(new VarInt(value).encode());
    }
}
