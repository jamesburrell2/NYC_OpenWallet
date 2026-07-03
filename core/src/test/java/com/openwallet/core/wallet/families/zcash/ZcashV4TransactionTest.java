package com.openwallet.core.wallet.families.zcash;

import org.bitcoinj.core.Utils;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class ZcashV4TransactionTest {

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    /** Builds a fixed 1-in/1-out tx and locks its exact wire bytes. */
    private ZcashV4Transaction fixture() {
        // consensusBranchId does not affect serialize() (only sighash); expiry = 100000
        ZcashV4Transaction tx = new ZcashV4Transaction(0x76B809BB, 100000L);

        byte[] prevTxId = new byte[32];
        for (int i = 0; i < 32; i++) prevTxId[i] = (byte) (i + 1); // 01..20

        // P2PKH scriptPubKey of the UTXO: 76 a9 14 <20-byte hash 00..13> 88 ac
        byte[] spk = new byte[25];
        spk[0] = 0x76; spk[1] = (byte) 0xa9; spk[2] = 0x14;
        for (int i = 0; i < 20; i++) spk[3 + i] = (byte) i; // 00..13
        spk[23] = (byte) 0x88; spk[24] = (byte) 0xac;

        ZcashV4Transaction.TxIn in =
                new ZcashV4Transaction.TxIn(prevTxId, 0, 100000000L, spk);
        in.scriptSig = new byte[]{0x51};   // dummy 1-byte scriptSig for the var-bytes path
        tx.addInput(in);

        tx.addOutput(new ZcashV4Transaction.TxOut(90000000L, spk));
        return tx;
    }

    @Test
    public void serializesToExactWireBytes() {
        // Independently hand-derived from the ZIP-243 v4 transparent layout.
        String expected =
                "0400008085202f8901" +                                                // header|vgid|vin=1
                "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20" +   // prevTxId
                "00000000" +                                                          // prevIndex=0
                "0151" +                                                              // scriptSig len=1, {0x51}
                "feffffff" +                                                          // sequence
                "01" +                                                                // vout=1
                "804a5d0500000000" +                                                  // value=90000000
                "1976a914000102030405060708090a0b0c0d0e0f1011121388ac" +             // scriptPubKey len=25
                "00000000" +                                                          // nLockTime=0
                "a0860100" +                                                          // nExpiryHeight=100000
                "0000000000000000" +                                                  // valueBalance=0
                "000000";                                                            // shielded counts 0/0/0
        assertEquals(expected, hex(fixture().serialize()));
    }

    @Test
    public void headerAndVersionGroupIdLittleEndian() {
        byte[] s = fixture().serialize();
        byte[] first8 = new byte[8];
        System.arraycopy(s, 0, first8, 0, 8);
        assertArrayEquals(
                new byte[]{0x04, 0x00, 0x00, (byte) 0x80, (byte) 0x85, 0x20, 0x2F, (byte) 0x89},
                first8);
    }

    @Test
    public void txIdIs32BytesDoubleSha256Reversed() {
        byte[] ser = fixture().serialize();
        byte[] expected = Utils.reverseBytes(org.bitcoinj.core.Sha256Hash.createDouble(ser).getBytes());
        assertArrayEquals(expected, fixture().txId());
        assertEquals(64, hex(fixture().txId()).length());
    }
}
