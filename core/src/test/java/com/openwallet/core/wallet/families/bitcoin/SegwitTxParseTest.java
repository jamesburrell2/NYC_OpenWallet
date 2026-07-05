package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.BitcoinMain;

import org.junit.Test;
import org.spongycastle.util.encoders.Hex;

import static org.junit.Assert.assertEquals;

/**
 * Regression test for parsing BIP141 (SegWit) serialized transactions.
 *
 * The bundled bitcoinj-core-0.12.3 predates SegWit and cannot parse a raw
 * transaction that carries the witness marker/flag (0x00 0x01 after the
 * version). Real-world funding transactions for bech32 (m/84') accounts are
 * almost always SegWit-serialized, so BitTransaction must strip the witness
 * to the legacy serialization before handing the bytes to bitcoinj. The txid
 * is defined over exactly that legacy serialization.
 *
 * Test vector: mainnet txid 1da5c545..., which begins "02000000 0001" and
 * pays a P2WPKH output belonging to the "StampHash" (m/84'/0'/7') account.
 */
public class SegwitTxParseTest {

    private static final String SEGWIT_TX_HEX =
            "02000000000101ce55763e99823bb6b0e3ba650e1467ac6dec3a465c5441f5af99d374"
            + "4a5d6a8f0100000000ffffffff020000000000000000526a4c4f53746d7048617368"
            + "3236303632325f3161333865393939323361653765303331316232653830306561366"
            + "3393734656339656537643965333965353164633832346561373965366264323064633"
            + "030e14a000000000000160014ecb8fb30c544e6fc6c8cc00b24aa3008bab86fea024730"
            + "44022069fa91295126472f05789c456ecf3cc2e998df579dc7cb55a099e802c29fb96d02"
            + "205a7d5ebf1dc5e6f3ccc9a66571405f5ec38585d40475488b51e326c4611913ed012103"
            + "f9024b68b1b96ec2ee25c4b694e8f12461e0023cfe65a1cdd1f24fb0c597b38800000000";

    private static final String EXPECTED_TXID =
            "1da5c54571c23889d68be19714fbb12ccb00940628c949691d2d5dd480c331bc";

    @Test
    public void parsesSegwitTxAndComputesCorrectTxid() {
        BitTransaction tx = new BitTransaction(BitcoinMain.get(), Hex.decode(SEGWIT_TX_HEX));
        assertEquals(EXPECTED_TXID, tx.getHashAsString());
    }
}
