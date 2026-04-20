# Address Formats, SimpleSwap & NYC Fix — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix NYC address prefix, replace ShapeShift with SimpleSwap WebView, and add a BIP address-type selector (Legacy / Compatibility / Native SegWit / Taproot) to the Receive tab for BTC, LTC, DGB, and VTC.

**Architecture:** The core module gains three new types (`AddressType` enum, `Bech32` utility, `AddressEncoder` utility) plus per-coin declarations. The wallet module gains a new `SimpleSwapFragment` (WebView), a rewritten `TradeActivity`, and an address-type `RadioGroup` in `AddressRequestFragment`. No new key-derivation paths — all address types are encodings of the same BIP44 key already in the keychain.

**Tech Stack:** Java 8, Android SDK 33 (minSdk 26), bitcoinj 0.12.3 (bundled jar), SpongyCastle 1.51.0.0, appcompat-v7:28, Butterknife 7.

---

## File Map

### Core module — new files
| Path | Responsibility |
|------|----------------|
| `core/src/main/java/com/openwallet/core/coins/AddressType.java` | Enum of four address format types |
| `core/src/main/java/com/openwallet/core/util/Bech32.java` | Bech32 / Bech32m encoder (self-contained) |
| `core/src/main/java/com/openwallet/core/util/AddressEncoder.java` | Encodes an ECKey as any supported address type |
| `core/src/main/java/com/openwallet/core/exceptions/UnsupportedAddressTypeException.java` | Checked exception for unsupported type requests |
| `core/src/test/java/com/openwallet/core/util/Bech32Test.java` | Unit tests for Bech32 encoding |
| `core/src/test/java/com/openwallet/core/util/AddressEncoderTest.java` | Unit tests for AddressEncoder |

### Core module — modified files
| Path | Change |
|------|--------|
| `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java` | `addressHeader` 52 → 60 |
| `core/src/main/java/com/openwallet/core/coins/CoinType.java` | Add `bech32Hrp`, `supportedAddressTypes`, two getters |
| `core/src/main/java/com/openwallet/core/coins/BitcoinMain.java` | Set bech32Hrp + full type list |
| `core/src/main/java/com/openwallet/core/coins/LitecoinMain.java` | `p2shHeader` 5 → 50; set bech32Hrp + type list |
| `core/src/main/java/com/openwallet/core/coins/DigibyteMain.java` | Set bech32Hrp + type list |
| `core/src/main/java/com/openwallet/core/coins/VertcoinMain.java` | Set bech32Hrp + type list |

### Wallet module — new files
| Path | Responsibility |
|------|----------------|
| `wallet/src/main/java/com/openwallet/wallet/ui/SimpleSwapFragment.java` | WebView loading SimpleSwap pre-filled URL |

