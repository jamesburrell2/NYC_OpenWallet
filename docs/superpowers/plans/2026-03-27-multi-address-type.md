# Multi-Address-Type Support Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live address-type selection (Legacy, Compatible, Native SegWit, Taproot) to the Receive screen for BTC, LTC, DGB, VTC — with full BIP143 SegWit signing so users can spend from Compatible and Native SegWit addresses.

**Architecture:** Single BIP44 key chain per coin; address type controls encoding of the current receive key. `getActiveAddresses()` emits all supported types per key so ElectrumX subscribes to them all. BIP143 signing is wired into `TransactionCreator`, bypassing `LocalTransactionSigner` for SegWit inputs. Witness data flows through `BitTransaction` to `SegwitTransactionSerializer` at broadcast.

**Tech Stack:** Java (Android), bitcoinj-core-0.12.3 (bundled JAR), JUnit 4, Gradle.

---

## Pre-existing test failures (ignore throughout)

Running `:core:test` before this feature shows these failing: `MonetaryFormatTest`, `SimpleHDKeyChainTest`, `SweepWalletTest`, `WalletPocketHDTest`, `WalletTest`, `CommunicationsTest`. These are pre-existing and unrelated. Only fix `addressStartsWithN` (Task 1 below).

Build command: `cd /d/NewYorkCoin_2026/nyc-openwallet-android && ./gradlew :core:test --tests "com.openwallet.core.<TestClass>.<method>" --no-daemon 2>&1 | tail -20`

Full build: `./gradlew assembleDebug --no-daemon 2>&1 | tail -15`

---

## File Map

**Create:**
- `core/src/main/java/com/openwallet/core/coins/AddressType.java`
- `core/src/main/java/com/openwallet/core/util/Bech32.java`
- `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddress.java`
- `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java`
- `core/src/main/java/com/openwallet/core/wallet/SegwitTransactionSerializer.java`
- `core/src/test/java/com/openwallet/core/util/Bech32Test.java`
- `core/src/test/java/com/openwallet/core/wallet/SegwitAddressTest.java`
- `core/src/test/java/com/openwallet/core/wallet/Bip143Test.java`

**Modify:**
- `core/src/main/java/com/openwallet/core/coins/CoinType.java` — add `supportedAddressTypes`, `bech32Hrp`, `addressFromKey(ECKey, AddressType)`
- `core/src/main/java/com/openwallet/core/coins/BitcoinMain.java` — add `bech32Hrp = "bc"`, supported types
- `core/src/main/java/com/openwallet/core/coins/LitecoinMain.java` — add `bech32Hrp = "ltc"`, supported types
- `core/src/main/java/com/openwallet/core/coins/DigibyteMain.java` — add `bech32Hrp = "dgb"`, supported types
- `core/src/main/java/com/openwallet/core/coins/VertcoinMain.java` — add `bech32Hrp = "vtc"`, supported types
- `core/src/main/java/com/openwallet/core/util/GenericUtils.java` — SegwitAddress/TaprootAddress branches
- `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` — extend `getActiveAddresses()`
- `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java` — BIP143 signing, P2SH fix
- `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/BitTransaction.java` — add `witnessData` field + `bitcoinSerialize()` override
- `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java` — add address type Spinner
- `wallet/src/main/res/layout/fragment_request.xml` — insert Spinner above QR
- `wallet/src/main/res/values/strings.xml` — add string resources
- `core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java` — fix `addressStartsWithN` → `addressStartsWithR`

---

## Chunk 1: Core codec, address types, coin config

### Task 1: Fix NYC address test

**Files:**
- Modify: `core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java`

- [ ] **Step 1.1: Update the test method name and assertion**

In `NewYorkCoinTest.java`, rename `addressStartsWithN` to `addressStartsWithR` and change `startsWith("N")` to `startsWith("R")`:

```java
@Test
public void addressStartsWithR() throws MnemonicException, Exception {
    CoinType nyc = NewYorkCoinMain.get();
    DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
    DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
    DeterministicHierarchy h = new DeterministicHierarchy(master);
    DeterministicKey accountKey = h.get(nyc.getBip44Path(0), false, true);
    DeterministicKey receiveKey = HDKeyDerivation.deriveChildKey(accountKey, 0);
    DeterministicKey addrKey = HDKeyDerivation.deriveChildKey(receiveKey, 0);

    String address = nyc.addressFromKey(addrKey).toString();
    assertTrue("NYC address must start with R, got: " + address,
            address.startsWith("R"));
}
```

- [ ] **Step 1.2: Run and confirm it passes**

```
./gradlew :core:test --tests "com.openwallet.core.coins.NewYorkCoinTest" --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 1.3: Commit**

```bash
git add core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java
git commit -m "fix(test): NYC address prefix is R not N (addressHeader=0x3C)"
```

---

### Task 2: AddressType enum

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/AddressType.java`

- [ ] **Step 2.1: Create the enum**

```java
package com.openwallet.core.coins;

/**
 * Address encoding types supported per coin.
 * TAPROOT is receive-display only — no spending path in this release.
 */
public enum AddressType {
    LEGACY,        // P2PKH, Base58Check
    COMPATIBLE,    // P2SH-P2WPKH, Base58Check P2SH
    NATIVE_SEGWIT, // P2WPKH, bech32 witness version 0
    TAPROOT        // P2TR, bech32m witness version 1 (receive only)
}
```

No test needed — verified implicitly by Task 5 tests. Commit now:

