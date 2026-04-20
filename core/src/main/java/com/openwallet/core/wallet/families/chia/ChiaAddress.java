package com.openwallet.core.wallet.families.chia;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;

/**
 * Chia address — bech32m-encoded puzzle hash with "xch" prefix for mainnet,
 * "txch" prefix for testnet.
 */
public class ChiaAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;
    private static final String MAINNET_PREFIX = "xch";
    private static final String TESTNET_PREFIX = "txch";
    private static final String BECH32_CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private final CoinType type;
    private final String address;
    private final byte[] puzzleHash;

    private ChiaAddress(CoinType type, String address, byte[] puzzleHash) {
        this.type = type;
        this.address = address;
        this.puzzleHash = puzzleHash;
    }

    /**
     * Parse and validate a Chia address string (bech32m encoded).
     */
    public static ChiaAddress from(CoinType type, String addressStr) throws AddressMalformedException {
        if (addressStr == null) {
            throw new AddressMalformedException("Null Chia address");
        }

        String lower = addressStr.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(MAINNET_PREFIX + "1") && !lower.startsWith(TESTNET_PREFIX + "1")) {
            throw new AddressMalformedException("Invalid Chia address prefix: " + addressStr);
        }

        int sepPos = lower.lastIndexOf('1');
        if (sepPos < 1) {
            throw new AddressMalformedException("Invalid bech32m Chia address: " + addressStr);
        }
        String dataPart = lower.substring(sepPos + 1);

        // Validate bech32 characters
        for (char c : dataPart.toCharArray()) {
            if (BECH32_CHARS.indexOf(c) < 0) {
                throw new AddressMalformedException("Invalid bech32m character in Chia address: " + c);
            }
        }

        // Decode 5-bit groups to 8-bit bytes
        byte[] data5bit = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            data5bit[i] = (byte) BECH32_CHARS.indexOf(dataPart.charAt(i));
        }

        // Remove the 6-character checksum before converting
        if (data5bit.length <= 6) {
            throw new AddressMalformedException("Chia address data too short: " + addressStr);
        }
        byte[] dataNoChecksum = Arrays.copyOf(data5bit, data5bit.length - 6);
        byte[] decoded = convertBits(dataNoChecksum, 5, 8, false);

        if (decoded == null || decoded.length != 32) {
            throw new AddressMalformedException("Invalid Chia puzzle hash length: " + addressStr);
        }

        return new ChiaAddress(type, addressStr, decoded);
    }

    /**
     * Create from raw 32-byte puzzle hash.
     */
    public static ChiaAddress fromPuzzleHash(CoinType type, byte[] puzzleHash, boolean testnet)
            throws AddressMalformedException {
        if (puzzleHash == null || puzzleHash.length != 32) {
            throw new AddressMalformedException("Invalid Chia puzzle hash: expected 32 bytes");
        }
        String hrp = testnet ? TESTNET_PREFIX : MAINNET_PREFIX;
        byte[] data5bit = convertBits(puzzleHash, 8, 5, true);
        if (data5bit == null) {
            throw new AddressMalformedException("Failed to encode Chia puzzle hash");
        }
        // Simplified bech32m encoding (without checksum computation)
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte b : data5bit) {
            sb.append(BECH32_CHARS.charAt(b));
        }
        return new ChiaAddress(type, sb.toString(), Arrays.copyOf(puzzleHash, 32));
    }

    public byte[] getPuzzleHash() {
        return Arrays.copyOf(puzzleHash, puzzleHash.length);
    }

    @Override
    public CoinType getType() {
        return type;
    }

    @Override
    public String toString() {
        return address;
    }

    @Override
    public long getId() {
        long id = 0;
        for (int i = 0; i < 8 && i < puzzleHash.length; i++) {
            id = (id << 8) | (puzzleHash[i] & 0xFF);
        }
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChiaAddress that = (ChiaAddress) o;
        return type.equals(that.type) && Arrays.equals(puzzleHash, that.puzzleHash);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(puzzleHash);
    }

    private static byte[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
        int acc = 0;
        int bits = 0;
        int maxv = (1 << toBits) - 1;
        byte[] result = new byte[data.length * fromBits / toBits + 1];
        int idx = 0;
        for (byte value : data) {
            acc = (acc << fromBits) | (value & 0xFF);
            bits += fromBits;
            while (bits >= toBits) {
                bits -= toBits;
                if (idx >= result.length) return null;
                result[idx++] = (byte) ((acc >> bits) & maxv);
            }
        }
        if (pad && bits > 0) {
            if (idx >= result.length) return null;
            result[idx++] = (byte) ((acc << (toBits - bits)) & maxv);
        } else if (!pad && (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0)) {
            return null;
        }
        return Arrays.copyOf(result, idx);
    }
}