### Wallet module — modified files
| Path | Change |
|------|--------|
| `wallet/src/main/java/com/openwallet/wallet/ui/TradeActivity.java` | Replace all ShapeShift logic; host SimpleSwapFragment |
| `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java` | Add address-type RadioGroup |
| `wallet/src/main/res/layout/fragment_request.xml` | Add RadioGroup |
| `wallet/src/main/res/values/strings.xml` | Replace ShapeShift strings |
| `wallet/src/main/java/com/openwallet/wallet/ui/PayWithDialog.java` | Replace ShapeShift dialog with SimpleSwap info |
| `wallet/src/main/res/layout/powered_by_shapeshift.xml` | Update text to reference SimpleSwap (the `<include>` in `select_pay_with.xml` stays; only the included file's content changes) |

### Wallet module — files to delete
| Path | Reason safe to delete |
|------|----------------------|
| `wallet/src/main/java/com/openwallet/wallet/ui/TradeSelectFragment.java` | Only referenced by the old `TradeActivity`; no other callers |

**Files kept (NOT deleted despite spec mention):**
- `MakeTransactionFragment.java` — still used by `SweepWalletActivity.onSendTransaction()`; removing it would break sweep-wallet signing.
- `TradeStatusFragment.java` — still referenced by `TradeStatusActivity`.
- `TradeStatusActivity.java` — still started by `ExchangeHistoryFragment.onListItemClick()` to display historical ShapeShift entries; removing it causes a compile error in that fragment.

---

## Chunk 1: Core Module — NYC Fix, Address Types, Bech32, Encoder, Coin Declarations

### Task 1: Fix NYC Address Prefix

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java:9`

- [ ] **Step 1.1: Change `addressHeader`**

  In `NewYorkCoinMain.java`, change line 9:
  ```java
  // BEFORE:
  addressHeader = 52;           // 0x34 → N... prefix
  // AFTER:
  addressHeader = 60;           // 0x3C → R... prefix
  ```
  The existing `acceptableAddressCodes = new int[] { addressHeader, p2shHeader };` on line 11 uses the field by name, so it automatically picks up the new value. Do NOT touch line 11.

- [ ] **Step 1.2: Build and verify NYC produces R… addresses**

  ```
  cd D:\NewYorkCoin_2026\nyc-openwallet-android
  gradlew.bat :core:test
  ```
  Expected: all existing tests pass (no test covers the NYC address specifically — that's fine; the fix is trivially correct by the base58check table).

- [ ] **Step 1.3: Commit**

  ```
  git add core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java
  git commit -m "fix: NYC address prefix 52→60 (N→R) to match mainnet"
  ```

---

### Task 2: AddressType Enum

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/AddressType.java`

- [ ] **Step 2.1: Create the enum**

  ```java
  // core/src/main/java/com/openwallet/core/coins/AddressType.java
  package com.openwallet.core.coins;

  /**
   * Bitcoin-family address encoding types.
   * LEGACY        = P2PKH  (base58check, e.g. 1..., L..., R...)
   * COMPATIBILITY = P2SH-P2WPKH (base58check p2sh, e.g. 3..., M...)
   * NATIVE_SEGWIT = P2WPKH bech32 (e.g. bc1q..., ltc1q...)
   * TAPROOT       = P2TR bech32m  (e.g. bc1p...)
   */
  public enum AddressType {
      LEGACY,
      COMPATIBILITY,
      NATIVE_SEGWIT,
      TAPROOT
  }
  ```

- [ ] **Step 2.2: Build**

  ```
  gradlew.bat :core:compileJava
  ```
  Expected: BUILD SUCCESSFUL

---

### Task 3: UnsupportedAddressTypeException

**Files:**
- Create: `core/src/main/java/com/openwallet/core/exceptions/UnsupportedAddressTypeException.java`

- [ ] **Step 3.1: Create the exception**

  ```java
  // core/src/main/java/com/openwallet/core/exceptions/UnsupportedAddressTypeException.java
  package com.openwallet.core.exceptions;

  import com.openwallet.core.coins.AddressType;
  import com.openwallet.core.coins.CoinType;

  public class UnsupportedAddressTypeException extends RuntimeException {
      public UnsupportedAddressTypeException(AddressType type, CoinType coin) {
          super(coin.getName() + " does not support address type: " + type);
      }
  }
  ```

- [ ] **Step 3.2: Build**

  ```
  gradlew.bat :core:compileJava
  ```
  Expected: BUILD SUCCESSFUL

---

### Task 4: CoinType Additions

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinType.java`

The class currently has protected fields and getters for most config. We need to add `bech32Hrp` and `supportedAddressTypes`.

- [ ] **Step 4.1: Add imports to CoinType.java**

  The file already imports `java.util.List`. Add the following to the import block (note: `AddressType` is in the same package so does **not** need an import):
  ```java
  import com.google.common.collect.ImmutableList;
  ```

- [ ] **Step 4.2: Add the two new fields**

  After the existing `protected byte[] signedMessageHeader;` line, add:
  ```java
  protected String bech32Hrp = null;
  protected List<AddressType> supportedAddressTypes = ImmutableList.of(AddressType.LEGACY);
  ```

- [ ] **Step 4.3: Add the two new getters**

  After the existing `canHandleMessages()` method (approximately line 118), add:
  ```java
  /** Returns the bech32 human-readable part for this coin, or null if not supported. */
  @Nullable
  public String getBech32Hrp() {
      return bech32Hrp;
  }

  /** Returns the address types this coin's Receive tab supports. Always includes LEGACY. */
  public List<AddressType> getSupportedAddressTypes() {
      return supportedAddressTypes;
  }
  ```

- [ ] **Step 4.4: Build**

  ```
  gradlew.bat :core:compileJava
  ```
  Expected: BUILD SUCCESSFUL

- [ ] **Step 4.5: Commit AddressType and CoinType changes**

  ```
  git add core/src/main/java/com/openwallet/core/coins/AddressType.java
  git add core/src/main/java/com/openwallet/core/coins/CoinType.java
  git commit -m "feat: add AddressType enum and bech32Hrp/supportedAddressTypes fields to CoinType"
  ```

---

### Task 5: Bech32 Utility + Tests

**Files:**
- Create: `core/src/main/java/com/openwallet/core/util/Bech32.java`
- Create: `core/src/test/java/com/openwallet/core/util/Bech32Test.java`

The Bech32 utility is self-contained (no external dependencies beyond standard Java).

- [ ] **Step 5.1: Write the failing tests first**

  ```java
  // core/src/test/java/com/openwallet/core/util/Bech32Test.java
  package com.openwallet.core.util;

  import org.junit.Test;
  import static org.junit.Assert.*;

  public class Bech32Test {

      // BIP-0173 test vector: bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4
      // witness v0, hrp=bc, payload=751e76e8199196f38d9979166e7ae0d2e7c5a974 (20 bytes)
      @Test
      public void testNativeSegWitBitcoin() {
          byte[] hash160 = hexToBytes("751e76e8199196f38d9979166e7ae0d2e7c5a974");
          String addr = Bech32.encode("bc", 0, hash160);
          assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", addr);
      }

      // BIP-0341 test vector: bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297
      // witness v1, hrp=bc, 32-byte payload
      @Test
      public void testTaprootBitcoin() {
          byte[] xOnly = hexToBytes("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c");
          String addr = Bech32.encode("bc", 1, xOnly);
          assertEquals("bc1p5d7rjq7g6rdk2yhzks9smlaqtedr4dekq08ge8ztwac72sfr9rusxg3297", addr);
      }

      // BIP-0141 Litecoin: ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9
      @Test
      public void testNativeSegWitLitecoin() {
          byte[] hash160 = hexToBytes("751e76e8199196f38d9979166e7ae0d2e7c5a974");
          String addr = Bech32.encode("ltc", 0, hash160);
          assertEquals("ltc1qw508d6qejxtdg4y5r3zarvary0c5xw7kgmn4n9", addr);
      }

      // Witness version out of range should throw
      @Test(expected = IllegalArgumentException.class)
      public void testInvalidWitnessVersionThrows() {
          Bech32.encode("bc", 17, new byte[20]);
      }

      // Payload too short should throw
      @Test(expected = IllegalArgumentException.class)
      public void testPayloadTooShortThrows() {
          Bech32.encode("bc", 0, new byte[1]);
      }

      private static byte[] hexToBytes(String hex) {
          int len = hex.length();
          byte[] data = new byte[len / 2];
          for (int i = 0; i < len; i += 2) {
              data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                      + Character.digit(hex.charAt(i + 1), 16));
          }
          return data;
      }
  }
  ```

- [ ] **Step 5.2: Run tests to verify they fail**

  ```
  gradlew.bat :core:test --tests "com.openwallet.core.util.Bech32Test"
  ```
  Expected: FAIL — `Bech32` class not found

- [ ] **Step 5.3: Implement Bech32.java**

  ```java
  // core/src/main/java/com/openwallet/core/util/Bech32.java
  package com.openwallet.core.util;

  import java.util.ArrayList;
  import java.util.List;

  /**
   * Bech32 and Bech32m encoder for witness address types.
   *
   * Bech32  (BIP-0173): used for witness version 0 (P2WPKH, P2WSH).
   * Bech32m (BIP-0350): used for witness version 1+ (P2TR).
   */
  public final class Bech32 {
      private Bech32() {}

      private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
      private static final int BECH32_CONST  = 1;
      private static final int BECH32M_CONST = 0x2bc830a3;
      private static final int[] GEN = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};

      /** Minimum witness program length (bytes). Ref BIP-141: 2..40 bytes. */
      private static final int MIN_DATA_LEN = 2;
      private static final int MAX_DATA_LEN = 40;

      /**
       * Encodes a witness program as a bech32/bech32m address.
       *
       * @param hrp            human-readable part (e.g. "bc", "ltc", "dgb")
       * @param witnessVersion 0 for P2WPKH/P2WSH (bech32), 1 for P2TR (bech32m)
       * @param witnessProgram raw witness program bytes (20 bytes for P2WPKH, 32 for P2TR)
       * @return lowercase bech32/bech32m string
       */
      public static String encode(String hrp, int witnessVersion, byte[] witnessProgram) {
          if (witnessVersion < 0 || witnessVersion > 16) {
              throw new IllegalArgumentException("Witness version out of range: " + witnessVersion);
          }
          if (witnessProgram.length < MIN_DATA_LEN || witnessProgram.length > MAX_DATA_LEN) {
              throw new IllegalArgumentException(
                      "Witness program length out of range: " + witnessProgram.length);
          }

          // Convert 8-bit witness program to 5-bit groups
          int[] conv = convertBits(witnessProgram, 8, 5, true);

          // Build data array: [witnessVersion, ...conv]
          int[] values = new int[1 + conv.length];
          values[0] = witnessVersion;
          System.arraycopy(conv, 0, values, 1, conv.length);

          int constant = (witnessVersion == 0) ? BECH32_CONST : BECH32M_CONST;
          int[] checksum = createChecksum(hrp, values, constant);

          // Assemble: hrp + '1' + values_chars + checksum_chars
          StringBuilder sb = new StringBuilder(hrp).append('1');
          for (int v : values)    sb.append(CHARSET.charAt(v));
          for (int c : checksum)  sb.append(CHARSET.charAt(c));
          return sb.toString();
      }

      // ── Internal helpers ──────────────────────────────────────────────────────

      private static int[] createChecksum(String hrp, int[] data, int constant) {
          int[] enc = cat(hrpExpand(hrp), data, new int[6]);
          int mod = polymod(enc) ^ constant;
          int[] checksum = new int[6];
          for (int i = 0; i < 6; i++) {
              checksum[i] = (mod >> (5 * (5 - i))) & 31;
          }
          return checksum;
      }

      private static int polymod(int[] values) {
          int c = 1;
          for (int v : values) {
              int c0 = (c >>> 25) & 0xff;
              c = ((c & 0x1ffffff) << 5) ^ v;
              for (int i = 0; i < 5; i++) {
                  if (((c0 >> i) & 1) != 0) c ^= GEN[i];
              }
          }
          return c;
      }

      private static int[] hrpExpand(String hrp) {
          int len = hrp.length();
          int[] ret = new int[len * 2 + 1];
          for (int i = 0; i < len; i++) {
              ret[i]           = hrp.charAt(i) >> 5;
              ret[i + len + 1] = hrp.charAt(i) & 31;
          }
          ret[len] = 0;
          return ret;
      }

      /**
       * Converts a byte array from {@code fromBits}-bit groups to {@code toBits}-bit groups.
       * Pads if requested.
       */
      static int[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
          int acc = 0, bits = 0;
          int maxv = (1 << toBits) - 1;
          List<Integer> ret = new ArrayList<>();
          for (byte b : data) {
              int value = b & 0xff;
              if ((value >> fromBits) != 0) throw new IllegalArgumentException("Invalid data byte");
              acc = (acc << fromBits) | value;
              bits += fromBits;
              while (bits >= toBits) {
                  bits -= toBits;
                  ret.add((acc >> bits) & maxv);
              }
          }
          if (pad) {
              if (bits > 0) ret.add((acc << (toBits - bits)) & maxv);
          } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
              throw new IllegalArgumentException("Invalid padding in convertBits");
          }
          int[] result = new int[ret.size()];
          for (int i = 0; i < result.length; i++) result[i] = ret.get(i);
          return result;
      }

      private static int[] cat(int[] a, int[] b, int[] c) {
          int[] result = new int[a.length + b.length + c.length];
          System.arraycopy(a, 0, result, 0, a.length);
          System.arraycopy(b, 0, result, a.length, b.length);
          System.arraycopy(c, 0, result, a.length + b.length, c.length);
          return result;
      }
  }
  ```

  **Note on `convertBits` input validation:** When called with raw `hash160` or `xOnly` bytes (values 0–255), the `(value >> fromBits) != 0` check would incorrectly throw for any byte > 127. This check is meant for 5-bit inputs being widened to 8-bit. Since we only call `convertBits(data, 8, 5, true)`, the `fromBits=8` branch will never trigger this validation error — any 8-bit value shifted right by 8 is 0. However, to be safe, remove that check or only apply it when `fromBits < 8`. Here is the corrected `convertBits` with the check removed (it was overly restrictive):

  ```java
  static int[] convertBits(byte[] data, int fromBits, int toBits, boolean pad) {
      int acc = 0, bits = 0;
      int maxv = (1 << toBits) - 1;
      List<Integer> ret = new ArrayList<>();
      for (byte b : data) {
          int value = b & 0xff;
          acc = (acc << fromBits) | value;
          bits += fromBits;
          while (bits >= toBits) {
              bits -= toBits;
              ret.add((acc >> bits) & maxv);
          }
      }
      if (pad) {
          if (bits > 0) ret.add((acc << (toBits - bits)) & maxv);
      } else if (bits >= fromBits || ((acc << (toBits - bits)) & maxv) != 0) {
          throw new IllegalArgumentException("Invalid padding in convertBits");
      }
      int[] result = new int[ret.size()];
      for (int i = 0; i < result.length; i++) result[i] = ret.get(i);
      return result;
  }
  ```
  Use this second version.

- [ ] **Step 5.4: Run tests**

  ```
  gradlew.bat :core:test --tests "com.openwallet.core.util.Bech32Test"
  ```
  Expected: All 5 tests PASS.

  If the Taproot test vector fails, double-check the bech32m constant (`0x2bc830a3`) is used for `witnessVersion > 0` and bech32 constant (`1`) for `witnessVersion == 0`.

- [ ] **Step 5.5: Commit**

  ```
  git add core/src/main/java/com/openwallet/core/util/Bech32.java
  git add core/src/test/java/com/openwallet/core/util/Bech32Test.java
  git commit -m "feat: add Bech32/Bech32m encoder utility with tests"
  ```

---

### Task 6: AddressEncoder Utility + Tests

**Files:**
- Create: `core/src/main/java/com/openwallet/core/util/AddressEncoder.java`
- Create: `core/src/test/java/com/openwallet/core/util/AddressEncoderTest.java`

`AddressEncoder` uses:
- `org.bitcoinj.core.ECKey` — key operations
- `org.bitcoinj.core.Utils.sha256hash160(byte[])` — hash160
- `org.bitcoinj.core.Base58.encode(byte[])` — base58 (no checksum)
- `org.bitcoinj.core.Sha256Hash.createDouble(byte[]).getBytes()` — double-SHA256 for checksum
- `org.spongycastle.math.ec.ECPoint` — for Taproot tweak
- `java.security.MessageDigest` — SHA256 for BIP340 tagged hash
- `com.openwallet.core.util.Bech32` — bech32/bech32m encoding

- [ ] **Step 6.1: Write the failing tests**

  ```java
  // core/src/test/java/com/openwallet/core/util/AddressEncoderTest.java
  package com.openwallet.core.util;

  import com.openwallet.core.coins.AddressType;
  import com.openwallet.core.coins.BitcoinMain;
  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.exceptions.UnsupportedAddressTypeException;

  import org.bitcoinj.core.ECKey;
  import org.bitcoinj.core.Utils;
  import org.junit.Test;

  import java.math.BigInteger;

  import static org.junit.Assert.*;

  public class AddressEncoderTest {

      // Well-known Bitcoin test key: private key = 1 (smallest valid scalar)
      // Compressed public key: 0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798
      // hash160: 751e76e8199196f38d9979166e7ae0d2e7c5a974
      private static final ECKey TEST_KEY = ECKey.fromPrivate(BigInteger.ONE);

      private static final CoinType BTC = BitcoinMain.get();

      @Test
      public void testLegacyBitcoin() {
          // P2PKH: base58check(0x00, hash160(pubkey))
          // Expected: 1BpEi6DfDAUFd153wiGrvkiKW1ECQ8MgFN
          String addr = AddressEncoder.encode(TEST_KEY, BTC, AddressType.LEGACY);
          assertEquals("1BpEi6DfDAUFd153wiGrvkiKW1ECQ8MgFN", addr);
      }

      @Test
      public void testNativeSegWitBitcoin() {
          // P2WPKH: bech32("bc", 0, hash160(pubkey))
          // Expected: bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4
          String addr = AddressEncoder.encode(TEST_KEY, BTC, AddressType.NATIVE_SEGWIT);
          assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", addr);
      }

      @Test
      public void testCompatibilityBitcoin() {
          // P2SH-P2WPKH: base58check(0x05, hash160(OP_0 || PUSH20 || hash160(pubkey)))
          // For privkey=1: redeemScript = 0014 751e76e8199196f38d9979166e7ae0d2e7c5a974
          //                p2shHash = hash160(redeemScript)
          // Expected: 3JvL6Ymt8MVWiCNHC7oWU6nleH6qRvdKKd
          String addr = AddressEncoder.encode(TEST_KEY, BTC, AddressType.COMPATIBILITY);
          assertEquals("3JvL6Ymt8MVWiCNHC7oWU6nleH6qRvdKKd", addr);
      }

      @Test
      public void testTaprootBitcoin() {
          // P2TR BIP86 key-path only, BIP340 x-only tweaked pubkey
          // For privkey=1: well-known BIP86 address
          // Expected: bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9
          String addr = AddressEncoder.encode(TEST_KEY, BTC, AddressType.TAPROOT);
          assertEquals("bc1pmfr3p9j00pfxjh0zmgp99y8zftmd3s5pmedqhyptwy6lm87hf5sspknck9", addr);
      }

      @Test(expected = UnsupportedAddressTypeException.class)
      public void testUnsupportedTypeThrows() {
          // NewYorkCoin doesn't support NATIVE_SEGWIT
          com.openwallet.core.coins.CoinType nyc = com.openwallet.core.coins.NewYorkCoinMain.get();
          AddressEncoder.encode(TEST_KEY, nyc, AddressType.NATIVE_SEGWIT);
      }
  }
  ```

  **Important:** Verify the expected values for `testCompatibilityBitcoin` and `testTaprootBitcoin` against a trusted tool before accepting tests as ground truth. If they differ, update the expected strings — do not modify the implementation to match wrong expected values. You can verify using:
  - `bitcoin-cli` (mainnet) or any BIP86 calculator online
  - The Taproot address for private key `1` can be verified at https://learnmeabitcoin.com/tools/taproot

- [ ] **Step 6.2: Run tests to verify they fail**

  ```
  gradlew.bat :core:test --tests "com.openwallet.core.util.AddressEncoderTest"
  ```
  Expected: FAIL — `AddressEncoder` class not found

- [ ] **Step 6.3: Implement AddressEncoder.java**

  ```java
  // core/src/main/java/com/openwallet/core/util/AddressEncoder.java
  package com.openwallet.core.util;

  import com.openwallet.core.coins.AddressType;
  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.exceptions.UnsupportedAddressTypeException;

  import org.bitcoinj.core.Base58;
  import org.bitcoinj.core.ECKey;
  import org.bitcoinj.core.Sha256Hash;
  import org.bitcoinj.core.Utils;
  import org.spongycastle.math.ec.ECPoint;

  import java.math.BigInteger;
  import java.nio.charset.StandardCharsets;
  import java.security.MessageDigest;
  import java.security.NoSuchAlgorithmException;

  /**
   * Encodes an ECKey as any of the four supported address types.
   *
   * All encodings use the same BIP44 receive key — no separate key derivation paths.
   * Spending from SegWit/Taproot UTXOs requires a future bitcoinj upgrade.
   */
  public final class AddressEncoder {
      private AddressEncoder() {}

      /**
       * Encodes the given key as an address of the requested type for the given coin.
       *
       * @param key     the BIP44 receive key (compressed)
       * @param type    the coin whose network parameters to use
       * @param addrType the desired encoding
       * @return the address string
       * @throws UnsupportedAddressTypeException if addrType is not in type.getSupportedAddressTypes()
       */
      public static String encode(ECKey key, CoinType type, AddressType addrType) {
          if (!type.getSupportedAddressTypes().contains(addrType)) {
              throw new UnsupportedAddressTypeException(addrType, type);
          }

          byte[] pubKey   = key.getPubKey();          // 33-byte compressed
          byte[] hash160  = Utils.sha256hash160(pubKey);

          switch (addrType) {
              case LEGACY:
                  return base58check(type.getAddressHeader(), hash160);

              case COMPATIBILITY: {
                  // P2SH-P2WPKH: hash160 of OP_0 PUSH20 <hash160(pubkey)>
                  byte[] redeemScript = buildP2WPKHScript(hash160);
                  byte[] p2shHash     = Utils.sha256hash160(redeemScript);
                  return base58check(type.getP2SHHeader(), p2shHash);
              }

              case NATIVE_SEGWIT:
                  return Bech32.encode(type.getBech32Hrp(), 0, hash160);

              case TAPROOT:
                  return Bech32.encode(type.getBech32Hrp(), 1, taprootTweak(pubKey));

              default:
                  throw new UnsupportedAddressTypeException(addrType, type);
          }
      }

      // ── P2SH-P2WPKH helper ────────────────────────────────────────────────────

      /** Builds the 22-byte P2WPKH redeem script: OP_0 PUSH20 <hash160> */
      private static byte[] buildP2WPKHScript(byte[] hash160) {
          byte[] script = new byte[22];
          script[0] = 0x00; // OP_0
          script[1] = 0x14; // PUSH 20 bytes
          System.arraycopy(hash160, 0, script, 2, 20);
          return script;
      }

      // ── Base58Check helper ─────────────────────────────────────────────────────

      /**
       * Base58Check encodes version || payload.
       * Base58.encode() in the bundled bitcoinj takes raw bytes without checksum,
       * so we must prepend the version byte and append the 4-byte checksum manually.
       */
      private static String base58check(int version, byte[] payload) {
          // Build versioned payload
          byte[] versionedPayload = new byte[1 + payload.length];
          versionedPayload[0] = (byte) version;
          System.arraycopy(payload, 0, versionedPayload, 1, payload.length);

          // Double-SHA256 checksum (first 4 bytes)
          byte[] checksum = Sha256Hash.createDouble(versionedPayload).getBytes();

          // Assemble: versioned payload + checksum
          byte[] full = new byte[versionedPayload.length + 4];
          System.arraycopy(versionedPayload, 0, full, 0, versionedPayload.length);
          System.arraycopy(checksum, 0, full, versionedPayload.length, 4);

          return Base58.encode(full);
      }

      // ── Taproot BIP86 key tweak ────────────────────────────────────────────────

      /**
       * Computes the BIP86 key-path tweaked x-only public key.
       * Q = P + hashTapTweak(Px) · G
       *
       * Uses SpongyCastle ECPoint arithmetic (already a dependency via the bundled bitcoinj
       * and explicit core/build.gradle dependency on com.madgag.spongycastle:core:1.51.0.0).
       *
       * @param compressedPubKey 33-byte compressed public key
       * @return 32-byte x-only tweaked public key
       */
      private static byte[] taprootTweak(byte[] compressedPubKey) {
          // Decode the point P from the compressed public key
          ECPoint P = ECKey.CURVE.getCurve().decodePoint(compressedPubKey).normalize();

          // x-only representation of P (32 bytes, big-endian)
          byte[] Px = P.getXCoord().getEncoded();

          // BIP340 tagged hash: SHA256(SHA256("TapTweak") || SHA256("TapTweak") || Px)
          byte[] tag     = sha256("TapTweak".getBytes(StandardCharsets.UTF_8));
          byte[] input   = concat(tag, tag, Px);
          byte[] t_bytes = sha256(input);

          // Scalar for the tweak
          BigInteger t = new BigInteger(1, t_bytes);

          // Q = P + t·G
          ECPoint G = ECKey.CURVE.getG();
          ECPoint Q = P.add(G.multiply(t)).normalize();

          // Return x-only coordinate (32 bytes)
          return Q.getXCoord().getEncoded();
      }

      private static byte[] sha256(byte[] data) {
          try {
              return MessageDigest.getInstance("SHA-256").digest(data);
          } catch (NoSuchAlgorithmException e) {
              throw new RuntimeException("SHA-256 not available", e);
          }
      }

      private static byte[] concat(byte[] a, byte[] b, byte[] c) {
          byte[] result = new byte[a.length + b.length + c.length];
          System.arraycopy(a, 0, result, 0, a.length);
          System.arraycopy(b, 0, result, a.length, b.length);
          System.arraycopy(c, 0, result, a.length + b.length, c.length);
          return result;
      }
  }
  ```

- [ ] **Step 6.4: Run tests**

  ```
  gradlew.bat :core:test --tests "com.openwallet.core.util.AddressEncoderTest"
  ```
  Expected: All tests PASS. If the Taproot or Compatibility expected values don't match, update the expected strings in the test file after verifying externally — do not adjust the implementation.

- [ ] **Step 6.5: Commit**

  ```
  git add core/src/main/java/com/openwallet/core/util/AddressEncoder.java
  git add core/src/main/java/com/openwallet/core/exceptions/UnsupportedAddressTypeException.java
  git add core/src/test/java/com/openwallet/core/util/AddressEncoderTest.java
  git commit -m "feat: add AddressEncoder with Legacy/Compat/SegWit/Taproot support"
  ```

---

### Task 7: Per-Coin BIP Address Declarations

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/BitcoinMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/LitecoinMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/DigibyteMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/VertcoinMain.java`