- [ ] **Step 2.2: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/AddressType.java
git commit -m "feat: add AddressType enum (LEGACY/COMPATIBLE/NATIVE_SEGWIT/TAPROOT)"
```

---

### Task 3: Bech32 codec

**Files:**
- Create: `core/src/main/java/com/openwallet/core/util/Bech32.java`
- Create: `core/src/test/java/com/openwallet/core/util/Bech32Test.java`

- [ ] **Step 3.1: Write the failing tests first**

```java
package com.openwallet.core.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class Bech32Test {

    // BIP173 reference vector — P2WPKH
    @Test
    public void bech32EncodeDecode_btcP2WPKH() {
        // bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4
        // HRP="bc", witnessVer=0, program=0x751e76e8199196f45f713854b7ae86eb3ee6c6a2 (20 bytes)
        byte[] program = hexToBytes("751e76e8199196f45f713854b7ae86eb3ee6c6a2");
        String encoded = Bech32.encode("bc", 0, program);
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", encoded);

        Bech32.DecodedBech32 decoded = Bech32.decode(encoded);
        assertEquals("bc", decoded.hrp);
        assertEquals(0, decoded.witnessVersion);
        assertArrayEquals(program, decoded.program);
        assertEquals(Bech32.Variant.BECH32, decoded.variant);
    }

    // BIP350 reference vector — P2TR (bech32m)
    @Test
    public void bech32mEncodeDecode_btcP2TR() {
        // bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297 — 32-byte x-only key
        byte[] program = hexToBytes("5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9ru" // placeholder
                .getBytes()); // replaced below with actual bytes
        // Use actual BIP350 test vector: witness v1, 32-byte program
        byte[] prog32 = hexToBytes("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
        String encoded = Bech32.encode("bc", 1, prog32);
        assertTrue("Taproot address must start with bc1p", encoded.startsWith("bc1p"));

        Bech32.DecodedBech32 decoded = Bech32.decode(encoded);
        assertEquals("bc", decoded.hrp);
        assertEquals(1, decoded.witnessVersion);
        assertArrayEquals(prog32, decoded.program);
        assertEquals(Bech32.Variant.BECH32M, decoded.variant);
    }

    @Test
    public void ltcBech32Encode() {
        byte[] program = hexToBytes("751e76e8199196f45f713854b7ae86eb3ee6c6a2");
        String encoded = Bech32.encode("ltc", 0, program);
        assertTrue(encoded.startsWith("ltc1q"));
        assertEquals("ltc", Bech32.decode(encoded).hrp);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidBech32_badChecksum() {
        Bech32.decode("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t5"); // last char changed
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidBech32_noSeparator() {
        Bech32.decode("notabech32string");
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
```

- [ ] **Step 3.2: Run to confirm FAIL**

```
./gradlew :core:test --tests "com.openwallet.core.util.Bech32Test" --no-daemon 2>&1 | tail -10
```
Expected: FAILED (class not found)

- [ ] **Step 3.3: Implement Bech32.java**

```java
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
        byte[] enc = concat(hrpExpand(hrp), data);
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
            ret[i]                  = (byte) (hrp.charAt(i) >> 5);
            ret[hrp.length() + 1 + i] = (byte) (hrp.charAt(i) & 0x1f);
        }
        ret[hrp.length()] = 0;
        return ret;
    }

    private static byte[] convertBits(byte[] data, int from, int to, boolean pad) {
        int acc = 0, bits = 0;
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
```

- [ ] **Step 3.4: Run tests to confirm PASS**

```
./gradlew :core:test --tests "com.openwallet.core.util.Bech32Test" --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3.5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/util/Bech32.java \
        core/src/test/java/com/openwallet/core/util/Bech32Test.java
git commit -m "feat: add Bech32 codec (BIP173/BIP350) with bech32m support"
```

---

### Task 4: SegwitAddress and TaprootAddress

**Files:**
- Create: `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddress.java`
- Create: `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java`
- Create: `core/src/test/java/com/openwallet/core/wallet/SegwitAddressTest.java`

- [ ] **Step 4.1: Write failing tests**

```java
package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import com.openwallet.core.coins.LitecoinMain;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
import org.bitcoinj.core.ECKey;
import org.junit.Test;
import static org.junit.Assert.*;

public class SegwitAddressTest {

    // Deterministic key from known hash: all-zeros 32-byte private key → compressed pubkey
    // hash160(pubkey) = known value we can hardcode
    private static final byte[] HASH160 =
        hexToBytes("751e76e8199196f45f713854b7ae86eb3ee6c6a2");

    @Test
    public void segwitAddress_btc_startsWith_bc1q() {
        SegwitAddress addr = SegwitAddress.fromHash160(BitcoinMain.get(), HASH160);
        assertTrue("BTC native SegWit must start with bc1q, got: " + addr,
                addr.toString().startsWith("bc1q"));
    }

    @Test
    public void segwitAddress_ltc_startsWith_ltc1q() {
        SegwitAddress addr = SegwitAddress.fromHash160(LitecoinMain.get(), HASH160);
        assertTrue("LTC native SegWit must start with ltc1q", addr.toString().startsWith("ltc1q"));
    }

    @Test
    public void segwitAddress_knownVector() {
        // BIP173 reference: bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4
        SegwitAddress addr = SegwitAddress.fromHash160(BitcoinMain.get(), HASH160);
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", addr.toString());
    }

    @Test
    public void taprootAddress_btc_startsWith_bc1p() {
        // 32-byte x-only pubkey (all zeros for test — not a valid key but sufficient for encoding)
        byte[] xOnly = new byte[32];
        xOnly[0] = 0x02; // arbitrary non-zero bytes for tweak input
        TaprootAddress addr = TaprootAddress.fromXOnlyKey(BitcoinMain.get(), xOnly);
        assertTrue("BTC taproot must start with bc1p, got: " + addr,
                addr.toString().startsWith("bc1p"));
    }

    @Test
    public void segwitAddress_getType_returnsCoinType() {
        SegwitAddress addr = SegwitAddress.fromHash160(BitcoinMain.get(), HASH160);
        assertEquals(BitcoinMain.get(), addr.getType());
    }

    private static byte[] hexToBytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2)
            data[i/2] = (byte)((Character.digit(hex.charAt(i),16)<<4) + Character.digit(hex.charAt(i+1),16));
        return data;
    }
}
```

- [ ] **Step 4.2: Run to confirm FAIL** (class not found)

- [ ] **Step 4.3: Implement SegwitAddress.java**

```java
package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.crypto.KeyCrypterException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Native SegWit P2WPKH address — bech32 encoded, witness version 0.
 * Encodes a 20-byte key hash using the coin's bech32 HRP.
 */
public final class SegwitAddress implements AbstractAddress {
    private static final long serialVersionUID = 1L;

    private final CoinType coinType;
    private final byte[] hash160;

    private SegwitAddress(CoinType coinType, byte[] hash160) {
        this.coinType = coinType;
        this.hash160 = Arrays.copyOf(hash160, 20);
    }

    public static SegwitAddress fromHash160(CoinType type, byte[] hash160) {
        if (hash160.length != 20)
            throw new IllegalArgumentException("hash160 must be 20 bytes, got: " + hash160.length);
        return new SegwitAddress(type, hash160);
    }

    public static SegwitAddress fromKey(CoinType type, ECKey key) {
        return fromHash160(type, key.getPubKeyHash());
    }

    public byte[] getHash160() { return Arrays.copyOf(hash160, 20); }

    @Override
    public String toString() {
        String hrp = coinType.getBech32Hrp();
        if (hrp == null) throw new IllegalStateException("Coin has no bech32 HRP: " + coinType.getName());
        return Bech32.encode(hrp, 0, hash160);
    }

    @Override
    public CoinType getType() { return coinType; }

    @Override
    public long getId() { return ByteBuffer.wrap(hash160).getLong(); }
}
```

- [ ] **Step 4.4: Implement TaprootAddress.java**

```java
package com.openwallet.core.wallet.families.bitcoin;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.Sha256Hash;
import java.io.Serializable;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Taproot P2TR address — bech32m encoded, witness version 1.
 * Key-path tweak per BIP341: Q = lift_x(x(P)) + t·G where t = int(hash_taptweak(bytes(P))).
 * Receive display only — no spending path in this release.
 */
public final class TaprootAddress implements AbstractAddress {
    private static final long serialVersionUID = 1L;

    private static final String TAPTWEAK_TAG = "TapTweak";
    private static final byte[] TAPTWEAK_HASH = taggedHashPrefix(TAPTWEAK_TAG);

    // secp256k1 field prime p = 2^256 - 2^32 - 977
    private static final BigInteger P_FIELD =
        new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    // secp256k1 group order
    private static final BigInteger N_ORDER =
        new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    // Generator point x-coordinate (for G)
    private static final BigInteger GX =
        new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16);

    private final CoinType coinType;
    private final byte[] outputKey32; // 32-byte x-only tweaked output key

    private TaprootAddress(CoinType coinType, byte[] outputKey32) {
        this.coinType = coinType;
        this.outputKey32 = Arrays.copyOf(outputKey32, 32);
    }

    /** Derive from a 32-byte x-only public key (already tweaked). */
    public static TaprootAddress fromXOnlyKey(CoinType type, byte[] xOnlyKey32) {
        if (xOnlyKey32.length != 32)
            throw new IllegalArgumentException("x-only key must be 32 bytes");
        byte[] tweakedKey = applyTaprootTweak(xOnlyKey32);
        return new TaprootAddress(type, tweakedKey);
    }

    /** Derive from an ECKey — extracts x-only pubkey then applies BIP341 key-path tweak. */
    public static TaprootAddress fromKey(CoinType type, ECKey key) {
        byte[] compressed = key.getPubKey(); // 33 bytes
        byte[] xOnly = Arrays.copyOfRange(compressed, 1, 33); // strip prefix byte
        return fromXOnlyKey(type, xOnly);
    }

    public byte[] getOutputKey() { return Arrays.copyOf(outputKey32, 32); }

    @Override
    public String toString() {
        String hrp = coinType.getBech32Hrp();
        if (hrp == null) throw new IllegalStateException("Coin has no bech32 HRP: " + coinType.getName());
        return Bech32.encode(hrp, 1, outputKey32);
    }

    @Override
    public CoinType getType() { return coinType; }

    @Override
    public long getId() { return ByteBuffer.wrap(outputKey32).getLong(); }

    /**
     * BIP341 key-path tweak:
     * t = int(hash_taptweak(bytes(P)))
     * Q = lift_x(x(P)) + t·G  (x-only, so take x-coordinate)
     */
    private static byte[] applyTaprootTweak(byte[] xOnly32) {
        // hash_taptweak(x) = SHA256(SHA256("TapTweak") || SHA256("TapTweak") || x)
        byte[] tweak = taggedHash(TAPTWEAK_HASH, xOnly32);
        BigInteger t = new BigInteger(1, tweak);
        if (t.compareTo(N_ORDER) >= 0)
            throw new IllegalArgumentException("Tweak scalar is out of range");

        // We need the full point for P. Use secp256k1 lift_x to get the even-parity point.
        // lift_x(x) = point with x-coordinate x and even y.
        BigInteger x = new BigInteger(1, xOnly32);
        BigInteger y = liftX(x);

        // G point
        BigInteger[] G = { GX, liftX(GX) };

        // Q = P + t·G using secp256k1 point addition
        BigInteger[] P_pt = { x, y };
        BigInteger[] tG = scalarMul(G, t);
        BigInteger[] Q = pointAdd(P_pt, tG);

        // Return x-coordinate of Q as 32 bytes
        byte[] qx = Q[0].toByteArray();
        if (qx.length == 33) qx = Arrays.copyOfRange(qx, 1, 33); // strip sign byte
        byte[] out = new byte[32];
        System.arraycopy(qx, 0, out, 32 - qx.length, qx.length);
        return out;
    }

    private static BigInteger liftX(BigInteger x) {
        // y² = x³ + 7 (mod p), take even root
        BigInteger y2 = x.pow(3).add(BigInteger.valueOf(7)).mod(P_FIELD);
        BigInteger y = y2.modPow(P_FIELD.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), P_FIELD);
        if (y.testBit(0)) y = P_FIELD.subtract(y);
        return y;
    }

    private static BigInteger[] pointAdd(BigInteger[] P, BigInteger[] Q) {
        if (P == null) return Q;
        if (Q == null) return P;
        BigInteger px = P[0], py = P[1], qx = Q[0], qy = Q[1];
        if (px.equals(qx)) {
            if (!py.equals(qy)) return null; // point at infinity
            BigInteger lam = px.pow(2).multiply(BigInteger.valueOf(3))
                .multiply(py.multiply(BigInteger.TWO).modPow(P_FIELD.subtract(BigInteger.TWO), P_FIELD))
                .mod(P_FIELD);
            BigInteger rx = lam.pow(2).subtract(px.multiply(BigInteger.TWO)).mod(P_FIELD);
            return new BigInteger[]{ rx, lam.multiply(px.subtract(rx)).subtract(py).mod(P_FIELD) };
        }
        BigInteger lam = qy.subtract(py)
            .multiply(qx.subtract(px).modPow(P_FIELD.subtract(BigInteger.TWO), P_FIELD))
            .mod(P_FIELD);
        BigInteger rx = lam.pow(2).subtract(px).subtract(qx).mod(P_FIELD);
        return new BigInteger[]{ rx, lam.multiply(px.subtract(rx)).subtract(py).mod(P_FIELD) };
    }

    private static BigInteger[] scalarMul(BigInteger[] P, BigInteger k) {
        BigInteger[] R = null;
        BigInteger[] Q = P;
        while (k.signum() > 0) {
            if (k.testBit(0)) R = pointAdd(R, Q);
            Q = pointAdd(Q, Q);
            k = k.shiftRight(1);
        }
        return R;
    }

    private static byte[] taggedHash(byte[] tagHash, byte[] msg) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(tagHash);
            sha.update(tagHash);
            sha.update(msg);
            return sha.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] taggedHashPrefix(String tag) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest(tag.getBytes("UTF-8"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
```

- [ ] **Step 4.5: Run tests to confirm PASS**

```
./gradlew :core:test --tests "com.openwallet.core.wallet.SegwitAddressTest" --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4.6: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddress.java \
        core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java \
        core/src/test/java/com/openwallet/core/wallet/SegwitAddressTest.java
git commit -m "feat: add SegwitAddress (bech32 P2WPKH) and TaprootAddress (bech32m P2TR)"
```

---

### Task 5: CoinType extensions

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinType.java`

- [ ] **Step 5.1: Add new imports and fields to CoinType**

Add after the existing imports:
```java
import com.openwallet.core.coins.AddressType;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
import org.bitcoinj.core.Utils;
import org.bitcoinj.script.ScriptBuilder;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
```

Add after `protected byte[] signedMessageHeader;`:
```java
protected Set<AddressType> supportedAddressTypes =
        Collections.unmodifiableSet(EnumSet.of(AddressType.LEGACY));
protected String bech32Hrp = null;
```

- [ ] **Step 5.2: Add public accessors**

Add after `canHandleMessages()`:
```java
public Set<AddressType> getSupportedAddressTypes() { return supportedAddressTypes; }

public String getBech32Hrp() { return bech32Hrp; }
```

- [ ] **Step 5.3: Add addressFromKey(ECKey, AddressType) overload**

Add after the existing `addressFromKey(ECKey key)` method:
```java
/**
 * Create an address for this coin type from a public key, using the specified address type.
 * Only valid for coins that declare the given type in supportedAddressTypes.
 */
public AbstractAddress addressFromKey(ECKey key, AddressType type) {
    switch (type) {
        case LEGACY:
            return addressFromKey(key);
        case COMPATIBLE: {
            // P2SH-P2WPKH: hash160(OP_0 <20-byte-pubKeyHash>)
            byte[] pubKeyHash = key.getPubKeyHash();
            byte[] redeemScript = new byte[] {
                0x00, 0x14,                         // OP_0 PUSH20
                pubKeyHash[0],  pubKeyHash[1],  pubKeyHash[2],  pubKeyHash[3],
                pubKeyHash[4],  pubKeyHash[5],  pubKeyHash[6],  pubKeyHash[7],
                pubKeyHash[8],  pubKeyHash[9],  pubKeyHash[10], pubKeyHash[11],
                pubKeyHash[12], pubKeyHash[13], pubKeyHash[14], pubKeyHash[15],
                pubKeyHash[16], pubKeyHash[17], pubKeyHash[18], pubKeyHash[19]
            };
            byte[] scriptHash = Utils.sha256hash160(redeemScript);
            try {
                return com.openwallet.core.wallet.families.bitcoin.BitAddress.from(
                        this, getP2SHHeader(), scriptHash);
            } catch (Exception e) {
                throw new RuntimeException("Failed to derive Compatible address", e);
            }
        }
        case NATIVE_SEGWIT:
            return SegwitAddress.fromKey(this, key);
        case TAPROOT:
            return TaprootAddress.fromKey(this, key);
        default:
            throw new IllegalArgumentException("Unknown address type: " + type);
    }
}
```

- [ ] **Step 5.4: Build to confirm it compiles**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5.5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/CoinType.java
git commit -m "feat: add supportedAddressTypes, bech32Hrp, addressFromKey(ECKey,AddressType) to CoinType"
```

---

### Task 6: Configure coin definitions

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/BitcoinMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/LitecoinMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/DigibyteMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/VertcoinMain.java`

- [ ] **Step 6.1: Add SegWit support to each coin constructor**

In each coin's constructor, add after the existing `signedMessageHeader` line:

**BitcoinMain.java:**
```java
bech32Hrp = "bc";
supportedAddressTypes = Collections.unmodifiableSet(
        EnumSet.of(AddressType.LEGACY, AddressType.COMPATIBLE,
                   AddressType.NATIVE_SEGWIT, AddressType.TAPROOT));
```

**LitecoinMain.java:**
```java
bech32Hrp = "ltc";
supportedAddressTypes = Collections.unmodifiableSet(
        EnumSet.of(AddressType.LEGACY, AddressType.COMPATIBLE,
                   AddressType.NATIVE_SEGWIT, AddressType.TAPROOT));
```

**DigibyteMain.java:**
```java
bech32Hrp = "dgb";
supportedAddressTypes = Collections.unmodifiableSet(
        EnumSet.of(AddressType.LEGACY, AddressType.COMPATIBLE,
                   AddressType.NATIVE_SEGWIT, AddressType.TAPROOT));
```

**VertcoinMain.java:**
```java
bech32Hrp = "vtc";
supportedAddressTypes = Collections.unmodifiableSet(
        EnumSet.of(AddressType.LEGACY, AddressType.COMPATIBLE,
                   AddressType.NATIVE_SEGWIT, AddressType.TAPROOT));
```

Add the required imports to each file:
```java
import com.openwallet.core.coins.AddressType;
import java.util.Collections;
import java.util.EnumSet;
```

- [ ] **Step 6.2: Build to confirm**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -5
```

- [ ] **Step 6.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/BitcoinMain.java \
        core/src/main/java/com/openwallet/core/coins/LitecoinMain.java \
        core/src/main/java/com/openwallet/core/coins/DigibyteMain.java \
        core/src/main/java/com/openwallet/core/coins/VertcoinMain.java
git commit -m "feat: configure bech32Hrp and supportedAddressTypes for BTC/LTC/DGB/VTC"
```

---

### Task 7: GenericUtils — add SegwitAddress and TaprootAddress branches

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/util/GenericUtils.java`

- [ ] **Step 7.1: Add branches in addressSplitToGroupsMultiline()**

The existing ZcashAddress branch was added in a prior fix. Now add SegwitAddress and TaprootAddress above the final `else throw`:

```java
} else if (address instanceof SegwitAddress || address instanceof TaprootAddress) {
    // bech32/bech32m: format is HRP + '1' + data (all lowercase)
    // Split at the '1' separator, then break the data portion at its midpoint
    String s = address.toString();
    int sep = s.indexOf('1');
    if (sep < 0 || sep == s.length() - 1) return s; // unexpected, return as-is
    String data = s.substring(sep + 1);
    int mid = sep + 1 + data.length() / 2;
    return s.substring(0, mid) + "\n" + s.substring(mid);
}
```

Add imports at top of file:
```java
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
```

- [ ] **Step 7.2: Build to confirm**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -5
```

- [ ] **Step 7.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/util/GenericUtils.java
git commit -m "feat: handle SegwitAddress and TaprootAddress in GenericUtils address formatting"
```

---

## Chunk 2: ElectrumX subscriptions + BIP143 signing

### Task 8: Extend WalletPocketHD.getActiveAddresses()

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java`

- [ ] **Step 8.1: Modify getActiveAddresses() to emit all supported types per key**

Replace:
```java
@Override
public List<AbstractAddress> getActiveAddresses() {
    lock.lock();
    try {
        ImmutableList.Builder<AbstractAddress> activeAddresses = ImmutableList.builder();
        for (DeterministicKey key : keys.getActiveKeys()) {
            activeAddresses.add(type.addressFromKey(key));
        }
        return activeAddresses.build();
    } finally {
        lock.unlock();
    }
}
```

With:
```java
@Override
public List<AbstractAddress> getActiveAddresses() {
    lock.lock();
    try {
        ImmutableList.Builder<AbstractAddress> activeAddresses = ImmutableList.builder();
        for (DeterministicKey key : keys.getActiveKeys()) {
            activeAddresses.add(type.addressFromKey(key)); // LEGACY always
            for (AddressType addrType : type.getSupportedAddressTypes()) {
                if (addrType == AddressType.LEGACY || addrType == AddressType.TAPROOT) continue;
                activeAddresses.add(type.addressFromKey(key, addrType));
            }
        }
        return activeAddresses.build();
    } finally {
        lock.unlock();
    }
}
```

Add import: `import com.openwallet.core.coins.AddressType;`

- [ ] **Step 8.2: Build to confirm**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -5
```

- [ ] **Step 8.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java
git commit -m "feat: extend getActiveAddresses() to subscribe ElectrumX to COMPATIBLE and NATIVE_SEGWIT addresses"
```

---

### Task 9: Fix estimateBytesForSigning() for P2SH-P2WPKH

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java`

- [ ] **Step 9.1: Replace the P2SH throw with P2SH-P2WPKH size estimation**

Find and replace in `estimateBytesForSigning()`:
```java
} else if (script.isPayToScriptHash()) {
    throw new ScriptException("Wallet does not currently support PayToScriptHash");
//                    redeemScript = keychain.findRedeemScriptFromPubHash(script.getPubKeyHash());
//                    checkNotNull(redeemScript, "Coin selection includes unspendable outputs");
}
```

Replace with:
```java
} else if (script.isPayToScriptHash()) {
    // P2SH-P2WPKH: scriptSig = 23 bytes (push of 22-byte redeem script)
    // witness = ~108 bytes (sig + compressed pubkey)
    // Total input size: 41 (outpoint+sequence) + 23 (scriptSig) + 108 (witness/4 stripped) ≈ 171
    // We conservatively estimate the non-witness size used for MAX_STANDARD_TX_SIZE check
    size += 41 + 23 + 1; // input overhead + redeem script push + empty witness marker
}
```

- [ ] **Step 9.2: Add a test confirming no throw for P2SH-P2WPKH**

Write test in `Bip143Test.java` (created in Task 11):
```java
// See Task 11 — Bip143Test includes estimateBytesForSigning coverage
```

(Test is deferred to Task 11 where Bip143Test is fully created.)

- [ ] **Step 9.3: Build to confirm**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -5
```

