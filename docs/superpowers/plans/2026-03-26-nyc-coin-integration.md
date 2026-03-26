# NYC OpenWallet Android — Phase 1 Coin Integration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NewYorkCoin (NYC) as the primary coin and Zcash (ZEC) transparent-address support to the openwallet-android app, with a first-run server config dialog for NYC's undeployed ElectrumX server.

**Architecture:** NYC reuses the existing `BitFamily` stack unchanged. ZEC requires `ZcashFamily` (extends `BitFamily`) + `ZcashAddress` (custom 2-byte Base58Check) because bitcoinj 0.12.3's `VersionedChecksummedBytes` hard-crashes on version bytes ≥ 256. `WalletPocketHD` and `BitWalletBase` must be updated to call `type.addressFromKey(key)` (new virtual method on `CoinType`) instead of the hardcoded `BitAddress.from(type, key)` so ZEC address derivation routes through `ZcashAddress`.

**Tech Stack:** Java, Android SDK 35 (min 26), bitcoinj 0.12.3-openwallet, Gradle, JUnit 4, ButterKnife

---

## File Map

| Status | Path | Responsibility |
|--------|------|----------------|
| CREATE | `core/src/main/java/com/openwallet/core/coins/ZcashAddress.java` | 2-byte Base58Check encode/decode for ZEC t-addresses |
| CREATE | `core/src/main/java/com/openwallet/core/coins/families/ZcashFamily.java` | BitFamily subclass; overrides `newAddress()` and `addressFromKey()` |
| CREATE | `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java` | NYC coin definition |
| CREATE | `core/src/main/java/com/openwallet/core/coins/ZcashMain.java` | ZEC coin definition |
| CREATE | `wallet/src/main/java/com/openwallet/wallet/ui/NycServerConfigDialog.java` | First-run ElectrumX server config dialog |
| CREATE | `core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java` | NYC address derivation test |
| CREATE | `core/src/test/java/com/openwallet/core/coins/ZcashTest.java` | ZEC address encode/decode tests |
| MODIFY | `core/src/main/java/com/openwallet/core/coins/CoinType.java` | Add `addressFromKey(ECKey)` virtual method |
| MODIFY | `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` | 4 call sites: `BitAddress.from(type, key)` → `type.addressFromKey(key)`; widen return types to `AbstractAddress` |
| MODIFY | `core/src/main/java/com/openwallet/core/wallet/BitWalletBase.java` | String-based address lookups only — no ECKey calls, no changes needed |
| MODIFY | `core/src/main/java/com/openwallet/core/coins/CoinID.java` | Register NYC + ZEC, NYC first |
| MODIFY | `wallet/src/main/java/com/openwallet/wallet/Constants.java` | DEFAULT_COIN, SUPPORTED_COINS, ZEC server, icons, explorers |
| MODIFY | `wallet/src/main/java/com/openwallet/wallet/Configuration.java` | `getNycElectrumServer()` / `setNycElectrumServer()` |
| MODIFY | `wallet/src/main/java/com/openwallet/wallet/ui/AddCoinsActivity.java` | Intercept NYC add-coin to show NycServerConfigDialog |
| MODIFY | `wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java` | No-server banner for NYC |
| MODIFY | `wallet/src/main/res/layout/fragment_balance_header.xml` | Add `no_server_banner` TextView |
| MODIFY | `wallet/build.gradle` | applicationId, compileSdk 35, minSdk 26, targetSdk 35 |
| MODIFY | `wallet/src/main/AndroidManifest.xml` | package rename |
| ADD | `wallet/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/newyorkcoin.png` | NYC icon at all densities |
| ADD | `wallet/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/zcash.png` | ZEC icon at all densities |

---

## Chunk 1: ZcashAddress — 2-Byte Base58Check Infrastructure

> These two tasks must pass before any ZEC coin definition is written. `ZcashAddress` is
> self-contained and has no dependencies on other new code.

---

### Task 1: ZcashAddress — write failing tests

**Files:**
- Create: `core/src/test/java/com/openwallet/core/coins/ZcashTest.java`

- [ ] **Step 1.1: Create the test file**

```java
package com.openwallet.core.coins;

import org.bitcoinj.core.Base58;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class ZcashTest {

    // Known Zcash transparent address (t1 = P2PKH, 0x1C 0xB8)
    // hash160 = all-zeros 20 bytes — used only to test encode/decode round-trip
    static final byte[] ZERO_HASH160 = new byte[20];

    // Known real ZEC t1 address to validate prefix
    // t1 addresses start with "t1" which encodes version 0x1C 0xB8
    static final String KNOWN_T1_ADDR = "t1KVGHzxCmVdmNBME6o7KFqBFMoGVQjqPBW";

    @Test
    public void encodeZeroHash160ProducesT1Prefix() {
        // Arrange: use null coin type — ZcashAddress only needs addressHeader
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        // Assert: encoded address starts with "t1"
        assertTrue("Expected t1 prefix, got: " + addr.toString(),
                addr.toString().startsWith("t1"));
    }

    @Test
    public void encodeP2SHProducesT3Prefix() {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CBD, ZERO_HASH160);
        assertTrue("Expected t3 prefix, got: " + addr.toString(),
                addr.toString().startsWith("t3"));
    }

    @Test
    public void roundTripPreservesHash160() throws Exception {
        byte[] hash160 = new byte[20];
        for (int i = 0; i < 20; i++) hash160[i] = (byte) (i + 1);
        ZcashAddress encoded = ZcashAddress.fromHash160(0x1CB8, hash160);
        ZcashAddress decoded = ZcashAddress.fromString(encoded.toString());
        assertArrayEquals(hash160, decoded.getHash160());
    }

    @Test
    public void roundTripPreservesVersion() throws Exception {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        ZcashAddress decoded = ZcashAddress.fromString(addr.toString());
        assertEquals(0x1CB8, decoded.getVersion());
    }

    @Test(expected = Exception.class)
    public void invalidChecksumThrows() throws Exception {
        // Corrupt the last character of a valid address
        String corrupted = KNOWN_T1_ADDR.substring(0, KNOWN_T1_ADDR.length() - 1) + "X";
        ZcashAddress.fromString(corrupted);
    }

    @Test
    public void addressLengthIsReasonable() {
        ZcashAddress addr = ZcashAddress.fromHash160(0x1CB8, ZERO_HASH160);
        int len = addr.toString().length();
        assertTrue("Address length " + len + " not in expected range [34,36]",
                len >= 34 && len <= 36);
    }
}
```