Each coin file is a single constructor method. Add the new fields at the end of the constructor, before the closing brace. Add the import for `ImmutableList` and `AddressType` at the top of each file.

- [ ] **Step 7.1: Update BitcoinMain.java**

  Add import (`AddressType` is same-package — no import needed for it):
  ```java
  import com.google.common.collect.ImmutableList;
  ```

  Add at the end of the constructor (before the closing `}`):
  ```java
  bech32Hrp = "bc";
  supportedAddressTypes = ImmutableList.of(
          AddressType.LEGACY,
          AddressType.COMPATIBILITY,
          AddressType.NATIVE_SEGWIT,
          AddressType.TAPROOT
  );
  ```

- [ ] **Step 7.2: Update LitecoinMain.java**

  Also change `p2shHeader = 5;` → `p2shHeader = 50;` (produces `M…` Compatibility addresses).

  Add import (`AddressType` is same-package — no import needed for it):
  ```java
  import com.google.common.collect.ImmutableList;
  ```

  Change the p2shHeader line:
  ```java
  p2shHeader = 50;  // 0x32 → M... Compatibility addresses (was 5 / 3... style)
  ```

  Add at the end of the constructor:
  ```java
  bech32Hrp = "ltc";
  supportedAddressTypes = ImmutableList.of(
          AddressType.LEGACY,
          AddressType.COMPATIBILITY,
          AddressType.NATIVE_SEGWIT
  );
  ```

  **Note on `acceptableAddressCodes`:** The existing line `acceptableAddressCodes = new int[] { addressHeader, p2shHeader };` references `p2shHeader` by name, so it will automatically pick up the new value 50. Do NOT change the `acceptableAddressCodes` line itself.

