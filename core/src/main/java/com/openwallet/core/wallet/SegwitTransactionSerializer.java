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