- [ ] **Step 1.2: Run tests — expect compile failure (ZcashAddress does not exist yet)**

```bash
cd /d/NewYorkCoin_2026/nyc-openwallet-android
./gradlew :core:test --tests "com.openwallet.core.coins.ZcashTest" 2>&1 | tail -20
```

Expected: BUILD FAILED — `ZcashAddress` cannot be resolved

---

### Task 2: ZcashAddress — implement to make tests pass

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/ZcashAddress.java`

- [ ] **Step 2.1: Create ZcashAddress.java**

```java
package com.openwallet.core.coins;

import com.openwallet.core.wallet.AbstractAddress;
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

    private final int version;       // full 2-byte version as int (e.g. 0x1CB8)
    private final byte[] hash160;    // 20-byte public key hash
    private final CoinType coinType; // may be null for unit tests

    private ZcashAddress(int version, byte[] hash160, CoinType coinType) {
        this.version = version;
        this.hash160 = Arrays.copyOf(hash160, 20);
        this.coinType = coinType;
    }

    /** Construct from a coin type (uses type.addressHeader as version). */
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
        byte[] decoded = Base58.decode(address);
        if (decoded.length < 26) {
            throw new IllegalArgumentException("Decoded address too short: " + decoded.length);
        }
        // Last 4 bytes are checksum
        byte[] payload = Arrays.copyOfRange(decoded, 0, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);

        // Verify checksum: first 4 bytes of double-SHA256 of payload
        byte[] hash = Sha256Hash.hashTwice(payload);
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
        byte[] checksum = Sha256Hash.hashTwice(payload);

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
```

- [ ] **Step 2.2: Run ZcashTest — expect all 6 tests to pass**

```bash
cd /d/NewYorkCoin_2026/nyc-openwallet-android
./gradlew :core:test --tests "com.openwallet.core.coins.ZcashTest" 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, 6 tests passed

- [ ] **Step 2.3: Commit**

```bash
cd /d/NewYorkCoin_2026/nyc-openwallet-android
git add core/src/main/java/com/openwallet/core/coins/ZcashAddress.java \
        core/src/test/java/com/openwallet/core/coins/ZcashTest.java
git commit -m "feat: add ZcashAddress — 2-byte Base58Check for ZEC t-addresses"
```

---

## Chunk 2: ZcashFamily + CoinType.addressFromKey() + Call-Site Updates

> `WalletPocketHD` and `BitWalletBase` hard-code `BitAddress.from(type, key)`.
> We add a virtual `addressFromKey(ECKey)` to `CoinType`, defaulting to `BitAddress`,
> then override in `ZcashFamily` to return `ZcashAddress`. Finally update 6 call sites.

---

### Task 3: Add `addressFromKey()` to CoinType

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinType.java`

Note: `addressFromKey()` is declared as an unchecked (RuntimeException) path. `BitAddress.from()`
wraps `WrongNetworkException` — which cannot fire in key derivation since we pass the key's own
hash160 to its own network — so no checked exception is needed, and callers stay clean.

- [ ] **Step 3.1: Add import and method to CoinType.java**

Open `CoinType.java`. Add import at the top with the other imports:

```java
import com.openwallet.core.wallet.families.bitcoin.BitAddress;
import com.openwallet.core.wallet.AbstractAddress;
import org.bitcoinj.core.ECKey;
```

Add this method at the end of the class body, before the closing `}`:

```java
/**
 * Create an address for this coin type from a public key.
 * Default: P2PKH BitAddress. Override in ZcashFamily to return ZcashAddress.
 * Throws RuntimeException (not checked) — WrongNetworkException cannot fire
 * when deriving from a key's own hash160.
 */
public AbstractAddress addressFromKey(ECKey key) {
    try {
        return BitAddress.from(this, key.getPubKeyHash());
    } catch (Exception e) {
        throw new RuntimeException("Failed to derive address for " + getName(), e);
    }
}
```

- [ ] **Step 3.2: Build core module — expect no errors**

```bash
cd /d/NewYorkCoin_2026/nyc-openwallet-android
./gradlew :core:compileDebugJava 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/CoinType.java
git commit -m "feat: add CoinType.addressFromKey() virtual method for coin-specific address derivation"
```

---

### Task 4: Create ZcashFamily

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/families/ZcashFamily.java`

- [ ] **Step 4.1: Create ZcashFamily.java**

```java
package com.openwallet.core.coins.families;

import com.openwallet.core.coins.ZcashAddress;
import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;

import org.bitcoinj.core.ECKey;

/**
 * Coin family for Zcash transparent addresses.
 *
 * Extends BitFamily to satisfy instanceof BitFamily checks in Wallet.java,
 * ServerClients.java, and WalletProtobufSerializer.java — all three dispatch
 * on instanceof BitFamily to route to the Bitcoin-family wallet stack.
 *
 * Overrides newAddress(String) and addressFromKey(ECKey) to use ZcashAddress
 * (2-byte Base58Check) instead of BitAddress (1-byte).
 */
public abstract class ZcashFamily extends BitFamily {

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        try {
            return ZcashAddress.fromString(addressStr);
        } catch (IllegalArgumentException e) {
            throw new AddressMalformedException(e);
        }
    }

    @Override
    public AbstractAddress addressFromKey(ECKey key) throws AddressMalformedException {
        return ZcashAddress.fromHash160(this, key.getPubKeyHash());
    }
}
```

- [ ] **Step 4.2: Build — expect no errors**

```bash
./gradlew :core:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/families/ZcashFamily.java
git commit -m "feat: add ZcashFamily extending BitFamily with ZcashAddress routing"
```

---