- [ ] **Step 7.3: Update DigibyteMain.java**

  Add import (`AddressType` is same-package — no import needed for it):
  ```java
  import com.google.common.collect.ImmutableList;
  ```

  Add at the end of the constructor:
  ```java
  bech32Hrp = "dgb";
  supportedAddressTypes = ImmutableList.of(
          AddressType.LEGACY,
          AddressType.NATIVE_SEGWIT
  );
  ```

- [ ] **Step 7.4: Update VertcoinMain.java**

  Add import (`AddressType` is same-package — no import needed for it):
  ```java
  import com.google.common.collect.ImmutableList;
  ```

  Add at the end of the constructor:
  ```java
  bech32Hrp = "vtc";
  supportedAddressTypes = ImmutableList.of(
          AddressType.LEGACY,
          AddressType.NATIVE_SEGWIT
  );
  ```

- [ ] **Step 7.5: Build all core tests**

  ```
  gradlew.bat :core:test
  ```
  Expected: All tests pass (including new Bech32Test and AddressEncoderTest).

- [ ] **Step 7.6: Commit**

  ```
  git add core/src/main/java/com/openwallet/core/coins/BitcoinMain.java
  git add core/src/main/java/com/openwallet/core/coins/LitecoinMain.java
  git add core/src/main/java/com/openwallet/core/coins/DigibyteMain.java
  git add core/src/main/java/com/openwallet/core/coins/VertcoinMain.java
  git commit -m "feat: add bech32Hrp and supportedAddressTypes to BTC/LTC/DGB/VTC; fix LTC p2shHeader for M… addresses"
  ```