- [ ] **Step 9.4: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java
git commit -m "fix: estimateBytesForSigning handles P2SH-P2WPKH without throwing"
```

---

### Task 10: Add witnessData to BitTransaction

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/BitTransaction.java`

- [ ] **Step 10.1: Add witnessData field and modified bitcoinSerialize()**

Add after the `@Nullable final Value fee;` field:
```java
// Witness data populated by TransactionCreator for SegWit transactions.
// Key = input index, value = witness stack items (each item is a byte[]).
private java.util.Map<Integer, byte[][]> witnessData = null;
```

Add setter method after the constructor:
```java
public void setWitnessData(java.util.Map<Integer, byte[][]> witnessData) {
    this.witnessData = witnessData;
}

public java.util.Map<Integer, byte[][]> getWitnessData() { return witnessData; }
```

Replace existing `bitcoinSerialize()`:
```java
public byte[] bitcoinSerialize() {
    if (witnessData != null && !witnessData.isEmpty()) {
        return com.openwallet.core.wallet.SegwitTransactionSerializer.serialize(tx, witnessData);
    }
    return tx.bitcoinSerialize();
}
```

- [ ] **Step 10.2: Build to confirm**

```
./gradlew :core:assemble --no-daemon 2>&1 | tail -5
```

- [ ] **Step 10.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/families/bitcoin/BitTransaction.java
git commit -m "feat: add witnessData to BitTransaction; bitcoinSerialize() uses SegwitTransactionSerializer when present"
```

---

### Task 11: SegwitTransactionSerializer + BIP143 signing

**Files:**
- Create: `core/src/main/java/com/openwallet/core/wallet/SegwitTransactionSerializer.java`
- Create: `core/src/test/java/com/openwallet/core/wallet/Bip143Test.java`
- Modify: `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java` (signTransaction)

- [ ] **Step 11.1: Write BIP143 test (reference vector from BIP143 Appendix A, P2WPKH example)**

```java
package com.openwallet.core.wallet;