### Task 5: Update call sites in WalletPocketHD

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` (lines 316, 431, 478, 510)

Note on BitWalletBase: the two `BitAddress.from()` calls in `BitWalletBase.java` use a
**String** address argument (`unsignedMessage.getAddress()`, `signedMessage.address`), not an
`ECKey`. They are address-lookup/verification paths, not key derivation. Do NOT change them.

Note on return-type widening: `WalletAccount` interface already declares these methods as
returning `AbstractAddress`. `WalletPocketHD` currently uses covariant `BitAddress` return types.
Widening back to `AbstractAddress` is backward-compatible with all callers (UI code stores
results as `AbstractAddress`). No cascade required.

- [ ] **Step 5.1: Widen return types and replace call sites in WalletPocketHD.java**

Make these 6 changes in `WalletPocketHD.java`:

**1. `getChangeAddress()` return type (line ~276):**
```java
// BEFORE: public BitAddress getChangeAddress()
// AFTER:
public AbstractAddress getChangeAddress() {
    return currentAddress(CHANGE);
}
```

**2. `getReceiveAddress()` return type (line ~281):**
```java
// BEFORE: public BitAddress getReceiveAddress()
// AFTER:
public AbstractAddress getReceiveAddress() {
    return currentAddress(RECEIVE_FUNDS);
}
```

**3. `getRefundAddress()` return type (line ~285):**
```java
// BEFORE: public BitAddress getRefundAddress()
// AFTER:
public AbstractAddress getRefundAddress() { return currentAddress(REFUND); }
```

**4. `currentAddress()` body (line ~478) — replaces the key derivation call:**
```java
@VisibleForTesting AbstractAddress currentAddress(SimpleHDKeyChain.KeyPurpose purpose) {
    // BEFORE return body:  return BitAddress.from(type, keys.getCurrentUnusedKey(purpose));
    // AFTER:
    return type.addressFromKey(keys.getCurrentUnusedKey(purpose));
}
```

**5. Line ~431 inside the receive-address list population loop:**
```java
// BEFORE:
receiveAddresses.add(BitAddress.from(type, key));
// AFTER — list type widens to List<AbstractAddress>:
receiveAddresses.add(type.addressFromKey(key));
```

**6. Line ~510 inside the active-address list population loop:**
```java
// BEFORE:
activeAddresses.add(BitAddress.from(type, key));
// AFTER:
activeAddresses.add(type.addressFromKey(key));
```

**7. Line ~316 inside `getLastUsedAddress()`:**
```java
// BEFORE:
return BitAddress.from(type, lastUsedKey);
// AFTER — widen method return type to AbstractAddress:
public AbstractAddress getLastUsedAddress(SimpleHDKeyChain.KeyPurpose purpose) {
    ...
    return type.addressFromKey(lastUsedKey);
}
```

Add import if not already present:
```java
import com.openwallet.core.wallet.AbstractAddress;
```

- [ ] **Step 5.2: Build core — no errors**

```bash
./gradlew :core:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. If any caller complains about `BitAddress` vs `AbstractAddress`,
widen that caller's local variable type from `BitAddress` to `AbstractAddress` — the interface
already uses `AbstractAddress` everywhere.

- [ ] **Step 5.3: Run all existing core tests — no regressions**

```bash
./gradlew :core:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all pre-existing tests pass

- [ ] **Step 5.4: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java
git commit -m "refactor: widen WalletPocketHD address return types to AbstractAddress; route derivation through CoinType.addressFromKey()"
```

---

## Chunk 3: Coin Definitions

---

### Task 6: NewYorkCoinMain

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java`
- Create: `core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java`

- [ ] **Step 6.1: Write the failing test**

```java
package com.openwallet.core.coins;

import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.crypto.MnemonicException;
import org.bitcoinj.wallet.DeterministicSeed;
import org.junit.Test;

import static org.junit.Assert.*;

public class NewYorkCoinTest {

    // Standard BIP39 test mnemonic (all-zeros entropy)
    static final String MNEMONIC =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    @Test
    public void addressStartsWithN() throws MnemonicException, Exception {
        CoinType nyc = NewYorkCoinMain.get();
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
        DeterministicHierarchy h = new DeterministicHierarchy(master);
        DeterministicKey accountKey = h.get(nyc.getBip44Path(0), false, true);
        DeterministicKey receiveKey = HDKeyDerivation.deriveChildKey(accountKey, 0);
        DeterministicKey addrKey = HDKeyDerivation.deriveChildKey(receiveKey, 0);

        String address = nyc.addressFromKey(addrKey).toString();
        assertTrue("NYC address must start with N, got: " + address,
                address.startsWith("N"));
    }

    @Test
    public void coinIdEndsWithMain() {
        assertEquals("nyc.main", NewYorkCoinMain.get().getId());
    }

    @Test
    public void bip44IndexIs179() {
        assertEquals(179, NewYorkCoinMain.get().getBip44Index());
    }

    @Test
    public void feeIsZero() {
        assertEquals(0L, NewYorkCoinMain.get().getFeeValue().value);
    }

    @Test
    public void singletonReturnsSameInstance() {
        assertSame(NewYorkCoinMain.get(), NewYorkCoinMain.get());
    }
}
```

- [ ] **Step 6.2: Run — expect compile failure (NewYorkCoinMain missing)**

```bash
./gradlew :core:test --tests "com.openwallet.core.coins.NewYorkCoinTest" 2>&1 | tail -10
```

Expected: FAILED — cannot find symbol NewYorkCoinMain

- [ ] **Step 6.3: Create NewYorkCoinMain.java**

```java
package com.openwallet.core.coins;

import com.openwallet.core.coins.families.BitFamily;

public class NewYorkCoinMain extends BitFamily {
    private NewYorkCoinMain() {
        id = "nyc.main";

        addressHeader = 60;           // 0x3C → N... prefix
        p2shHeader = 22;              // 0x16
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 188; // 0xBC

        addressPrefix = "";
        name = "NewYorkCoin";
        symbol = "NYC";
        uriScheme = "newyorkcoin";
        bip44Index = 179;             // SLIP-0044 confirmed
        unitExponent = 8;
        feeValue = value(0);          // NYC is fee-free
        feePolicy = FeePolicy.FLAT_FEE;
        minNonDust = value(0);
        softDustLimit = value(0);
        softDustPolicy = SoftDustPolicy.NO_POLICY;
        signedMessageHeader = toBytes("NewYorkCoin Signed Message:\n");
    }