---

## Chunk 2: SimpleSwap WebView — Remove ShapeShift, Add WebView

### Task 8: Delete TradeSelectFragment (Only Truly ShapeShift-Only File)

**File to delete:**
- `wallet/src/main/java/com/openwallet/wallet/ui/TradeSelectFragment.java`

**Files NOT deleted (cross-dependencies exist):**
- `MakeTransactionFragment.java` — still used by `SweepWalletActivity` for sweep-wallet signing. Deleting it breaks `SweepWalletActivity`.
- `TradeStatusFragment.java` — still referenced by `TradeStatusActivity`. Deleting it breaks that class.
- `TradeStatusActivity.java` — still started by `ExchangeHistoryFragment.onListItemClick()`. Deleting it causes a compile error in `ExchangeHistoryFragment`.

**Important:** Rewrite `TradeActivity.java` (Task 9) BEFORE deleting `TradeSelectFragment.java`, since `TradeActivity` currently implements `TradeSelectFragment.Listener`.

- [ ] **Step 8.1: Rewrite TradeActivity.java first (see Task 9)**

  Complete Task 9 Steps 9.1 and 9.2 before this step.

---

### Task 9: SimpleSwapFragment + TradeActivity Rewrite

**Files:**
- Create: `wallet/src/main/java/com/openwallet/wallet/ui/SimpleSwapFragment.java`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/TradeActivity.java`

The new `TradeActivity` is a thin host activity that just shows `SimpleSwapFragment`. The fragment builds the SimpleSwap URL from the current NYC receive address and last active non-NYC account, then loads it in a WebView.

- [ ] **Step 9.1: Create SimpleSwapFragment.java**

  ```java
  // wallet/src/main/java/com/openwallet/wallet/ui/SimpleSwapFragment.java
  package com.openwallet.wallet.ui;

  import android.annotation.SuppressLint;
  import android.content.Intent;
  import android.net.Uri;
  import android.os.Bundle;
  import android.support.v4.app.Fragment;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.webkit.WebResourceRequest;
  import android.webkit.WebSettings;
  import android.webkit.WebView;
  import android.webkit.WebViewClient;

  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.wallet.WalletAccount;
  import com.openwallet.wallet.Constants;
  import com.openwallet.wallet.WalletApplication;

  import java.util.List;

  /**
   * Hosts a WebView that opens SimpleSwap.io pre-filled with:
   *   from = last active non-NYC account symbol (lowercase), or "btc" if none
   *   to   = nyc
   *   addressTo = the user's current NYC receive address (R...)
   */
  public class SimpleSwapFragment extends Fragment {

      private static final String SIMPLESWAP_BASE =
              "https://simpleswap.io/?from=%s&to=nyc&addressTo=%s";

      private static final String NYC_COIN_ID = "newyorkcoin.main";

      private WebView webView;

      public static SimpleSwapFragment newInstance() {
          return new SimpleSwapFragment();
      }

      @Override
      @SuppressLint("SetJavaScriptEnabled")
      public View onCreateView(LayoutInflater inflater, ViewGroup container,
                               Bundle savedInstanceState) {
          webView = new WebView(requireContext());

          WebSettings settings = webView.getSettings();
          settings.setJavaScriptEnabled(true);
          settings.setDomStorageEnabled(true);

          webView.setWebViewClient(new WebViewClient() {
              @Override
              public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                  String url = request.getUrl().toString();
                  // Keep simpleswap.io pages within the WebView;
                  // open all other URLs in the system browser
                  if (url.contains("simpleswap.io")) {
                      return false; // WebView handles it
                  }
                  startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                  return true;
              }
          });

          webView.loadUrl(buildUrl());
          return webView;
      }

      /** Allow WebView back-navigation; return false to signal the activity to finish. */
      public boolean onBackPressed() {
          if (webView != null && webView.canGoBack()) {
              webView.goBack();
              return true;
          }
          return false;
      }

      private String buildUrl() {
          WalletApplication app = (WalletApplication) requireActivity().getApplication();

          // Determine "from" ticker: last active non-NYC account, or "btc" as default
          String fromTicker = "btc";
          List<WalletAccount> accounts = app.getAllAccounts();
          for (int i = accounts.size() - 1; i >= 0; i--) {
              WalletAccount acc = accounts.get(i);
              if (!acc.getCoinType().getId().equals(NYC_COIN_ID)) {
                  fromTicker = acc.getCoinType().getSymbol().toLowerCase();
                  break;
              }
          }

          // Get the NYC receive address
          String nycAddress = "";
          for (WalletAccount acc : accounts) {
              if (acc.getCoinType().getId().equals(NYC_COIN_ID)) {
                  nycAddress = acc.getReceiveAddress().toString();
                  break;
              }
          }

          return String.format(SIMPLESWAP_BASE, fromTicker, nycAddress);
      }
  }
  ```

- [ ] **Step 9.2: Rewrite TradeActivity.java**

  Replace the entire file content:
  ```java
  // wallet/src/main/java/com/openwallet/wallet/ui/TradeActivity.java
  package com.openwallet.wallet.ui;

  import android.os.Bundle;

  import com.openwallet.wallet.R;

  /**
   * Hosts the SimpleSwap WebView exchange screen.
   * ShapeShift integration has been removed; SimpleSwap is loaded in a WebView.
   */
  public class TradeActivity extends BaseWalletActivity {

      private static final String FRAGMENT_TAG = "simpleswap_fragment";

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_fragment_wrapper);

          if (savedInstanceState == null) {
              getSupportFragmentManager().beginTransaction()
                      .add(R.id.container, SimpleSwapFragment.newInstance(), FRAGMENT_TAG)
                      .commit();
          }
      }

      @Override
      public void onBackPressed() {
          SimpleSwapFragment f = (SimpleSwapFragment)
                  getSupportFragmentManager().findFragmentByTag(FRAGMENT_TAG);
          if (f == null || !f.onBackPressed()) {
              super.onBackPressed();
          }
      }
  }
  ```

- [ ] **Step 9.3: Delete TradeSelectFragment (the only fully ShapeShift-only file)**

  ```
  del "wallet\src\main\java\com\openwallet\wallet\ui\TradeSelectFragment.java"
  ```

  Do NOT delete `MakeTransactionFragment.java`, `TradeStatusFragment.java`, or `TradeStatusActivity.java` — they are still used by `SweepWalletActivity` and `ExchangeHistoryFragment`.

- [ ] **Step 9.4: Build to check for compile errors**

  ```
  gradlew.bat :wallet:compileDebugJava
  ```
  Expected: BUILD SUCCESSFUL. The only deleted file (`TradeSelectFragment.java`) was only referenced by the old `TradeActivity`, which is now rewritten and no longer references it.

- [ ] **Step 9.5: Commit**

  ```
  git add wallet/src/main/java/com/openwallet/wallet/ui/SimpleSwapFragment.java
  git add wallet/src/main/java/com/openwallet/wallet/ui/TradeActivity.java
  git rm wallet/src/main/java/com/openwallet/wallet/ui/TradeSelectFragment.java
  git commit -m "feat: replace ShapeShift exchange with SimpleSwap WebView"
  ```

---

### Task 10: PayWithDialog + strings.xml

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/PayWithDialog.java`
- Modify: `wallet/src/main/res/values/strings.xml`
- Modify: `wallet/src/main/res/layout/powered_by_shapeshift.xml`

