package com.openwallet.core.wallet.families.solana;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Solana address — base58-encoded 32-byte Ed25519 public key.
 */
public class SolanaAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;
    private static final String BASE58_CHARS = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private final CoinType type;
    private final String address;
    private final byte[] pubkey;

    private SolanaAddress(CoinType type, String address, byte[] pubkey) {
        this.type = type;
        this.address = address;
        this.pubkey = pubkey;
    }

    /**
     * Parse and validate a Solana address string (base58, 32-44 chars).
     */
    public static SolanaAddress from(CoinType type, String addressStr) throws AddressMalformedException {
        if (addressStr == null || addressStr.length() < 32 || addressStr.length() > 44) {
            throw new AddressMalformedException("Invalid Solana address: " + addressStr);
        }
        // Validate base58 characters
        for (char c : addressStr.toCharArray()) {
            if (BASE58_CHARS.indexOf(c) < 0) {
                throw new AddressMalformedException("Invalid base58 character in Solana address: " + c);
            }
        }
        byte[] decoded = base58Decode(addressStr);
        if (decoded == null || decoded.length != 32) {
            throw new AddressMalformedException("Invalid Solana address length after decode: " + addressStr);
        }
        return new SolanaAddress(type, addressStr, decoded);
    }

    /**
     * Create from raw 32-byte Ed25519 public key.
     */
    public static SolanaAddress fromPubkey(CoinType type, byte[] pubkey) throws AddressMalformedException {
        if (pubkey == null || pubkey.length != 32) {
            throw new AddressMalformedException("Invalid Solana pubkey: expected 32 bytes");
        }
        String encoded = base58Encode(pubkey);
        return new SolanaAddress(type, encoded, Arrays.copyOf(pubkey, 32));
    }

    public byte[] getPubkey() {
        return Arrays.copyOf(pubkey, pubkey.length);
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
        // Use first 8 bytes of the pubkey as numeric ID
        long id = 0;
        for (int i = 0; i < 8 && i < pubkey.length; i++) {
            id = (id << 8) | (pubkey[i] & 0xFF);
        }
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SolanaAddress that = (SolanaAddress) o;
        return type.equals(that.type) && Arrays.equals(pubkey, that.pubkey);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + Arrays.hashCode(pubkey);
    }

    // ---- Minimal base58 codec (no external dependency) ----

    private static byte[] base58Decode(String input) {
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            int idx = BASE58_CHARS.indexOf(input.charAt(i));
            if (idx < 0) return null;
            input58[i] = (byte) idx;
        }
        // Count leading zeros
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) zeros++;
        // Decode base58 → big integer → byte array
        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        for (int inputStart = zeros; inputStart < input58.length; ) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] == 0) inputStart++;
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) outputStart++;
        byte[] result = new byte[zeros + (decoded.length - outputStart)];
        System.arraycopy(decoded, outputStart, result, zeros, decoded.length - outputStart);
        return result;
    }

    private static String base58Encode(byte[] input) {
        if (input.length == 0) return "";
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;
        byte[] temp = Arrays.copyOf(input, input.length);
        char[] encoded = new char[temp.length * 2];
        int outputStart = encoded.length;
        for (int inputStart = zeros; inputStart < temp.length; ) {
            encoded[--outputStart] = BASE58_CHARS.charAt(divmod(temp, inputStart, 256, 58));
            if (temp[inputStart] == 0) inputStart++;
        }
        while (outputStart < encoded.length && encoded[outputStart] == BASE58_CHARS.charAt(0)) outputStart++;
        while (--zeros >= 0) encoded[--outputStart] = BASE58_CHARS.charAt(0);
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = (int) number[i] & 0xFF;
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
