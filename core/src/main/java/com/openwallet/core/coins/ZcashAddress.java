package com.openwallet.core.coins;

import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.Sha256Hash;

import java.util.Arrays;

/**
 * Zcash transparent address with 2-byte version prefix.
 *
 * Zcash uses a 2-byte version (e.g. 0x1C 0xB8 for t1, 0x1C 0xBD for t3).
 * bitcoinj's VersionedChecksummedBytes only handles single-byte versions,
 * so this class implements encode/decode independently.
 *
 * Wire format: [versionHigh, versionLow, hash160 x20, checksum x4] = 26 bytes
 * Base58Check of 26 bytes → typically 35 chars for 0x1CB8/0x1CBD prefixes.
 */
public class ZcashAddress implements AbstractAddress {

    private static final long serialVersionUID = 1L;

    private final int version;       // full 2-byte version as int (e.g. 0x1CB8)
    private final byte[] hash160;    // 20-byte public key hash
    private final CoinType coinType; // may be null for unit tests

    private ZcashAddress(int version, byte[] hash160, CoinType coinType) {
        this.version = version;
        this.hash160 = Arrays.copyOf(hash160, 20);
        this.coinType = coinType;
    }

    /** Construct from a coin type (uses type.getAddressHeader() as version). */
    public static ZcashAddress fromHash160(CoinType type, byte[] hash160) {
        return new ZcashAddress(type.getAddressHeader(), hash160, type);
    }

    /** Construct without a coin type — useful for tests and parsing. */
    public static ZcashAddress fromHash160(int version, byte[] hash160) {
        return new ZcashAddress(version, hash160, null);
    }

    /**
     * Decode a Base58Check Zcash address string.
     *
     * @throws IllegalArgumentException on checksum failure or wrong length
     */
    public static ZcashAddress fromString(String address) throws IllegalArgumentException {
        byte[] decoded;
        try {
            decoded = Base58.decode(address);
        } catch (AddressFormatException e) {
            throw new IllegalArgumentException("Invalid Base58 address: " + address, e);
        }
        if (decoded.length != 26) {
            throw new IllegalArgumentException("Decoded address must be 26 bytes, got: " + decoded.length);
        }
        // Last 4 bytes are checksum
        byte[] payload = Arrays.copyOfRange(decoded, 0, 22);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);

        // Verify checksum: first 4 bytes of double-SHA256 of payload
        byte[] hash = Sha256Hash.createDouble(payload).getBytes();
        for (int i = 0; i < 4; i++) {
            if (hash[i] != checksum[i]) {
                throw new IllegalArgumentException("Invalid checksum for address: " + address);
            }
        }
        // payload = [versionHigh, versionLow, hash160 x20]
        if (payload.length < 22) {
            throw new IllegalArgumentException("Payload too short: " + payload.length);
        }
        int ver = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        byte[] hash160 = Arrays.copyOfRange(payload, 2, 22);
        return new ZcashAddress(ver, hash160, null);
    }

    @Override
    public String toString() {
        // Build payload: [versionHigh, versionLow, hash160 x20] = 22 bytes
        byte[] payload = new byte[22];
        payload[0] = (byte) ((version >> 8) & 0xFF);
        payload[1] = (byte) (version & 0xFF);
        System.arraycopy(hash160, 0, payload, 2, 20);

        // Checksum: first 4 bytes of double-SHA256 of payload
        byte[] checksum = Sha256Hash.createDouble(payload).getBytes();

        // Final: payload + 4-byte checksum = 26 bytes
        byte[] full = new byte[26];
        System.arraycopy(payload, 0, full, 0, 22);
        System.arraycopy(checksum, 0, full, 22, 4);

        return Base58.encode(full);
    }

    public int getVersion() { return version; }
    public byte[] getHash160() { return Arrays.copyOf(hash160, 20); }

    @Override
    public CoinType getType() { return coinType; }

    @Override
    public long getId() {
        // Use first 8 bytes of hash160 as a stable ID (same convention as BitAddress)
        long id = 0;
        for (int i = 0; i < Math.min(8, hash160.length); i++) {
            id = (id << 8) | (hash160[i] & 0xFF);
        }
        return id;
    }
}