`select_pay_with.xml` includes `@layout/powered_by_shapeshift`, which has `id="powered_by_shapeshift"`. Rather than changing the include (which would require renaming the layout file and updating `select_pay_with.xml`), update the layout file content and `PayWithDialog.java` to reference SimpleSwap.

- [ ] **Step 10.1: Update strings.xml**

  In `wallet/src/main/res/values/strings.xml`, find and replace the ShapeShift strings block:
  ```xml
  <!-- BEFORE -->
  <!-- ShapeShift -->
  <string name="about_shapeshift_title">About ShapeShift.io</string>
  <string name="about_shapeshift_message">ShapeShift.io is an instant altcoin exchange...</string>

  <!-- AFTER -->
  <!-- SimpleSwap -->
  <string name="about_simpleswap_title">About SimpleSwap.io</string>
  <string name="about_simpleswap_message">SimpleSwap.io is a non-custodial cryptocurrency exchange. No account or registration required. Swap between 700+ coins instantly.</string>
  ```

- [ ] **Step 10.2: Update powered_by_shapeshift.xml**

  Replace the content of `wallet/src/main/res/layout/powered_by_shapeshift.xml`:
  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <TextView xmlns:android="http://schemas.android.com/apk/res/android"
      android:id="@+id/powered_by_shapeshift"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:clickable="true"
      style="@style/SmallHelpText"
      android:gravity="center_vertical"
      android:text="Exchange via SimpleSwap.io" />
  ```
  (The ShapeShift drawable reference and `@string/powered_by` are removed; the `android:id` is kept so `PayWithDialog.java` doesn't need changes to find the view.)

- [ ] **Step 10.3: Update PayWithDialog.java click handler**

  In `PayWithDialog.java`, find the `setOnClickListener` block for `powered_by_shapeshift` (around line 99):

  ```java
  // BEFORE:
  poweredByShapeShift.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.about_shapeshift_title)
                  .setMessage(R.string.about_shapeshift_message)
                  .setPositiveButton(R.string.button_ok, null)
                  .create().show();
      }
  });

  // AFTER:
  poweredByShapeShift.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.about_simpleswap_title)
                  .setMessage(R.string.about_simpleswap_message)
                  .setPositiveButton(R.string.button_ok, null)
                  .create().show();
      }
  });
  ```

- [ ] **Step 10.4: Build**

  ```
  gradlew.bat :wallet:compileDebugJava
  ```
  Expected: BUILD SUCCESSFUL (no remaining `about_shapeshift_*` string references).

- [ ] **Step 10.5: Commit**

  ```
  git add wallet/src/main/res/values/strings.xml
  git add wallet/src/main/res/layout/powered_by_shapeshift.xml
  git add wallet/src/main/java/com/openwallet/wallet/ui/PayWithDialog.java
  git commit -m "feat: replace ShapeShift badge/dialog with SimpleSwap in PayWithDialog"
  ```

---

## Chunk 3: BIP Address-Type UI (Receive Tab)

### Task 11: Add Address-Type Selector to fragment_request.xml

**Files:**
- Modify: `wallet/src/main/res/layout/fragment_request.xml`

The wallet uses appcompat-v7 (not AndroidX / Material Design), so `ChipGroup` is unavailable. Use a horizontal `RadioGroup` with `RadioButton` elements styled as toggle buttons.

- [ ] **Step 11.1: Add RadioGroup to fragment_request.xml**

  In `fragment_request.xml`, insert the following `RadioGroup` **before** the `<RelativeLayout android:layout_width="wrap_content" android:layout_height="wrap_content" android:layout_gravity="center_horizontal">` block (line 24 of the current file — the outer RelativeLayout wrapping the address label and address text views). This makes the RadioGroup the new first child of the vertical `LinearLayout`.

  Note: `@+id/request_address_view` is a `LinearLayout` *inside* that outer RelativeLayout — do not search for it as the insertion point. Find the **outer** `<RelativeLayout>` at line 24.

  ```xml
  <!-- Address-type selector — shown only for coins with multiple address types -->
  <RadioGroup
      android:id="@+id/address_type_group"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:layout_gravity="center_horizontal"
      android:orientation="horizontal"
      android:layout_marginBottom="8dp"
      android:visibility="gone" />
  ```

  No child `RadioButton` elements are defined in XML — they are inflated programmatically in `AddressRequestFragment` (Task 12) based on `type.getSupportedAddressTypes()`.

- [ ] **Step 11.2: Build**

  ```
  gradlew.bat :wallet:compileDebugJava
  ```
  Expected: BUILD SUCCESSFUL

---

### Task 12: AddressRequestFragment Address-Type Selector Logic

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`