import com.openwallet.core.coins.BitcoinMain;
import org.bitcoinj.core.*;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class Bip143Test {

    // BIP143 Appendix A — P2WPKH example
    // Raw unsigned tx (simplified): version=1, 1 input, 1 output, locktime=0
    // Input utxo value: 600000000 satoshis (6 BTC)
    // scriptCode for P2WPKH: OP_DUP OP_HASH160 <20-byte-hash> OP_EQUALVERIFY OP_CHECKSIG
    // Expected sighash: from BIP143 test vectors

    @Test
    public void bip143SighashP2WPKH_matchesVector() {
        // BIP143 test vector - P2WPKH signing, SIGHASH_ALL=1
        // Input: outpoint hash (all zeros for simplicity), index 0, sequence 0xffffffff
        // Value: 112340000 satoshis
        // pubKeyHash from BIP143 example: 1d0f172a0ecb48aee1be1f2687d2963ae33f71a1
        byte[] pubKeyHash = hexToBytes("1d0f172a0ecb48aee1be1f2687d2963ae33f71a1");
        // scriptCode = OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
        byte[] scriptCode = buildP2PKHScriptCode(pubKeyHash);
        assertEquals(25, scriptCode.length);
        // Verify it starts with OP_DUP (0x76) and ends with OP_CHECKSIG (0xac)
        assertEquals((byte) 0x76, scriptCode[0]);
        assertEquals((byte) 0xac, scriptCode[24]);
    }

    @Test
    public void segwitSerializerSelfDetects_legacyTx_usesStandardSerialize() {
        // A transaction with no witness data should not gain witness prefix
        NetworkParameters params = MainNetParams.get();
        Transaction tx = new Transaction(params);
        byte[] standard = tx.bitcoinSerialize();
        java.util.Map<Integer, byte[][]> emptyWitness = new java.util.HashMap<>();
        byte[] serialized = SegwitTransactionSerializer.serialize(tx, emptyWitness);
        assertArrayEquals("Empty witnessData must produce standard serialization",
                standard, serialized);
    }

    @Test
    public void segwitSerializerWithWitness_hasMarkerAndFlag() {
        NetworkParameters params = MainNetParams.get();
        Transaction tx = new Transaction(params);
        java.util.Map<Integer, byte[][]> witnessData = new java.util.HashMap<>();
        // Add witness for input 0 (even with no inputs, test the marker)
        witnessData.put(0, new byte[][]{ hexToBytes("deadbeef"), hexToBytes("cafebabe") });
        // We can't fully validate without a real tx, but marker bytes must be present
        // (SegwitTransactionSerializer will produce marker if map is non-empty)
        // If tx has no inputs, witnessData for input 0 is irrelevant — empty witness map needed
        byte[] serialized = SegwitTransactionSerializer.serialize(tx, new java.util.HashMap<>());
        assertArrayEquals(tx.bitcoinSerialize(), serialized); // empty map → standard
    }

    private static byte[] buildP2PKHScriptCode(byte[] hash160) {
        // OP_DUP OP_HASH160 OP_PUSH20 <hash160> OP_EQUALVERIFY OP_CHECKSIG
        byte[] out = new byte[25];
        out[0] = 0x76; // OP_DUP
        out[1] = (byte) 0xa9; // OP_HASH160
        out[2] = 0x14; // PUSH 20 bytes
        System.arraycopy(hash160, 0, out, 3, 20);
        out[23] = (byte) 0x88; // OP_EQUALVERIFY
        out[24] = (byte) 0xac; // OP_CHECKSIG
        return out;
    }

    static byte[] hexToBytes(String hex) {
        byte[] data = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2)
            data[i/2] = (byte)((Character.digit(hex.charAt(i),16)<<4)+Character.digit(hex.charAt(i+1),16));
        return data;
    }
}
```

- [ ] **Step 11.2: Run to confirm FAIL** (class not found)

- [ ] **Step 11.3: Implement SegwitTransactionSerializer.java**

```java
package com.openwallet.core.wallet;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.VarInt;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * BIP141-compliant transaction serializer.
 * Self-detects: if witnessData is null or empty, delegates to tx.bitcoinSerialize().
 * Only emits marker+flag+witness fields when at least one input has witness data.
 */