    private static NewYorkCoinMain instance = new NewYorkCoinMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
```

- [ ] **Step 6.4: Run NewYorkCoinTest — all 5 tests pass**

```bash
./gradlew :core:test --tests "com.openwallet.core.coins.NewYorkCoinTest" 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 6.5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java \
        core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java
git commit -m "feat: add NewYorkCoinMain coin definition (BIP44 179, fee-free, N... addresses)"
```

---

### Task 7: ZcashMain

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/ZcashMain.java`

Extend `ZcashTest.java` with coin-type-aware tests.

- [ ] **Step 7.1: Add ZcashMain-dependent tests to ZcashTest.java**

Append these tests to the existing `ZcashTest` class:

```java
@Test
public void zecAddressFromKeyStartsWithT1() throws Exception {
    CoinType zec = ZcashMain.get();
    DeterministicSeed seed = new DeterministicSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        null, "", 0);
    DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(seed.getSeedBytes());
    DeterministicHierarchy h = new DeterministicHierarchy(master);
    DeterministicKey accountKey = h.get(zec.getBip44Path(0), false, true);
    DeterministicKey receiveKey = HDKeyDerivation.deriveChildKey(accountKey, 0);
    DeterministicKey addrKey = HDKeyDerivation.deriveChildKey(receiveKey, 0);
    String address = zec.addressFromKey(addrKey).toString();
    assertTrue("ZEC address must start with t1, got: " + address,
            address.startsWith("t1"));
}

@Test
public void zecCoinIdEndsWithMain() {
    assertEquals("zcash.main", ZcashMain.get().getId());
}

@Test
public void zecBip44IndexIs133() {
    assertEquals(133, ZcashMain.get().getBip44Index());
}

@Test
public void zecSingletonReturnsSameInstance() {
    assertSame(ZcashMain.get(), ZcashMain.get());
}
```

Add required imports to `ZcashTest.java`:
```java
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
```

- [ ] **Step 7.2: Run — expect failure (ZcashMain missing)**

```bash
./gradlew :core:test --tests "com.openwallet.core.coins.ZcashTest" 2>&1 | tail -10
```

Expected: FAILED — cannot find symbol ZcashMain

- [ ] **Step 7.3: Create ZcashMain.java**

```java
package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ZcashFamily;

public class ZcashMain extends ZcashFamily {
    private ZcashMain() {
        id = "zcash.main";

        // 0x1CB8 → t1... (P2PKH); 0x1CBD → t3... (P2SH)
        // Stored as int; ZcashFamily/ZcashAddress handle 2-byte split.
        // NOT passed to VersionedChecksummedBytes.
        addressHeader = 7352;         // 0x1CB8
        p2shHeader = 7357;            // 0x1CBD
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 128; // 0x80

        addressPrefix = "";
        name = "Zcash";
        symbol = "ZEC";
        uriScheme = "zcash";
        bip44Index = 133;
        unitExponent = 8;
        feeValue = value(1000);
        minNonDust = value(1000);
        softDustLimit = value(1000);
        softDustPolicy = SoftDustPolicy.BASE_FEE_FOR_EACH_SOFT_DUST_TXO;
        signedMessageHeader = toBytes("Zcash Signed Message:\n");
    }

    private static ZcashMain instance = new ZcashMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
```

- [ ] **Step 7.4: Run all ZcashTest tests — all 10 pass**

```bash
./gradlew :core:test --tests "com.openwallet.core.coins.ZcashTest" 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, 10 tests passed

- [ ] **Step 7.5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/ZcashMain.java \
        core/src/test/java/com/openwallet/core/coins/ZcashTest.java
git commit -m "feat: add ZcashMain coin definition (BIP44 133, transparent t-addresses only)"
```

---

## Chunk 4: Registration — CoinID, Constants, Configuration

---

### Task 8: Register NYC and ZEC in CoinID

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinID.java`

- [ ] **Step 8.1: Add NYC and ZEC to the CoinID enum**

In `CoinID.java`, add two entries at the **top** of the enum, before `BITCOIN_MAIN`:

```java
NEWYORKCOIN_MAIN(NewYorkCoinMain.get()),  // PRIMARY — must be first
ZCASH_MAIN(ZcashMain.get()),
BITCOIN_MAIN(BitcoinMain.get()),
// ... rest unchanged
```

Add the two imports at the top of the file:
```java
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.coins.ZcashMain;
```

- [ ] **Step 8.2: Run core tests — no regressions**

```bash
./gradlew :core:test 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (CoinID validates uniqueness of IDs/symbols at static init)

- [ ] **Step 8.3: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/CoinID.java
git commit -m "feat: register NEWYORKCOIN_MAIN and ZCASH_MAIN in CoinID"
```

---

### Task 9: Update Constants.java

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/Constants.java`

- [ ] **Step 9.1: Add imports**

Add these imports with the other coin imports:
```java
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.coins.ZcashMain;
```

- [ ] **Step 9.2: Update DEFAULT_COIN and DEFAULT_COINS**

```java
// BEFORE:
public static final CoinType DEFAULT_COIN = BitcoinMain.get();
public static final List<CoinType> DEFAULT_COINS = ImmutableList.of((CoinType) BitcoinMain.get());

// AFTER:
public static final CoinType DEFAULT_COIN = NewYorkCoinMain.get();
public static final List<CoinType> DEFAULT_COINS = ImmutableList.of((CoinType) NewYorkCoinMain.get());
```

- [ ] **Step 9.3: Add ZEC to DEFAULT_COINS_SERVERS (append inside existing ImmutableList.of call)**

Inside the existing `ImmutableList.of(...)` that builds `DEFAULT_COINS_SERVERS`, add ZEC after the last existing entry (before the closing parenthesis):

```java
new CoinAddress(ZcashMain.get(),
    new ServerAddress("electrum.z.cash", 50002),
    new ServerAddress("electrum2.z.cash", 50002))
```

Note: NYC deliberately has **no** entry here — server is configured at runtime.

- [ ] **Step 9.4: Add NYC first in SUPPORTED_COINS**

In the `SUPPORTED_COINS` `ImmutableList.of(...)`, add NYC as the first entry and ZEC after DOGE:
```java
public static final List<CoinType> SUPPORTED_COINS = ImmutableList.of(
    NewYorkCoinMain.get(),   // PRIMARY
    BitcoinMain.get(),
    LitecoinMain.get(),
    DogecoinMain.get(),
    ZcashMain.get(),
    // ... all existing coins follow in their current order, unchanged
```

- [ ] **Step 9.5: Add icon and explorer entries (inside existing static block)**

```java
// Icons
COINS_ICONS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), R.drawable.newyorkcoin);
COINS_ICONS.put(CoinID.ZCASH_MAIN.getCoinType(), R.drawable.zcash);

// Block explorers
COINS_BLOCK_EXPLORERS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(),
    "https://explorer.newyorkcoin.net/tx/%s");
COINS_BLOCK_EXPLORERS.put(CoinID.ZCASH_MAIN.getCoinType(),
    "https://explorer.zcha.in/transactions/%s");
```

- [ ] **Step 9.6: Build wallet module — no errors**

```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL (drawable resources don't exist yet but R.drawable refs compile)

- [ ] **Step 9.7: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/Constants.java
git commit -m "feat: add NYC + ZEC to Constants — DEFAULT_COIN, SUPPORTED_COINS, servers, icons, explorers"
```

---

### Task 10: Add getNycElectrumServer() to Configuration

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/Configuration.java`

- [ ] **Step 10.1: Add import and two methods**

Add import:
```java
import javax.annotation.Nullable;
```

Find the `SharedPreferences` field (already exists). Add these two methods in the existing preferences access section:

```java
private static final String PREFS_KEY_NYC_ELECTRUM_SERVER = "nyc_electrum_server";

@Nullable
public String getNycElectrumServer() {
    return prefs.getString(PREFS_KEY_NYC_ELECTRUM_SERVER, null);
}

public void setNycElectrumServer(final String hostPort) {
    prefs.edit().putString(PREFS_KEY_NYC_ELECTRUM_SERVER, hostPort).apply();
}
```

- [ ] **Step 10.2: Build — no errors**

```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 10.3: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/Configuration.java
git commit -m "feat: add getNycElectrumServer/setNycElectrumServer to Configuration"
```

---

## Chunk 5: Build, Package Rename, and Icon Assets

---

### Task 11: Update build.gradle and AndroidManifest

**Files:**
- Modify: `wallet/build.gradle`
- Modify: `wallet/src/main/AndroidManifest.xml`

- [ ] **Step 11.0: Check AGP version in root build.gradle**

Open the root `build.gradle` and find the `classpath 'com.android.tools.build:gradle:...'` line.
AGP 3.x+ is required for `implementation` syntax and SDK 35 compatibility. If the current version
is 2.x, bump to at least `3.4.3`:

```groovy
// BEFORE (example):
classpath 'com.android.tools.build:gradle:2.3.3'
// AFTER:
classpath 'com.android.tools.build:gradle:3.4.3'
```

Also check the Gradle wrapper version in `gradle/wrapper/gradle-wrapper.properties`. AGP 3.4.x
requires Gradle 5.1.1+:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-5.6.4-all.zip
```

Build to confirm the AGP bump compiles before proceeding:
```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -10
```

- [ ] **Step 11.1: Update wallet/build.gradle**

First, enumerate all `compile` declarations in the wallet build file:
```bash
grep -n "^\s*compile " wallet/build.gradle
```

Then make these updates in `wallet/build.gradle`:

**a) `android { }` block:**
```groovy
compileSdkVersion 35
buildToolsVersion "35.0.0"

defaultConfig {
    applicationId "com.newyorkcoin.openwallet"
    minSdkVersion 26
    targetSdkVersion 35
    // ... other existing fields unchanged
}
```