This is the main logic change. The fragment gains:
- A `RadioGroup` view (bound via ButterKnife)
- A `selectedAddressType` field (persisted in `SharedPreferences`)
- A `currentECKey` field (the `ECKey` for the current receive address)
- A modified `updateView()` that computes the encoded address string
- A modified `getUri()` that uses the encoded address for non-Legacy types

**Design notes:**
- The `RadioGroup` is shown only when `type.getSupportedAddressTypes().size() > 1`. For NYC and other Legacy-only coins, the UI is unchanged.
- `SharedPreferences` key: `"addr_type_" + type.getId()` (e.g. `"addr_type_bitcoin.main"`).
- Default for multi-type coins: `NATIVE_SEGWIT`. Default for Legacy-only coins: `LEGACY`.
- The `currentECKey` is obtained via `account.findKeyFromPubHash(((BitAddress) receiveAddress).getHash160())`. This works because `WalletAccount` extends `KeyBag`.
- For `getUri()`: when `selectedAddressType != LEGACY`, the URI is built manually as `{scheme}:{encodedAddr}[?amount=...]` to bypass `CoinURI.convertToCoinURI()` which only accepts `AbstractAddress` objects.
- The `onAddressClick()` copy/share action still uses the underlying `receiveAddress` — this is a known limitation; the Legacy address is copied even if a non-Legacy type is displayed.

- [ ] **Step 12.1: Add new imports to AddressRequestFragment.java**

  Add to the import block:
  ```java
  import android.content.SharedPreferences;
  import android.widget.RadioButton;
  import android.widget.RadioGroup;
  import com.openwallet.core.coins.AddressType;
  import com.openwallet.core.util.AddressEncoder;
  import com.openwallet.core.util.GenericUtils;
  import com.openwallet.core.wallet.families.bitcoin.BitAddress;
  import org.bitcoinj.core.ECKey;
  import java.util.List;
  ```

  (`GenericUtils` is already imported but include for clarity.)

- [ ] **Step 12.2: Add new fields**

  After the existing field `private String message;`, add:
  ```java
  private AddressType selectedAddressType = AddressType.LEGACY;
  private ECKey currentECKey;
  private SharedPreferences addrTypePrefs;
  ```

- [ ] **Step 12.3: Add ButterKnife binding for RadioGroup**

  After the existing `@Bind(R.id.qr_code) ImageView qrView;` line, add:
  ```java
  @Bind(R.id.address_type_group) RadioGroup addressTypeGroup;
  ```

- [ ] **Step 12.4: Initialize `addrTypePrefs` and `selectedAddressType` in `onCreate()`**

  In `onCreate()`, after `type = account.getCoinType();`, add:
  ```java
  addrTypePrefs = getActivity().getSharedPreferences(
          "address_type_prefs", android.content.Context.MODE_PRIVATE);

  // Load persisted type, defaulting to NATIVE_SEGWIT for multi-type coins
  if (type != null) {
      String defaultType = type.getSupportedAddressTypes().size() > 1
              ? AddressType.NATIVE_SEGWIT.name()
              : AddressType.LEGACY.name();
      String saved = addrTypePrefs.getString("addr_type_" + type.getId(), defaultType);
      try {
          selectedAddressType = AddressType.valueOf(saved);
      } catch (IllegalArgumentException e) {
          selectedAddressType = AddressType.LEGACY;
      }
      // Guard: if the saved type isn't supported by this coin, fall back to LEGACY
      if (!type.getSupportedAddressTypes().contains(selectedAddressType)) {
          selectedAddressType = AddressType.LEGACY;
      }
  }
  ```