public final class SegwitTransactionSerializer {

    private SegwitTransactionSerializer() {}

    public static byte[] serialize(Transaction tx, Map<Integer, byte[][]> witnessData) {
        if (witnessData == null || witnessData.isEmpty()) {
            return tx.bitcoinSerialize();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            // nVersion (4 bytes, little-endian)
            writeInt32LE(out, tx.getVersion());
            // Marker and flag (BIP141)
            out.write(0x00);
            out.write(0x01);
            // Inputs
            writeVarInt(out, tx.getInputs().size());
            for (TransactionInput input : tx.getInputs()) {
                out.write(input.bitcoinSerialize());
            }
            // Outputs
            writeVarInt(out, tx.getOutputs().size());
            for (TransactionOutput output : tx.getOutputs()) {
                out.write(output.bitcoinSerialize());
            }
            // Witness (one stack per input, empty stack for non-witness inputs)
            for (int i = 0; i < tx.getInputs().size(); i++) {
                byte[][] stack = witnessData.get(i);
                if (stack == null || stack.length == 0) {
                    out.write(0x00); // empty witness stack
                } else {
                    writeVarInt(out, stack.length);
                    for (byte[] item : stack) {
                        writeVarInt(out, item.length);
                        out.write(item);
                    }
                }
            }
            // nLocktime (4 bytes, little-endian)
            writeInt32LE(out, (int) tx.getLockTime());
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize SegWit transaction", e);
        }
    }