**b) All `compile '...'` → `implementation '...'`** (AGP 3+ requires this).
Convert every `compile` line; bump support library versions to `28.0.0` at minimum.
Common lines to update (actual versions in your file may differ — update each one):
```groovy
// BEFORE:
compile 'com.android.support:appcompat-v7:XX.X.X'
compile 'com.android.support:support-v4:XX.X.X'
compile 'com.android.support:design:XX.X.X'
compile 'com.android.support:recyclerview-v7:XX.X.X'
compile 'com.android.support:cardview-v7:XX.X.X'
compile fileTree(dir: 'libs', include: ['*.jar'])
compile project(':core')
// ... and any other compile lines shown by the grep above

// AFTER — example of the pattern:
implementation 'com.android.support:appcompat-v7:28.0.0'
implementation 'com.android.support:support-v4:28.0.0'
implementation 'com.android.support:design:28.0.0'
implementation 'com.android.support:recyclerview-v7:28.0.0'
implementation 'com.android.support:cardview-v7:28.0.0'
implementation fileTree(dir: 'libs', include: ['*.jar'])
implementation project(':core')
```

Note: `testCompile` → `testImplementation`; `androidTestCompile` → `androidTestImplementation`.
Do NOT migrate to AndroidX (that would require renaming all `android.support.*` imports). Stay on
support lib 28.x.x — the last non-AndroidX release. It will compile against `compileSdkVersion 35`
but is not officially supported at that API level; lint warnings about deprecated APIs are expected
and acceptable for this phase. There is no build-time compatibility guarantee from Google.

- [ ] **Step 11.2: Update AndroidManifest.xml**

```xml
<!-- BEFORE: -->
package="com.openwallet.wallet"
<!-- AFTER: -->
package="com.newyorkcoin.openwallet"
```

- [ ] **Step 11.3: Build — no errors**

```bash
./gradlew :wallet:assembleDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL (icons still missing, but that's a resource warning not an error — unless `R.drawable.newyorkcoin` is referenced in a layout; in that case, add placeholder 1×1 PNG files first)

If build fails due to missing drawables, create a 1×1 placeholder PNG:
```bash
# Use Python to create a minimal valid PNG (1x1 white pixel)
python3 -c "
import zlib, struct
def png1x1():
    def chunk(name, data):
        c = zlib.crc32(name + data) & 0xFFFFFFFF
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', c)
    sig = b'\x89PNG\r\n\x1a\n'
    ihdr = chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
    idat = chunk(b'IDAT', zlib.compress(b'\x00\xFF\xFF\xFF'))
    iend = chunk(b'IEND', b'')
    return sig + ihdr + idat + iend
data = png1x1()
for d in ['mdpi','hdpi','xhdpi','xxhdpi','xxxhdpi']:
    open(f'wallet/src/main/res/drawable-{d}/newyorkcoin.png','wb').write(data)
    open(f'wallet/src/main/res/drawable-{d}/zcash.png','wb').write(data)
