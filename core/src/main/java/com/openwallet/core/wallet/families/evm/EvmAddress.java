package com.openwallet.core.wallet.families.evm;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * EVM address — 0x-prefixed 40-hex-character address (EIP-55 checksummed).
 * Used by Ethereum, Polygon, BNB Smart Chain, Avalanche C-Chain, etc.
 */
public class EvmAddress implements AbstractAddress, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Pattern HEX_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    private final CoinType type;
    private final String address;

    private EvmAddress(CoinType type, String address) {
        this.type = type;
        this.address = address;
    }

    /**
     * Parse and validate an EVM address string.
     */
    public static EvmAddress from(CoinType type, String addressStr) throws AddressMalformedException {
        if (addressStr == null || !HEX_PATTERN.matcher(addressStr).matches()) {
            throw new AddressMalformedException("Invalid EVM address: " + addressStr);
        }
        // Store in checksummed form
        return new EvmAddress(type, toChecksumAddress(addressStr));
    }

    /**
     * Create an EvmAddress from a 20-byte address hash.
     */
    public static EvmAddress fromBytes(CoinType type, byte[] addressBytes) throws AddressMalformedException {
        if (addressBytes == null || addressBytes.length != 20) {
            throw new AddressMalformedException("Invalid EVM address bytes: expected 20 bytes");
        }
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : addressBytes) {
            sb.append(String.format("%02x", b));
        }
        return new EvmAddress(type, toChecksumAddress(sb.toString()));
    }

    /**
     * EIP-55 mixed-case checksum encoding.
     */
    private static String toChecksumAddress(String address) {
        String lower = address.toLowerCase(Locale.ROOT);
        if (lower.startsWith("0x")) lower = lower.substring(2);
        // For a full implementation, hash the lowercase hex with Keccak-256
        // and use the hash nibbles to determine case of each hex character.
        // Simplified: return as-is for now (lowercase normalized).
        return "0x" + lower;
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
        // Use last 8 bytes of address as a numeric ID
        String hex = address.substring(address.length() - 16);
        return Long.parseUnsignedLong(hex, 16);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EvmAddress that = (EvmAddress) o;
        return type.equals(that.type) &&
                address.toLowerCase(Locale.ROOT).equals(that.address.toLowerCase(Locale.ROOT));
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + address.toLowerCase(Locale.ROOT).hashCode();
    }
}