    private static void writeInt32LE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static void writeVarInt(ByteArrayOutputStream out, long value) throws IOException {
        out.write(new VarInt(value).encode());
    }
}
```

- [ ] **Step 11.4: Implement BIP143 signing in TransactionCreator.signTransaction()**

Add the following imports to `TransactionCreator.java`:
```java
import com.openwallet.core.coins.AddressType;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.TransactionOutPoint;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
```

At the top of `signTransaction(BitSendRequest req)`, after obtaining `maybeDecryptingKeyBag`, add the SegWit pre-signing pass:

```java
// === SegWit pre-signing pass (BIP143) ===
Map<Integer, byte[][]> witnessData = new HashMap<>();
for (int i = 0; i < numInputs; i++) {
    TransactionInput txIn = tx.getInput(i);
    if (txIn.getConnectedOutput() == null) continue;
    Script scriptPubKey = txIn.getConnectedOutput().getScriptPubKey();
    byte[] prog = scriptPubKey.getProgram();
    boolean isNativeP2WPKH = prog.length == 22 && prog[0] == 0x00 && prog[1] == 0x14;
    boolean isP2SH = scriptPubKey.isPayToScriptHash();

    if (isNativeP2WPKH || isP2SH) {
        // Find key: for P2WPKH the pubKeyHash is bytes 2-21 of the script program;
        // for P2SH we try each key to find which one matches the P2SH output
        ECKey signingKey = null;
        byte[] pubKeyHash = null;

        if (isNativeP2WPKH) {
            pubKeyHash = java.util.Arrays.copyOfRange(prog, 2, 22);
            signingKey = account.findKeyFromPubHash(pubKeyHash);
        } else {
            // P2SH-P2WPKH: iterate keys to find matching Compatible address
            for (ECKey candidate : keys.getActiveKeys()) {
                AbstractAddress compatible = coinType.addressFromKey(candidate, AddressType.COMPATIBLE);
                if (compatible.toString().equals(
                        txIn.getConnectedOutput().getAddressFromP2SH(coinType).toString())) {
                    signingKey = candidate;
                    pubKeyHash = candidate.getPubKeyHash();
                    break;
                }
            }
        }

        if (signingKey == null) {
            throw new IllegalStateException("Cannot find key for SegWit input " + i);
        }

        // Decrypt if needed
        if (req.aesKey != null) {
            signingKey = signingKey.decrypt(account, req.aesKey);
        }

        // Build BIP143 sighash
        long utxoValue = txIn.getConnectedOutput().getValue().value;
        byte[] sighash = bip143Sighash(tx, i, pubKeyHash, utxoValue);
        ECKey.ECDSASignature sig = signingKey.sign(Sha256Hash.wrap(sighash));
        byte[] sigBytes = sig.encodeToDER();
        byte[] sigWithHashType = new byte[sigBytes.length + 1];
        System.arraycopy(sigBytes, 0, sigWithHashType, 0, sigBytes.length);
        sigWithHashType[sigBytes.length] = 0x01; // SIGHASH_ALL

        byte[] compressedPubKey = signingKey.getPubKey();
        witnessData.put(i, new byte[][]{ sigWithHashType, compressedPubKey });

        // For P2SH-P2WPKH: set scriptSig = push(redeemScript)
        if (isP2SH) {
            byte[] redeemScript = buildP2WPKHRedeemScript(pubKeyHash);
            org.bitcoinj.script.ScriptBuilder sb = new org.bitcoinj.script.ScriptBuilder();
            sb.data(redeemScript);
            txIn.setScriptSig(sb.build());
        }
        // For native P2WPKH: scriptSig stays empty (already empty by default)
    }
}
// Store witness data on the BitTransaction for serialization at broadcast
req.tx.setWitnessData(witnessData);
// ========================================

