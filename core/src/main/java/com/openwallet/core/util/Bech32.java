package com.openwallet.core.util;

import java.util.Arrays;
import java.util.Locale;

/**
 * BIP173 (bech32) and BIP350 (bech32m) codec.
 * No external dependencies.
 */
public final class Bech32 {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int[] CHARSET_REV = new int[128];
    static {
        Arrays.fill(CHARSET_REV, -1);
        for (int i = 0; i < CHARSET.length(); i++)
            CHARSET_REV[CHARSET.charAt(i)] = i;
    }

    private static final int BECH32_CONST  = 1;
    private static final int BECH32M_CONST = 0x2bc830a3;

    public enum Variant { BECH32, BECH32M }

    public static final class DecodedBech32 {
        public final String hrp;
        public final int witnessVersion;
        public final byte[] program;
        public final Variant variant;
        DecodedBech32(String hrp, int witnessVersion, byte[] program, Variant variant) {
            this.hrp = hrp;
            this.witnessVersion = witnessVersion;
            this.program = program;
            this.variant = variant;
        }
    }

    /** Encode a witness program. witnessVersion 0 → bech32, 1+ → bech32m. */
    public static String encode(String hrp, int witnessVersion, byte[] program) {
        if (witnessVersion < 0 || witnessVersion > 16)
            throw new IllegalArgumentException("Invalid witness version: " + witnessVersion);
        int constant = witnessVersion == 0 ? BECH32_CONST : BECH32M_CONST;
        byte[] conv = convertBits(program, 8, 5, true);
        // Data: witnessVersion byte + converted program
        byte[] data = new byte[1 + conv.length];
        data[0] = (byte) witnessVersion;
        System.arraycopy(conv, 0, data, 1, conv.length);
        return hrp + '1' + encodeData(hrp, data, constant);
    }

    /** Decode a bech32 or bech32m string. Throws IllegalArgumentException on any error. */
    public static DecodedBech32 decode(String bech) {
        bech = bech.toLowerCase(Locale.ROOT);
        int sepPos = bech.lastIndexOf('1');
        if (sepPos < 1 || sepPos + 7 > bech.length())
            throw new IllegalArgumentException("Invalid bech32 string: " + bech);
        String hrp = bech.substring(0, sepPos);
        byte[] data = new byte[bech.length() - sepPos - 1];
        for (int i = 0; i < data.length; i++) {
            char c = bech.charAt(sepPos + 1 + i);
            if (c >= 128 || CHARSET_REV[c] == -1)
                throw new IllegalArgumentException("Invalid character: " + c);
            data[i] = (byte) CHARSET_REV[c];
        }
        // Last 6 bytes are checksum
        if (data.length < 6) throw new IllegalArgumentException("Too short");
        int constant = polymodVerify(hrpExpand(hrp), data);
        Variant variant;
        if (constant == BECH32_CONST)        variant = Variant.BECH32;
        else if (constant == BECH32M_CONST)  variant = Variant.BECH32M;
        else throw new IllegalArgumentException("Invalid checksum");

        byte[] stripped = Arrays.copyOfRange(data, 0, data.length - 6);
        int witnessVersion = stripped[0] & 0xFF;
        byte[] program = convertBits(Arrays.copyOfRange(stripped, 1, stripped.length), 5, 8, false);
        if (program.length < 2 || program.length > 40)
            throw new IllegalArgumentException("Invalid program length");
        if (witnessVersion == 0 && program.length != 20 && program.length != 32)
            throw new IllegalArgumentException("Invalid program length for witness v0");
        return new DecodedBech32(hrp, witnessVersion, program, variant);
    }

    private static String encodeData(String hrp, byte[] data, int constant) {
        byte[] checksum = createChecksum(hrp, data, constant);
        StringBuilder sb = new StringBuilder();
        for (byte b : data)      sb.append(CHARSET.charAt(b & 0x1F));
        for (byte b : checksum)  sb.append(CHARSET.charAt(b & 0x1F));
        return sb.toString();
    }

    private static byte[] createChecksum(String hrp, byte[] data, int constant) {
        byte[] enc = concat(concat(hrpExpand(hrp), data), new byte[6]); // append 6 zeros
        int mod = polymod(enc) ^ constant;
        byte[] ret = new byte[6];
        for (int i = 0; i < 6; i++)
            ret[i] = (byte) ((mod >> (5 * (5 - i))) & 31);
        return ret;
    }

    private static int polymodVerify(byte[] hrpExpanded, byte[] data) {
        byte[] all = concat(hrpExpanded, data);
        return polymod(all);
    }

    private static int polymod(byte[] values) {
        int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        for (byte b : values) {
            int top = chk >> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ (b & 0xFF);
            for (int i = 0; i < 5; i++)
                if (((top >> i) & 1) == 1) chk ^= GEN[i];
        }
        return chk;
    }

    private static byte[] hrpExpand(String hrp) {
        byte[] ret = new byte[hrp.length() * 2 + 1];
        for (int i = 0; i < hrp.length(); i++) {
            ret[i]                      = (byte) (hrp.charAt(i) >> 5);
            ret[hrp.length() + 1 + i]  = (byte) (hrp.charAt(i) & 0x1f);
        }
        ret[hrp.length()] = 0;
        return ret;
    }

    private static byte[] convertBits(byte[] data, int from, int to, boolean pad) {
        long acc = 0;
        int bits = 0;
        byte[] out = new byte[data.length * from / to + 2];
        int idx = 0;
        int maxv = (1 << to) - 1;
        for (byte b : data) {
            acc = ((acc << from) | (b & ((1 << from) - 1)));
            bits += from;
            while (bits >= to) {
                bits -= to;
                out[idx++] = (byte) ((acc >> bits) & maxv);
            }
        }
        if (pad) {
            if (bits > 0) out[idx++] = (byte) ((acc << (to - bits)) & maxv);
        } else if (bits >= from || ((acc << (to - bits)) & maxv) != 0) {
            throw new IllegalArgumentException("Non-zero padding bits");
        }
        return Arrays.copyOf(out, idx);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
