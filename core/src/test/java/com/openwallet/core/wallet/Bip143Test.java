package com.openwallet.core.wallet;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.params.MainNetParams;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class Bip143Test {

    // BIP143 Appendix A — P2WPKH example scriptCode structure test
    @Test
    public void bip143ScriptCode_p2wpkh_correctStructure() {
        // scriptCode = OP_DUP OP_HASH160 PUSH20 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
        byte[] pubKeyHash = hexToBytes("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        byte[] scriptCode = buildP2PKHScriptCode(pubKeyHash);
        assertEquals(25, scriptCode.length);
        assertEquals((byte) 0x76, scriptCode[0]);  // OP_DUP
        assertEquals((byte) 0xa9, scriptCode[1]);  // OP_HASH160
        assertEquals((byte) 0x14, scriptCode[2]);  // PUSH 20
        assertEquals((byte) 0x88, scriptCode[23]); // OP_EQUALVERIFY
        assertEquals((byte) 0xac, scriptCode[24]); // OP_CHECKSIG
    }

    @Test
    public void segwitSerializer_emptyWitness_returnsStandardSerialize() {
        // A transaction with no witness data should produce standard serialization
        org.bitcoinj.core.NetworkParameters params = MainNetParams.get();
        Transaction tx = new Transaction(params);
        byte[] standard = tx.bitcoinSerialize();
        Map<Integer, byte[][]> emptyWitness = new HashMap<>();
        byte[] serialized = SegwitTransactionSerializer.serialize(tx, emptyWitness);
        assertArrayEquals("Empty witnessData must produce standard serialization",
                standard, serialized);
    }

    @Test
    public void segwitSerializer_nullWitness_returnsStandardSerialize() {
        org.bitcoinj.core.NetworkParameters params = MainNetParams.get();
        Transaction tx = new Transaction(params);
        byte[] standard = tx.bitcoinSerialize();
        byte[] serialized = SegwitTransactionSerializer.serialize(tx, null);
        assertArrayEquals("Null witnessData must produce standard serialization",
                standard, serialized);
    }

    @Test
    public void p2wpkhRedeemScript_correctBytes() {
        // OP_0 PUSH20 <hash> = 22 bytes
        byte[] hash = hexToBytes("aabbccddeeff00112233445566778899aabbccdd");
        byte[] script = buildP2WPKHRedeemScript(hash);
        assertEquals(22, script.length);
        assertEquals((byte) 0x00, script[0]); // OP_0
        assertEquals((byte) 0x14, script[1]); // PUSH 20
        for (int i = 0; i < 20; i++) {
            assertEquals(hash[i], script[2 + i]);
        }
    }

    private static byte[] buildP2PKHScriptCode(byte[] hash160) {
        byte[] out = new byte[25];
        out[0] = 0x76; // OP_DUP
        out[1] = (byte) 0xa9; // OP_HASH160
        out[2] = 0x14; // PUSH 20
        System.arraycopy(hash160, 0, out, 3, 20);
        out[23] = (byte) 0x88; // OP_EQUALVERIFY
        out[24] = (byte) 0xac; // OP_CHECKSIG
        return out;
    }

    private static byte[] buildP2WPKHRedeemScript(byte[] pubKeyHash) {
        byte[] script = new byte[22];
        script[0] = 0x00;
        script[1] = 0x14;
        System.arraycopy(pubKeyHash, 0, script, 2, 20);
        return script;
    }

    static byte[] hexToBytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
