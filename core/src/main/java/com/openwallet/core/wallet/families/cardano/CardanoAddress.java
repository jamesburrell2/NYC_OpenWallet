package com.openwallet.core.wallet.families.cardano;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;

/**
 * Cardano address — bech32-encoded Shelley address (addr1... for mainnet, addr_test1... for testnet).
 */
public class CardanoAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;
    private static final String MAINNET_PREFIX = "addr1";
    private static final String TESTNET_PREFIX = "addr_test1";
    private static final String BECH32_CHARS = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

    private final CoinType type;
    private final String address;
    private final byte[] addressBytes;

    private CardanoAddress(CoinType type, String address, byte[] addressBytes) {
        this.type = type;
        this.address = address;
        this.addressBytes = addressBytes;
    }

    /**
     * Parse and validate a Cardano address string.
     */
    public static CardanoAddress from(CoinType type, String addressStr) throws AddressMalformedException {
        if (addressStr == null) {
            throw new AddressMalformedException("Null Cardano address");
        }

        String lower = addressStr.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(MAINNET_PREFIX) && !lower.startsWith(TESTNET_PREFIX)) {
            throw new AddressMalformedException("Invalid Cardano address prefix: " + addressStr);
        }

        // Find the separator (always '1' after the HRP)
        int sepPos = lower.lastIndexOf('1');
        if (sepPos < 1) {
            throw new AddressMalformedException("Invalid bech32 Cardano address: " + addressStr);
        }
        String dataPart = lower.substring(sepPos + 1);

        // Validate bech32 characters
        for (char c : dataPart.toCharArray()) {
            if (BECH32_CHARS.indexOf(c) < 0) {
                throw new AddressMalformedException("Invalid bech32 character in Cardano address: " + c);
            }
        }

        // Decode the 5-bit data part to bytes (simplified — no checksum validation here)
        byte[] data5bit = new byte[dataPart.length()];
        for (int i = 0; i < dataPart.length(); i++) {
            data5bit[i] = (byte) BECH32_CHARS.indexOf(dataPart.charAt(i));
        }
        byte[] decoded = convertBits(data5bit, 5, 8, false);
        if (decoded == null) {
            throw new AddressMalformedException("Failed to decode Cardano address: " + addressStr);
        }

        return new CardanoAddress(type, addressStr, decoded);
    }

    /**
     * Create from raw address bytes (header byte + payload).
     */
    public static CardanoAddress fromBytes(CoinType type, byte[] bytes, boolean testnet) throws AddressMalformedException {
        if (bytes == null || bytes.length < 2) {
            throw new AddressMalformedException("Invalid Cardano address bytes");
        }
        String hrp = testnet ? "addr_test" : "addr";
        byte[] data5bit = convertBits(bytes, 8, 5, true);
        if (data5bit == null) {
            throw new AddressMalformedException("Failed to encode Cardano address bytes");
        }
        // Simplified bech32 encoding (without checksum — a full implementation would add it)
        StringBuilder sb = new StringBuilder(hrp).append('1');
        for (byte b : data5bit) {
            sb.append(BECH32_CHARS.charAt(b));
        }
        return new CardanoAddress(type, sb.toString(), bytes);
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
        for (int i = 0; i < 8 && i < addressBytes.length; i++) {
            id = (id << 8) | (addressBytes[i] & 0xFF);
        }
        return id;
    }

    public byte[] getBytes() {
        return Arrays.copyOf(addressBytes, addressBytes.length);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CardanoAddress that = (CardanoAddress) o;
        return type.equals(that.type) && Arrays.equals(addressBytes, that.addressBytes);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(addressBytes);
    }

    /**
     * Convert between bit groups (e.g., 8-bit to 5-bit for bech32).
     */
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