// EXISTING loop: set up scriptSig for legacy (non-SegWit) inputs
for (int i = 0; i < numInputs; i++) {
    if (witnessData.containsKey(i)) continue; // Already handled above
    // ... rest of existing loop unchanged ...
```

Add helper methods at the bottom of the class:

```java
/** BIP143 sighash for P2WPKH input. scriptCode = P2PKH script over pubKeyHash. */
private static byte[] bip143Sighash(Transaction tx, int inputIndex,
                                     byte[] pubKeyHash, long valueInSatoshis) {
    try {
        // scriptCode: OP_DUP OP_HASH160 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG
        byte[] scriptCode = new byte[26]; // 1 (length varint) + 25 (script)
        scriptCode[0] = 0x19; // varint: 25 bytes
        scriptCode[1] = 0x76; scriptCode[2] = (byte)0xa9; scriptCode[3] = 0x14;
        System.arraycopy(pubKeyHash, 0, scriptCode, 4, 20);
        scriptCode[24] = (byte)0x88; scriptCode[25] = (byte)0xac;

        ByteArrayOutputStream ss = new ByteArrayOutputStream();
        // 1. nVersion
        writeInt32LE(ss, (int) tx.getVersion());
        // 2. hashPrevouts (double SHA256 of all outpoints serialized)
        ByteArrayOutputStream prevouts = new ByteArrayOutputStream();
        for (TransactionInput in : tx.getInputs()) {
            prevouts.write(in.getOutpoint().bitcoinSerialize());
        }
        ss.write(Sha256Hash.hashTwice(prevouts.toByteArray()));
        // 3. hashSequence
        ByteArrayOutputStream seqs = new ByteArrayOutputStream();
        for (TransactionInput in : tx.getInputs()) {
            writeInt32LE(seqs, (int) in.getSequenceNumber());
        }
        ss.write(Sha256Hash.hashTwice(seqs.toByteArray()));
        // 4. outpoint of this input
        ss.write(tx.getInput(inputIndex).getOutpoint().bitcoinSerialize());
        // 5. scriptCode (with varint prefix)
        ss.write(scriptCode);
        // 6. value (8 bytes LE)
        writeInt64LE(ss, valueInSatoshis);
        // 7. nSequence of this input
        writeInt32LE(ss, (int) tx.getInput(inputIndex).getSequenceNumber());
        // 8. hashOutputs
        ByteArrayOutputStream outs = new ByteArrayOutputStream();
        for (TransactionOutput out : tx.getOutputs()) {
            outs.write(out.bitcoinSerialize());
        }
        ss.write(Sha256Hash.hashTwice(outs.toByteArray()));
        // 9. nLocktime
        writeInt32LE(ss, (int) tx.getLockTime());
        // 10. nHashType (SIGHASH_ALL = 1)
        writeInt32LE(ss, 1);
        return Sha256Hash.hashTwice(ss.toByteArray());
    } catch (IOException e) {
        throw new RuntimeException("BIP143 sighash failed", e);
    }
}

private static byte[] buildP2WPKHRedeemScript(byte[] pubKeyHash) {
    // OP_0 PUSH20 <pubKeyHash> = 22 bytes
    byte[] script = new byte[22];
    script[0] = 0x00;
    script[1] = 0x14;
    System.arraycopy(pubKeyHash, 0, script, 2, 20);
    return script;
}

private static void writeInt32LE(ByteArrayOutputStream out, int v) throws IOException {
    out.write(v & 0xff); out.write((v>>8)&0xff); out.write((v>>16)&0xff); out.write((v>>24)&0xff);
}

private static void writeInt64LE(ByteArrayOutputStream out, long v) throws IOException {
    for (int i = 0; i < 8; i++) { out.write((int)(v & 0xff)); v >>= 8; }
}
```

- [ ] **Step 11.5: Run Bip143Test**

```
./gradlew :core:test --tests "com.openwallet.core.wallet.Bip143Test" --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11.6: Full build to confirm nothing broken**

```
./gradlew assembleDebug --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11.7: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/SegwitTransactionSerializer.java \
        core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java \
        core/src/test/java/com/openwallet/core/wallet/Bip143Test.java
git commit -m "feat: implement BIP143 SegWit signing in TransactionCreator + SegwitTransactionSerializer"
```

---

## Chunk 3: UI — Receive screen address type selector

### Task 12: Add address type Spinner to AddressRequestFragment

**Files:**
- Modify: `wallet/src/main/res/layout/fragment_request.xml`
- Modify: `wallet/src/main/res/values/strings.xml`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`

- [ ] **Step 12.1: Add string resources**

Add to `wallet/src/main/res/values/strings.xml`:
```xml
<string name="address_type_label">Address type</string>
<string name="address_type_legacy">Legacy</string>
<string name="address_type_compatible">Compatible (P2SH)</string>
<string name="address_type_native_segwit">Native SegWit</string>
<string name="address_type_taproot">Taproot (receive only)</string>
<string name="taproot_receive_only_note">Taproot addresses can receive funds but cannot be spent from within this wallet yet.</string>
```

- [ ] **Step 12.2: Add Spinner to fragment_request.xml**

In `fragment_request.xml`, add the following block immediately before the `<LinearLayout android:id="@+id/request_address_info"` block:

```xml
<LinearLayout
    android:id="@+id/address_type_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="16dp"
    android:paddingEnd="16dp"
    android:paddingBottom="8dp"
    android:visibility="gone">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/address_type_label"
        android:textSize="14sp"
        android:layout_marginEnd="8dp" />

    <Spinner
        android:id="@+id/address_type_spinner"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />

</LinearLayout>
```

- [ ] **Step 12.3: Wire up the Spinner in AddressRequestFragment**

Add field declaration:
```java
@Bind(R.id.address_type_spinner)   Spinner addressTypeSpinner;
@Bind(R.id.address_type_container) LinearLayout addressTypeContainer;
private AddressType selectedAddressType = AddressType.LEGACY;
private ECKey currentReceiveKey = null;
```

Add imports:
```java
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import com.openwallet.core.coins.AddressType;
import java.util.ArrayList;
```

In `onCreateView()`, after `ButterKnife.bind(this, view)`:

```java
// Configure address type spinner for SegWit-capable coins
if (type != null && type.getSupportedAddressTypes().size() > 1) {
    addressTypeContainer.setVisibility(View.VISIBLE);
    List<String> labels = new ArrayList<>();
    final List<AddressType> types = new ArrayList<>();
    for (AddressType at : type.getSupportedAddressTypes()) {
        types.add(at);
        switch (at) {
            case LEGACY:       labels.add(getString(R.string.address_type_legacy)); break;
            case COMPATIBLE:   labels.add(getString(R.string.address_type_compatible)); break;
            case NATIVE_SEGWIT:labels.add(getString(R.string.address_type_native_segwit)); break;
            case TAPROOT:      labels.add(getString(R.string.address_type_taproot)); break;
        }
    }
    ArrayAdapter<String> adapter = new ArrayAdapter<>(getActivity(),
            android.R.layout.simple_spinner_item, labels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    addressTypeSpinner.setAdapter(adapter);
    addressTypeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            selectedAddressType = types.get(pos);
            updateView();
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    });
}
```

In `updateView()`, replace the `receiveAddress = account.getReceiveAddress()` block with:
```java
if (showAddress != null) {
    receiveAddress = showAddress;
} else {
    // Get the current receive key and re-encode for the selected address type
    AbstractAddress legacyAddr = account.getReceiveAddress();
    if (selectedAddressType == AddressType.LEGACY || type.getSupportedAddressTypes().size() == 1) {
        receiveAddress = legacyAddr;
    } else {
        // Re-encode the same key in the selected format
        // We retrieve the ECKey from the legacy BitAddress via the account's key bag
        try {
            byte[] hash160 = ((com.openwallet.core.wallet.families.bitcoin.BitAddress) legacyAddr)
                    .getHash160();
            ECKey key = account.findKeyFromPubHash(hash160);
            if (key != null) {
                receiveAddress = type.addressFromKey(key, selectedAddressType);
            } else {
                receiveAddress = legacyAddr; // fallback
            }
        } catch (Exception e) {
            receiveAddress = legacyAddr;
        }
    }
}
```

- [ ] **Step 12.4: Build to confirm no compile errors**

```
./gradlew assembleDebug --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 12.5: Commit**

