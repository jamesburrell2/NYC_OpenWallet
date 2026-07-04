package com.openwallet.core.wallet.families.zcash;

import org.json.JSONArray;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies {@link ZcashTxSigner#sighashRawTx} against the official Zcash ZIP-243
 * test vectors (zcash/zcash-test-vectors → zip_0243.json). Each row provides a raw
 * v4 transaction, the input's scriptCode, the transparent input index (-1 = none),
 * the sighash type, the input amount, the consensus branch id, and the expected
 * BLAKE2b-256 sighash.
 */
public class Zip243SighashTest {

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String loadResource(String path) throws Exception {
        try (InputStream in = Zip243SighashTest.class.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    @Test
    public void matchesOfficialZip243Vectors() throws Exception {
        String content = loadResource("/zip_0243_vectors.json");
        // Fail closed: the vectors are the whole point of this test — never pass silently.
        assertNotNull("zip_0243_vectors.json missing from test resources", content);

        JSONArray rows = new JSONArray(content);
        int exercised = 0;
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            if (row.length() < 7) continue; // header rows

            byte[] rawTx      = unhex(row.getString(0));
            String scriptHex  = row.getString(1);
            byte[] scriptCode = scriptHex.isEmpty() ? new byte[0] : unhex(scriptHex);
            int inputIndex    = row.getInt(2);
            int hashType      = row.getInt(3);
            long amount        = row.getLong(4);
            int branchId      = (int) row.getLong(5);
            String expected   = row.getString(6);

            byte[] got = ZcashTxSigner.sighashRawTx(
                    rawTx, inputIndex, scriptCode, amount, hashType, branchId);
            assertEquals("vector row " + r, expected, hex(got));
            exercised++;
        }
        assertTrue("expected at least 4 vectors exercised, got " + exercised, exercised >= 4);
    }
}