"
```

- [ ] **Step 11.4: Commit**

```bash
git add wallet/build.gradle wallet/src/main/AndroidManifest.xml
git commit -m "build: rename package to com.newyorkcoin.openwallet, bump compileSdk/minSdk/targetSdk to 35/26/35"
```

---

### Task 12: Replace placeholder icons with real assets

**Files:**
- Replace: `wallet/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/newyorkcoin.png`
- Add: `wallet/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/zcash.png`

- [ ] **Step 12.1: Convert NYC logo**

Source: `C:/Users/james/Downloads/NewYorkCoin_v2/Main-File-JPEG_200x200.jpg`

Using Python with Pillow (install if needed: `pip install Pillow`):

```python
from PIL import Image
import os

src = r"C:/Users/james/Downloads/NewYorkCoin_v2/Main-File-JPEG_200x200.jpg"
img = Image.open(src).convert("RGBA")

densities = {
    "mdpi":    32,
    "hdpi":    48,
    "xhdpi":   64,
    "xxhdpi":  96,
    "xxxhdpi": 128,
}

base = "/d/NewYorkCoin_2026/nyc-openwallet-android/wallet/src/main/res"
for density, size in densities.items():
    out_dir = f"{base}/drawable-{density}"
    os.makedirs(out_dir, exist_ok=True)
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(f"{out_dir}/newyorkcoin.png", "PNG")
    print(f"Saved {density} {size}x{size}")
```

Run: `python3 convert_nyc_icon.py`

- [ ] **Step 12.2: Download and resize ZEC logo**

Obtain the official Zcash gold-Z logo PNG. Two options in priority order:

**Option A — download from GitHub (preferred):**
```bash
curl -L "https://raw.githubusercontent.com/zcash/zcash-brand/main/assets/zcash-icon-gold.png" \
     -o /tmp/zcash_logo.png
```

**Option B — if Option A fails (URL changed or offline):**
Use Python with Pillow to generate a minimal gold-Z placeholder that will be replaced before
release. This keeps the build green:
```python
from PIL import Image, ImageDraw, ImageFont
import os

# Create a 200x200 gold circle with white Z
img = Image.new("RGBA", (200, 200), (0, 0, 0, 0))
draw = ImageDraw.Draw(img)
draw.ellipse([0, 0, 199, 199], fill=(247, 170, 0, 255))
draw.text((65, 50), "Z", fill=(255, 255, 255, 255))
img.save("/tmp/zcash_logo.png")
```

Then resize for all densities:
```python
from PIL import Image
import os

src = "/tmp/zcash_logo.png"
img = Image.open(src).convert("RGBA")

densities = {"mdpi": 32, "hdpi": 48, "xhdpi": 64, "xxhdpi": 96, "xxxhdpi": 128}
base = "/d/NewYorkCoin_2026/nyc-openwallet-android/wallet/src/main/res"
for density, size in densities.items():
    resized = img.resize((size, size), Image.LANCZOS)
    resized.save(f"{base}/drawable-{density}/zcash.png", "PNG")
    print(f"Saved {density} {size}x{size}")
```

- [ ] **Step 12.3: Verify icons display correctly**

```bash
# Check file sizes are non-trivial (not 1x1 placeholders)
ls -la /d/NewYorkCoin_2026/nyc-openwallet-android/wallet/src/main/res/drawable-xhdpi/newyorkcoin.png
ls -la /d/NewYorkCoin_2026/nyc-openwallet-android/wallet/src/main/res/drawable-xhdpi/zcash.png
```

Expected: files > 500 bytes

- [ ] **Step 12.4: Build to confirm icons load**

```bash
./gradlew :wallet:assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, no resource errors

- [ ] **Step 12.5: Commit**

```bash
git add wallet/src/main/res/drawable-*/newyorkcoin.png \
        wallet/src/main/res/drawable-*/zcash.png
git commit -m "feat: add NYC and ZEC coin icons at all drawable densities"
```

---

## Chunk 6: NYC Server Dialog + BalanceFragment Banner

---

### Task 13: NycServerConfigDialog

**Files:**
- Create: `wallet/src/main/java/com/openwallet/wallet/ui/NycServerConfigDialog.java`

- [ ] **Step 13.1: Create NycServerConfigDialog.java**

**Critical:** `AlertDialog.setPositiveButton()` always dismisses the dialog before `onClick` fires.
Showing an error on invalid input requires overriding the button's click listener in `onStart()`
(after `dialog.show()` has been called and the button widget exists). The listener is set to `null`
in `setPositiveButton` — this creates the button label only; the real logic goes in `onStart()`.

```java
package com.openwallet.wallet.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.openwallet.wallet.R;

import java.util.regex.Pattern;

/**
 * First-run dialog to configure the NYC ElectrumX server address.
 *
 * Shown once when the user adds the NYC coin for the first time,
 * before wallet pocket creation. The user can Save (persist host:port)
 * or Skip (wallet created without a server — banner shown in BalanceFragment).
 *
 * NOTE: Use android.support.v7.app.AlertDialog (not android.app.AlertDialog)
 * to inherit the app's Material theme consistently across API levels.
 *
 * NOTE: setPositiveButton() always auto-dismisses. To keep the dialog open on
 * invalid input and show an error, we pass null as the listener and override
 * the button's click listener in onStart() instead.
 *
 * NOTE: onAttach() checks the parent fragment first (for child-fragment usage
 * from BalanceFragment), then falls back to checking the Activity (for usage
 * from AddCoinsActivity). Both callers must implement OnNycServerConfiguredListener.
 */
public class NycServerConfigDialog extends DialogFragment {

    public interface OnNycServerConfiguredListener {
        /** Called with hostPort (e.g. "electrum.paywith.nyc:50002") or null if skipped. */
        void onNycServerConfigured(@Nullable String hostPort);
    }

    private static final Pattern HOST_PORT_PATTERN =
        Pattern.compile("^[^:]+:(\\d{1,5})$");

    private OnNycServerConfiguredListener listener;
    private EditText serverInput; // held across onCreateDialog → onStart

    public static NycServerConfigDialog newInstance() {
        return new NycServerConfigDialog();
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        // Check parent fragment first — this dialog may be shown as a child fragment
        // of BalanceFragment (via getChildFragmentManager()), in which case the Activity
        // is NOT the listener. Fall back to the Activity for AddCoinsActivity usage.
        Fragment parent = getParentFragment();
        if (parent instanceof OnNycServerConfiguredListener) {
            listener = (OnNycServerConfiguredListener) parent;
        } else if (context instanceof OnNycServerConfiguredListener) {
            listener = (OnNycServerConfiguredListener) context;
        } else {
            throw new IllegalStateException(
                "Parent fragment or activity must implement OnNycServerConfiguredListener");
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        View view = inflater.inflate(R.layout.dialog_nyc_server_config, null);
        serverInput = view.findViewById(R.id.nyc_server_input);

        // Pass null for Save listener — real listener is set in onStart() to
        // prevent the dialog auto-dismissing before we can show a validation error.
        return new AlertDialog.Builder(getContext())
            .setTitle("Configure NYC ElectrumX Server")
            .setMessage("Enter the NYC ElectrumX server address. You can add this later from the wallet settings.")
            .setView(view)
            .setPositiveButton("Save", null)
            .setNegativeButton("Skip", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    listener.onNycServerConfigured(null);
                }
            })
            .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        // Override positive button AFTER dialog.show() so the button widget exists.
        // This is the only safe pattern that keeps the dialog open on invalid input.
        Button saveBtn = ((AlertDialog) getDialog())
                .getButton(DialogInterface.BUTTON_POSITIVE);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String input = serverInput.getText().toString().trim();
                if (isValidHostPort(input)) {
                    listener.onNycServerConfigured(input);
                    dismiss();
                } else {
                    serverInput.setError(
                        "Enter a valid host:port (e.g. electrum.paywith.nyc:50002)");
                }
            }
        });
    }

    private boolean isValidHostPort(String input) {
        if (!HOST_PORT_PATTERN.matcher(input).matches()) return false;
        int port = Integer.parseInt(input.substring(input.lastIndexOf(':') + 1));
        return port >= 1 && port <= 65535;
    }
}
```