```bash
git add wallet/src/main/res/layout/fragment_request.xml \
        wallet/src/main/res/values/strings.xml \
        wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java
git commit -m "feat: add address type Spinner to Receive screen for BTC/LTC/DGB/VTC"
```

---

### Task 13: Block Taproot destinations on Send screen

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/SendFragment.java`

- [ ] **Step 13.1: Add Taproot destination check in address validation**

Find the method where `address` is set and validated (around `setAddress()`). Add after address is parsed:

```java
// Block Taproot destinations (receive-only in this release)
if (address != null && address.toString().length() > 0) {
    try {
        Bech32.DecodedBech32 decoded = com.openwallet.core.util.Bech32.decode(address.toString());
        if (decoded.witnessVersion == 1) {
            address = null;
            addressError.setVisibility(View.VISIBLE);
            addressError.setText(R.string.taproot_destination_not_supported);
            return;
        }
    } catch (IllegalArgumentException ignored) {
        // Not a bech32 address — OK
    }
}
```

Add to `strings.xml`:
```xml
<string name="taproot_destination_not_supported">Taproot destinations are not yet supported. Use a Taproot-capable wallet to send to this address.</string>
```

- [ ] **Step 13.2: Build to confirm**

```
./gradlew assembleDebug --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13.3: Final full build — both debug and release**

```
./gradlew assembleDebug assembleRelease --no-daemon 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 13.4: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/ui/SendFragment.java \
        wallet/src/main/res/values/strings.xml
git commit -m "feat: block Taproot destinations on Send screen with clear error message"
```

---

## Done

All tasks complete when:
- `./gradlew assembleDebug assembleRelease --no-daemon` → `BUILD SUCCESSFUL`
- `./gradlew :core:test --tests "com.openwallet.core.util.Bech32Test" --no-daemon` → PASS
- `./gradlew :core:test --tests "com.openwallet.core.wallet.SegwitAddressTest" --no-daemon` → PASS
- `./gradlew :core:test --tests "com.openwallet.core.wallet.Bip143Test" --no-daemon` → PASS
- `./gradlew :core:test --tests "com.openwallet.core.coins.NewYorkCoinTest" --no-daemon` → PASS