- [ ] **Step 12.5: Build the RadioGroup in `onCreateView()`**

  In `onCreateView()`, after `sendCoinAmountView.resetType(type, true);`, add a call to a new helper method:
  ```java
  buildAddressTypeGroup(view);
  ```

  Add the helper method to the class:
  ```java
  private void buildAddressTypeGroup(View root) {
      if (type == null) return;
      List<AddressType> types = type.getSupportedAddressTypes();
      if (types.size() <= 1) {
          // Legacy-only coin: leave RadioGroup hidden
          return;
      }

      addressTypeGroup.setVisibility(View.VISIBLE);
      addressTypeGroup.removeAllViews(); // clear in case of view recycling

      for (AddressType addrType : types) {
          RadioButton btn = new RadioButton(getContext());
          btn.setId(addrType.ordinal()); // stable ID per enum value
          btn.setText(labelFor(addrType));
          btn.setChecked(addrType == selectedAddressType);
          btn.setButtonDrawable(null); // hide the radio circle dot (valid on API 26+)
          btn.setPadding(24, 12, 24, 12);
          addressTypeGroup.addView(btn);
      }

      addressTypeGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
          @Override
          public void onCheckedChanged(RadioGroup group, int checkedId) {
              // Map button ID (= enum ordinal) back to AddressType
              for (AddressType t : type.getSupportedAddressTypes()) {
                  if (t.ordinal() == checkedId) {
                      selectedAddressType = t;
                      addrTypePrefs.edit()
                              .putString("addr_type_" + type.getId(), t.name())
                              .apply();
                      updateView();
                      break;
                  }
              }
          }
      });
  }

  private static String labelFor(AddressType type) {
      switch (type) {
          case LEGACY:         return "Legacy";
          case COMPATIBILITY:  return "Compat";
          case NATIVE_SEGWIT:  return "SegWit";
          case TAPROOT:        return "Taproot";
          default:             return type.name();
      }
  }
  ```

- [ ] **Step 12.6: Modify `updateView()` to compute the encoded address**

  Replace the current `updateView()` method:

  ```java
  @Override
  public void updateView() {
      if (isRemoving() || isDetached()) return;
      receiveAddress = null;
      currentECKey = null;

      if (showAddress != null) {
          receiveAddress = showAddress;
      } else if (account != null) {
          receiveAddress = account.getReceiveAddress();
      }

      if (receiveAddress == null) return;

      // Resolve the ECKey for non-LEGACY encodings
      if (selectedAddressType != AddressType.LEGACY
              && receiveAddress instanceof BitAddress
              && account != null) {
          byte[] hash160 = ((BitAddress) receiveAddress).getHash160();
          currentECKey = account.findKeyFromPubHash(hash160);
      }

      // Don't show previous addresses link if we are showing a specific address
      if (showAddress == null && account != null && account.hasUsedAddresses()) {
          previousAddressesLink.setVisibility(View.VISIBLE);
      } else {
          previousAddressesLink.setVisibility(View.GONE);
      }

      updateLabel();
      updateQrCode(getUri());
  }
  ```

- [ ] **Step 12.7: Modify `getUri()` for non-Legacy address types**

  Replace the current `getUri()` method:

  ```java
  private String getUri() {
      // For non-Legacy types, build URI from encoded address string directly.
      // CoinURI.convertToCoinURI() only accepts AbstractAddress which maps to Legacy strings.
      if (selectedAddressType != AddressType.LEGACY
              && currentECKey != null
              && type != null && type.getBech32Hrp() != null) {
          String encodedAddr = AddressEncoder.encode(currentECKey, type, selectedAddressType);
          StringBuilder uri = new StringBuilder();
          uri.append(type.getUriScheme()).append(":").append(encodedAddr);
          if (amount != null && amount.isPositive()) {
              uri.append("?amount=")
                 .append(GenericUtils.formatCoinValue(type, amount, true));
          }
          return uri.toString();
      }

      // Standard Legacy path
      if (type instanceof BitFamily) {
          return CoinURI.convertToCoinURI(receiveAddress, amount, label, message);
      } else if (type instanceof NxtFamily) {
          return CoinURI.convertToCoinURI(receiveAddress, amount, label, message,
                  account.getPublicKeySerialized());
      } else {
          throw new UnsupportedCoinTypeException(type);
      }
  }
  ```

- [ ] **Step 12.8: Modify `updateLabel()` to display the encoded address string**

  Replace the current `updateLabel()` method:

  ```java
  private void updateLabel() {
      // Compute the address string to display
      String addrStr;
      if (selectedAddressType != AddressType.LEGACY
              && currentECKey != null
              && type != null && type.getBech32Hrp() != null) {
          addrStr = AddressEncoder.encode(currentECKey, type, selectedAddressType);
      } else if (receiveAddress != null) {
          addrStr = receiveAddress.toString();
      } else {
          return;
      }

      label = resolveLabel(receiveAddress);
      if (label != null) {
          addressLabelView.setText(label);
          addressLabelView.setTypeface(Typeface.DEFAULT);
          addressView.setText(addrStr);
          addressView.setVisibility(View.VISIBLE);
      } else {
          // GenericUtils.addressSplitToGroupsMultiline only accepts AbstractAddress (not String)
          // and hardcodes positions for 34-char Legacy addresses. Use the local helper instead —
          // it handles Legacy, SegWit (42 chars), and Taproot (62 chars) uniformly.
          addressLabelView.setText(splitAddressForDisplay(addrStr));
          addressLabelView.setTypeface(Typeface.MONOSPACE);
          addressView.setVisibility(View.GONE);
      }
  }

  /** Splits an address string into lines every 12 characters for display readability. */
  private static String splitAddressForDisplay(String addr) {
      return addr.replaceAll("(.{12})", "$1\n").trim();
  }

- [ ] **Step 12.9: Build**

  ```
  gradlew.bat :wallet:compileDebugJava
  ```
  Expected: BUILD SUCCESSFUL. Fix any import or method-signature issues found.

- [ ] **Step 12.10: Build the release APK**

  ```
  gradlew.bat :wallet:assembleRelease
  ```
  Expected: BUILD SUCCESSFUL. APK at `wallet/build/outputs/apk/release/wallet-release.apk`.

- [ ] **Step 12.11: Commit**

  ```
  git add wallet/src/main/res/layout/fragment_request.xml
  git add wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java
  git commit -m "feat: add address-type RadioGroup selector to Receive tab (BTC/LTC/DGB/VTC)"
  ```

---

## Manual Test Checklist

After the APK installs and launches:

- [ ] **NYC addresses**: Open a NewYorkCoin wallet → Receive tab. Address starts with `R`, not `N`.
- [ ] **BTC address types**: Open Bitcoin wallet → Receive tab. RadioGroup shows Legacy / Compat / SegWit / Taproot. Selecting each updates the QR code and address text. Toggling back to Legacy shows the original `1…` address. App restart preserves the last selected type (SharedPreferences).
- [ ] **LTC address types**: Open Litecoin wallet → Receive tab. Compat shows `M…` address (not `3…`). SegWit shows `ltc1q…`.
- [ ] **DGB/VTC address types**: Show Legacy + SegWit only.
- [ ] **SimpleSwap**: Tap Trade in the navigation drawer. WebView loads SimpleSwap with `from=` and `addressTo=` pre-filled. Back button navigates within WebView history; second back press exits.
- [ ] **Legacy-only coins**: Open any coin without bech32Hrp (e.g. Dogecoin, NewYorkCoin). Receive tab shows no RadioGroup — UI unchanged.
- [ ] **Litecoin visible**: Tap + to add a coin. Litecoin appears in the list.