- [ ] **Step 13.2: Create the dialog layout**

Create `wallet/src/main/res/layout/dialog_nyc_server_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <EditText
        android:id="@+id/nyc_server_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:hint="electrum.paywith.nyc:50002"
        android:inputType="textUri"
        android:maxLines="1"
        android:singleLine="true" />

</LinearLayout>
```

- [ ] **Step 13.3: Build wallet — no errors**

```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 13.4: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/ui/NycServerConfigDialog.java \
        wallet/src/main/res/layout/dialog_nyc_server_config.xml
git commit -m "feat: add NycServerConfigDialog — first-run ElectrumX server configuration"
```

---

### Task 14: Hook dialog into AddCoinsActivity

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/AddCoinsActivity.java`

- [ ] **Step 14.1: Make AddCoinsActivity implement OnNycServerConfiguredListener**

Add `NycServerConfigDialog.OnNycServerConfiguredListener` to the `implements` clause:

```java
public class AddCoinsActivity extends BaseWalletActivity
        implements SelectCoinsFragment.Listener, AddCoinTask.Listener,
        ConfirmAddCoinUnlockWalletDialog.Listener,
        NycServerConfigDialog.OnNycServerConfiguredListener {
```

Add import:
```java
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.wallet.WalletApplication;
```

- [ ] **Step 14.2: Add server config check in onCoinSelection()**

In `onCoinSelection()`, immediately **before** the existing `showAddCoinDialog()` call (the original
call — do not remove it), insert the NYC intercept block:

```java
// For NYC, show server config dialog before proceeding
if (selectedCoin.equals(NewYorkCoinMain.get())) {
    String existingServer = ((WalletApplication) getApplication())
            .getConfiguration().getNycElectrumServer();
    if (existingServer == null) {
        NycServerConfigDialog.newInstance()
                .show(getSupportFragmentManager(), "nyc_server_config");
        return; // showAddCoinDialog() will be called from onNycServerConfigured()
    }
}
showAddCoinDialog(); // ← EXISTING call — retained here; reached when NYC has a server already,
                     // or when the coin is BTC/LTC/DOGE/ZEC
```

- [ ] **Step 14.3: Implement onNycServerConfigured()**

Add the callback method to the class:

```java
@Override
public void onNycServerConfigured(@Nullable String hostPort) {
    if (hostPort != null) {
        ((WalletApplication) getApplication())
                .getConfiguration().setNycElectrumServer(hostPort);
    }
    // Proceed with coin creation (server may be null — wallet shows banner)
    showAddCoinDialog();
}
```

Add imports (both are needed — `WalletApplication` for the cast in this method,
`@Nullable` for the parameter annotation):
```java
import android.support.annotation.Nullable;
import com.openwallet.wallet.WalletApplication;
```

- [ ] **Step 14.4: Build — no errors**

```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 14.5: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/ui/AddCoinsActivity.java
git commit -m "feat: intercept NYC coin add to show NycServerConfigDialog before wallet creation"
```

---

### Task 15: BalanceFragment no-server banner

**Files:**
- Modify: `wallet/src/main/res/layout/fragment_balance_header.xml`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java`

Note: `WalletFragment` is abstract — the concrete per-coin wallet view is `BalanceFragment`,
which inflates `fragment_balance_header.xml` as a list header.

- [ ] **Step 15.1: Add no_server_banner to fragment_balance_header.xml**

Open `wallet/src/main/res/layout/fragment_balance_header.xml`. Add a `TextView` after the last existing child view:

```xml
<TextView
    android:id="@+id/no_server_banner"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="#FFFDE7"
    android:clickable="true"
    android:focusable="true"
    android:gravity="center"
    android:padding="12dp"
    android:text="No ElectrumX server configured — tap to set up"
    android:textColor="#F57F17"
    android:textSize="13sp"
    android:visibility="gone" />
```

- [ ] **Step 15.2: Add banner logic to BalanceFragment.java**

In `BalanceFragment.java`:

**Important:** Do NOT use `@Bind(R.id.no_server_banner)` for this view. ButterKnife's `@Bind`
annotations are resolved in the first `ButterKnife.bind(this, view)` call (the main fragment
root). A second `ButterKnife.bind(this, header)` call on the inflated header would overwrite the
first binding, corrupting all existing `@Bind` fields. Instead, find the header view manually:

Add field (plain field, not `@Bind`):
```java
private View noServerBanner;
```

In `addHeaderAndFooterToList()`, after inflating the header, find the banner view explicitly:
```java
private void addHeaderAndFooterToList(LayoutInflater inflater, ViewGroup container, View view) {
    View header = inflater.inflate(R.layout.fragment_balance_header, null);
    // Do NOT call ButterKnife.bind(this, header) — it would overwrite existing @Bind fields.
    noServerBanner = ButterKnife.findById(header, R.id.no_server_banner);
    // ... existing code continues
}
```

Add import:
```java
import com.openwallet.core.coins.NewYorkCoinMain;
```

Add a private method:
```java
private void updateNoServerBanner() {
    if (noServerBanner == null) return;
    // Lifecycle note: config is assigned in onAttach() and type in onCreate(),
    // both of which fire before onCreateView() / addHeaderAndFooterToList().
    // By the time this method is called (from onResume() or the dialog callback),
    // both fields are guaranteed non-null. The null-guard on noServerBanner covers
    // the case where the banner view was not yet inflated (defensive only).
    boolean showBanner = type != null
            && type.equals(NewYorkCoinMain.get())
            && config.getNycElectrumServer() == null;
    noServerBanner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
}
```

Call `updateNoServerBanner()` in `onResume()`:
```java
@Override
public void onResume() {
    super.onResume();
    updateNoServerBanner();
    // ... existing onResume code
}
```

Set up click listener in `addHeaderAndFooterToList()` after the ButterKnife bind:
```java
noServerBanner.setOnClickListener(new View.OnClickListener() {
    @Override
    public void onClick(View v) {
        NycServerConfigDialog.newInstance()
                .show(getChildFragmentManager(), "nyc_server_config");
    }
});
```

Since `BalanceFragment` hosts the dialog but is not an Activity, implement the listener on the fragment itself. Add to the `implements` clause:
```java
public class BalanceFragment extends WalletFragment
        implements LoaderCallbacks<List<AbstractTransaction>>,
        NycServerConfigDialog.OnNycServerConfiguredListener {
```

Add the callback:
```java
@Override
public void onNycServerConfigured(@Nullable String hostPort) {
    if (hostPort != null) {
        config.setNycElectrumServer(hostPort);
    }
    updateNoServerBanner();
}
```

- [ ] **Step 15.3: Build — no errors**

```bash
./gradlew :wallet:assembleDebug 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 15.4: Commit**

```bash
git add wallet/src/main/res/layout/fragment_balance_header.xml \
        wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java
git commit -m "feat: add no-server banner to BalanceFragment for NYC ElectrumX config"
```

---

## Chunk 7: NYC Runtime Connection + Final Verification

---

### Task 16: NYC server connection at startup

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ServerClients.java`

`ServerClients` is built in `CoinServiceImpl.getServerClients()` (lines 238–244). This method is
called at service start and on every reconnection — injecting NYC there covers all connection
paths. `WalletApplication` is NOT the right place.

`ServerClients` does not have an `addCoinAddress()` method. Add it first (Step 16.1), then
inject NYC from `getServerClients()` (Step 16.2).

- [ ] **Step 16.1: Add addCoinAddress() to ServerClients.java**

Open `ServerClients.java`. Find the field that stores coin addresses (typically
`Map<CoinType, CoinAddress> addresses`). Add this method to the class:

```java
/**
 * Dynamically add a coin address not present in the static DEFAULT_COINS_SERVERS.
 * Used to inject the NYC ElectrumX server address at runtime.
 */
public void addCoinAddress(CoinAddress coinAddress) {
    addresses.put(coinAddress.getType(), coinAddress);
}
```

Build to confirm it compiles:
```bash
./gradlew :wallet:compileDebugJava 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 16.2: Inject NYC server in CoinServiceImpl.getServerClients()**

Open `CoinServiceImpl.java`. Find `getServerClients()` (around line 238). After `ServerClients`
is constructed from `DEFAULT_COINS_SERVERS`, add the NYC injection block:

```java
// NYC: ElectrumX server is configured at runtime — not in DEFAULT_COINS_SERVERS.
// getServerClients() is called at startup and on every reconnection, so this
// injection covers all connection paths.
String nycServer = application.getConfiguration().getNycElectrumServer();
if (nycServer != null && !nycServer.isEmpty()) {
    int lastColon = nycServer.lastIndexOf(':');
    String host = nycServer.substring(0, lastColon);
    int port = Integer.parseInt(nycServer.substring(lastColon + 1));
    serverClients.addCoinAddress(new CoinAddress(
        NewYorkCoinMain.get(),
        new ServerAddress(host, port),
        new ServerAddress(host, port)
    ));
}
```

Add imports at the top of `CoinServiceImpl.java`:
```java
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.network.CoinAddress;
import com.openwallet.stratumj.ServerAddress;
```

- [ ] **Step 16.3: Build — no errors**

```bash
./gradlew :wallet:assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 16.4: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/ServerClients.java \
        wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java
git commit -m "feat: inject NYC ElectrumX server from Configuration in CoinServiceImpl.getServerClients()"
```

---

### Task 17: Final verification

- [ ] **Step 17.1: Run all tests**

```bash
./gradlew test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL — all tests pass including `NewYorkCoinTest` and `ZcashTest`

- [ ] **Step 17.2: Build release APK**

```bash
./gradlew :wallet:assembleDebug 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 17.3: Verify APK package name**

```bash
aapt dump badging \
    /d/NewYorkCoin_2026/nyc-openwallet-android/wallet/build/outputs/apk/debug/wallet-debug.apk \
    2>/dev/null | grep "package: name"
```

Expected: `package: name='com.newyorkcoin.openwallet'`

- [ ] **Step 17.4: Final commit + tag**

```bash
cd /d/NewYorkCoin_2026/nyc-openwallet-android
git add -A
git status  # confirm nothing untracked
git log --oneline -15  # review commit history
git tag v1.0.0-phase1-alpha
```

---

## Success Criteria Checklist

- [ ] `./gradlew test` passes — `NewYorkCoinTest` (5 tests) and `ZcashTest` (10 tests) all green
- [ ] `./gradlew :wallet:assembleDebug` succeeds
- [ ] APK `applicationId` = `com.newyorkcoin.openwallet`
- [ ] NYC is first coin in `SUPPORTED_COINS` and `DEFAULT_COIN`
- [ ] Adding NYC first time → `NycServerConfigDialog` appears
- [ ] Skipping dialog → `BalanceFragment` shows "No ElectrumX server configured" banner
- [ ] Tapping banner → dialog reopens; saving server → banner hides
- [ ] NYC `N...` address generated from standard test mnemonic
- [ ] ZEC `t1...` address generated from standard test mnemonic
- [ ] BTC, LTC, DOGE compile and their existing tests pass (no regressions)
