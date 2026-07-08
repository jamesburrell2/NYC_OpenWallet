# NewYorkCoin Height-Gated SegWit Reveal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NewYorkCoin expose only Legacy addresses until the wallet's own synced NYC height reaches block 13,500,000, then automatically reveal P2SH-SegWit and native-SegWit address types.

**Architecture:** A stateless, height-aware `CoinType` gains a per-coin `segwitActivationHeight` plus `isSegwitActivatedAt(height)` / `effectiveAddressTypes(height)` helpers. Address generation (`WalletPocketHD.getActiveAddresses`) and the receive UI (`AddressRequestFragment`) compute the effective address types live from the pocket's `lastBlockSeenHeight`; pre-activation they hide SegWit and stop new SegWit gap-limit generation, while keeping any already-issued (used) SegWit address in the watch list.

**Tech Stack:** Java, bitcoinj-core-0.12.3-openwallet (vendored), `com.openwallet.core.coins` / `com.openwallet.core.wallet`, JUnit, Gradle (`./gradlew.bat`).

## Global Constraints

- Balance-critical wallet: no change may drop, double-count, or mis-attribute a UTXO. Every task ends with `:core:test` green at the established baseline — **314 tests, exactly 36 pre-existing failures, no NEW failures**. New tests raise the pass count; failures stay at 36.
- Preserve the wallet-file format: an existing single-keychain NYC pocket MUST deserialize and behave legacy-only for new addresses, byte-for-byte unchanged.
- Follow existing conventions (class-name-only logging per the SOC-2 rule; no secrets/seeds in logs).
- SegWit activation height for NYC is exactly **13_500_000** (buried deployment, verified on the live v2.1.0 node). Already-active coins (BTC/LTC/DGB/VTC) use `segwitActivationHeight = 0`.
- Purpose→AddressType mapping is fixed: LEGACY (44'/P2PKH), COMPATIBLE (49'/P2SH-SegWit), NATIVE_SEGWIT (84'/bech32).
- Build/verify: `./gradlew.bat :core:test` (unit), `./gradlew.bat :wallet:assembleDebug` (APK).

## File Structure

- `core/.../coins/CoinType.java` — add `segwitActivationHeight` field + `getSegwitActivationHeight()`, `isSegwitActivatedAt(int)`, `effectiveAddressTypes(int)`; redefine `isSegwitActivated()`.
- `core/.../coins/NewYorkCoinMain.java` — set `segwitActivationHeight`, remove `segwitActivated = false`.
- `core/.../wallet/SimpleHDKeyChain.java` — add `isIssued(DeterministicKey)`.
- `core/.../wallet/WalletPocketHD.java` — gate `getActiveAddresses()` (both branches).
- `wallet/.../ui/AddressRequestFragment.java` — use effective types from account height + stale-selection fallback.
- `wallet/.../ui/dialogs/ConfirmAddCoinUnlockWalletDialog.java` — no code change (already reads `isSegwitActivated()`); verified in Task 6.
- Tests (new): `SegwitActivationHeightTest.java`, `KeyChainIsIssuedTest.java`, `NycSegwitRevealTest.java`.

---

## Task 1: Height-aware CoinType + NYC activation height

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinType.java` (field near line 52-56; methods near 132-137)
- Modify: `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java` (lines 33-37)
- Test: `core/src/test/java/com/openwallet/core/coins/SegwitActivationHeightTest.java` (create)

**Interfaces:**
- Produces: `int CoinType.getSegwitActivationHeight()`; `boolean CoinType.isSegwitActivatedAt(int height)`; `Set<AddressType> CoinType.effectiveAddressTypes(int height)`; `boolean CoinType.isSegwitActivated()` redefined as `segwitActivationHeight <= 0`.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/openwallet/core/coins/SegwitActivationHeightTest.java`:

```java
package com.openwallet.core.coins;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class SegwitActivationHeightTest {

    @Test
    public void nycGatedBelowActivationHeight() {
        CoinType nyc = NewYorkCoinMain.get();
        assertEquals(13_500_000, nyc.getSegwitActivationHeight());
        assertFalse("unsynced (-1) must be legacy-only", nyc.isSegwitActivatedAt(-1));
        assertFalse(nyc.isSegwitActivatedAt(13_499_999));
        assertTrue(nyc.isSegwitActivatedAt(13_500_000));
        assertTrue(nyc.isSegwitActivatedAt(20_000_000));
    }

    @Test
    public void nycEffectiveTypesLegacyOnlyBeforeActivation() {
        CoinType nyc = NewYorkCoinMain.get();
        Set<AddressType> before = nyc.effectiveAddressTypes(13_499_999);
        assertTrue(before.contains(AddressType.LEGACY));
        assertFalse(before.contains(AddressType.COMPATIBLE));
        assertFalse(before.contains(AddressType.NATIVE_SEGWIT));

        Set<AddressType> after = nyc.effectiveAddressTypes(13_500_000);
        assertTrue(after.contains(AddressType.LEGACY));
        assertTrue(after.contains(AddressType.COMPATIBLE));
        assertTrue(after.contains(AddressType.NATIVE_SEGWIT));
    }

    @Test
    public void nycStaticFlagFalseForAddCoinGate() {
        assertFalse(NewYorkCoinMain.get().isSegwitActivated());
    }

    @Test
    public void bitcoinAlwaysActivated() {
        CoinType btc = BitcoinMain.get();
        assertEquals(0, btc.getSegwitActivationHeight());
        assertTrue(btc.isSegwitActivatedAt(-1));
        assertTrue(btc.isSegwitActivatedAt(0));
        assertTrue(btc.isSegwitActivated());
        assertEquals(btc.getSupportedAddressTypes(), btc.effectiveAddressTypes(-1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.coins.SegwitActivationHeightTest"`
Expected: FAIL — `getSegwitActivationHeight()` / `isSegwitActivatedAt` / `effectiveAddressTypes` do not exist (compile error).

- [ ] **Step 3: Implement CoinType helpers**

In `CoinType.java`, replace the `segwitActivated` field (lines 55-56):

```java
    protected String bech32Hrp = null;
    /** Block height at which SegWit activates on this coin's network.
     *  0 means already active (the default for coins that shipped with SegWit). */
    protected int segwitActivationHeight = 0;
```

Replace the `isSegwitActivated()` accessor (around line 136-137) and add the height-aware helpers:

```java
    public String getBech32Hrp() { return bech32Hrp; }

    /** Block height at which SegWit activates; 0 means already active. */
    public int getSegwitActivationHeight() { return segwitActivationHeight; }

    /** True if SegWit is active at the given (wallet-synced) block height.
     *  A negative/unknown height fails closed (legacy-only). */
    public boolean isSegwitActivatedAt(int height) {
        return segwitActivationHeight <= 0 || (height >= 0 && height >= segwitActivationHeight);
    }

    /** Static "already active" check for paths with no synced height (e.g. add-coin). */
    public boolean isSegwitActivated() { return segwitActivationHeight <= 0; }

    /** Address types that may be used/shown at the given synced height: the full
     *  supported set once SegWit is active, otherwise supported minus the SegWit types. */
    public Set<AddressType> effectiveAddressTypes(int height) {
        if (isSegwitActivatedAt(height)) return supportedAddressTypes;
        EnumSet<AddressType> effective = EnumSet.copyOf(supportedAddressTypes);
        effective.remove(AddressType.COMPATIBLE);
        effective.remove(AddressType.NATIVE_SEGWIT);
        return Collections.unmodifiableSet(effective);
    }
```

Ensure `java.util.EnumSet` and `java.util.Collections` are imported (they already are — `supportedAddressTypes` uses both).

In `NewYorkCoinMain.java`, replace lines 33-37 (the `supportedAddressTypes` block plus `segwitActivated = false;`):

```java
        bech32Hrp = "nyc";
        supportedAddressTypes = Collections.unmodifiableSet(EnumSet.of(
                AddressType.LEGACY,
                AddressType.COMPATIBLE,
                AddressType.NATIVE_SEGWIT));
        segwitActivationHeight = 13_500_000;   // buried SegWit activation on NYC mainnet
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.coins.SegwitActivationHeightTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/coins/CoinType.java core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java core/src/test/java/com/openwallet/core/coins/SegwitActivationHeightTest.java
git commit -m "CoinType: height-aware SegWit activation; NYC gated at block 13,500,000

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: SimpleHDKeyChain.isIssued(key)

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/SimpleHDKeyChain.java`
- Test: `core/src/test/java/com/openwallet/core/wallet/KeyChainIsIssuedTest.java` (create)

**Interfaces:**
- Consumes: existing `getKey(KeyPurpose)` (issues next key), `getCurrentUnusedKey(KeyPurpose)` (does not issue), private `externalKey`/`internalKey`, `issuedExternalKeys`/`issuedInternalKeys`, `isLeaf(...)`.
- Produces: `boolean SimpleHDKeyChain.isIssued(DeterministicKey key)` — true iff `key` is a leaf whose child index is below the issued count for its branch.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/openwallet/core/wallet/KeyChainIsIssuedTest.java`:

```java
package com.openwallet.core.wallet;

import com.openwallet.core.coins.NewYorkCoinMain;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class KeyChainIsIssuedTest {

    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale",
            "nurse", "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    private SimpleHDKeyChain nycChain() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        DeterministicHierarchy h = new DeterministicHierarchy(master);
        return new SimpleHDKeyChain(h.get(NewYorkCoinMain.get().getBip44Path(0), false, true));
    }

    @Test
    public void issuedKeyIsIssuedUnusedIsNot() throws Exception {
        SimpleHDKeyChain kc = nycChain();
        DeterministicKey issued = kc.getKey(KeyChain.KeyPurpose.RECEIVE_FUNDS); // issues external index 0
        assertTrue("issued index-0 key must report issued", kc.isIssued(issued));

        DeterministicKey unused = kc.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS); // index 1
        assertFalse("next unused key must report not-issued", kc.isIssued(unused));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.KeyChainIsIssuedTest"`
Expected: FAIL — `isIssued(...)` does not exist (compile error).

- [ ] **Step 3: Implement isIssued**

In `SimpleHDKeyChain.java`, add near `markKeyAsUsed` (after line ~347):

```java
    /**
     * Returns true if the key is a leaf that has already been issued (its child index is
     * below the issued counter for its branch). Lookahead keys return false. Used to keep
     * watching already-issued addresses while gating not-yet-issued ones.
     */
    public boolean isIssued(DeterministicKey key) {
        if (!isLeaf(key)) return false;
        if (key.getParent() == externalKey) {
            return key.getChildNumber().num() < issuedExternalKeys;
        }
        if (key.getParent() == internalKey) {
            return key.getChildNumber().num() < issuedInternalKeys;
        }
        return false;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.KeyChainIsIssuedTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/SimpleHDKeyChain.java core/src/test/java/com/openwallet/core/wallet/KeyChainIsIssuedTest.java
git commit -m "SimpleHDKeyChain: add isIssued(key) predicate

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Gate getActiveAddresses on synced height

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` (`getActiveAddresses`, lines ~749-786)
- Test: `core/src/test/java/com/openwallet/core/wallet/NycSegwitRevealTest.java` (create)

**Interfaces:**
- Consumes: `CoinType.isSegwitActivatedAt(int)` (Task 1); `SimpleHDKeyChain.isIssued(DeterministicKey)` (Task 2); existing `getLastBlockSeenHeight()`, `setLastBlockSeenHeight(int)`, `maybeInitializeAllKeys()`, `markAddressAsUsed(AbstractAddress)`, single-keychain ctor `WalletPocketHD(DeterministicKey rootKey, CoinType, KeyCrypter, KeyParameter)`.
- Produces: `getActiveAddresses()` that emits SegWit script types only when SegWit is active at the pocket's synced height OR the key is already issued.

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/com/openwallet/core/wallet/NycSegwitRevealTest.java`:

```java
package com.openwallet.core.wallet;

import com.openwallet.core.coins.AddressType;
import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.NewYorkCoinMain;
import com.openwallet.core.util.AbstractAddress;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.KeyChain;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.junit.Assert.*;

public class NycSegwitRevealTest {

    static final List<String> MNEMONIC = ImmutableList.of("citizen", "fever", "scale",
            "nurse", "brief", "round", "ski", "fiction", "car", "fitness", "pluck", "act");

    private final CoinType nyc = NewYorkCoinMain.get();
    private DeterministicHierarchy hierarchy() throws Exception {
        DeterministicSeed seed = new DeterministicSeed(MNEMONIC, null, "", 0);
        DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(checkNotNull(seed.getSeedBytes()));
        return new DeterministicHierarchy(master);
    }
    private WalletPocketHD nycPocket() throws Exception {
        WalletPocketHD p = new WalletPocketHD(
                hierarchy().get(nyc.getBip44Path(0), false, true), nyc, null, null);
        p.maybeInitializeAllKeys();
        return p;
    }
    private Set<String> addrs(WalletPocketHD p) {
        Set<String> s = new HashSet<>();
        for (AbstractAddress a : p.getActiveAddresses()) s.add(a.toString());
        return s;
    }

    @Test
    public void belowActivationNoSegwitForUnusedKeys() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_499_999);
        Set<String> a = addrs(p);
        assertTrue("must still emit legacy addresses", a.stream().anyMatch(x -> x.startsWith("R")));
        assertTrue("no bech32 before activation", a.stream().noneMatch(x -> x.startsWith("nyc1")));
    }

    @Test
    public void atActivationSegwitRevealed() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_500_000);
        Set<String> a = addrs(p);
        assertTrue("bech32 revealed at activation", a.stream().anyMatch(x -> x.startsWith("nyc1")));
    }

    @Test
    public void belowActivationKeepsWatchingUsedSegwitAddress() throws Exception {
        WalletPocketHD p = nycPocket();
        p.setLastBlockSeenHeight(13_499_999);

        // Derive index-0 external key; its legacy + native-segwit addresses share that key.
        SimpleHDKeyChain probe = new SimpleHDKeyChain(hierarchy().get(nyc.getBip44Path(0), false, true));
        DeterministicKey k0 = probe.getCurrentUnusedKey(KeyChain.KeyPurpose.RECEIVE_FUNDS);
        String legacy0 = nyc.addressFromKey(k0, AddressType.LEGACY).toString();
        String segwit0 = nyc.addressFromKey(k0, AddressType.NATIVE_SEGWIT).toString();

        // Mark the index-0 address used (bumps the issued counter for key 0).
        AbstractAddress legacyAddr = null;
        for (AbstractAddress a : p.getActiveAddresses()) {
            if (a.toString().equals(legacy0)) { legacyAddr = a; break; }
        }
        assertNotNull("index-0 legacy address must be active", legacyAddr);
        p.markAddressAsUsed(legacyAddr);

        // Even below activation, the used key's segwit sibling stays watched.
        assertTrue("used key's segwit address must remain watched",
                addrs(p).contains(segwit0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.NycSegwitRevealTest"`
Expected: FAIL — today `getActiveAddresses` emits `nyc1…` for all keys regardless of height, so `belowActivationNoSegwitForUnusedKeys` fails.

- [ ] **Step 3: Implement the gate**

In `WalletPocketHD.getActiveAddresses()`, replace the whole `try { ... }` body (lines ~752-780) with:

```java
        try {
            ImmutableList.Builder<AbstractAddress> activeAddresses = ImmutableList.builder();
            boolean segwitOn = type.isSegwitActivatedAt(getLastBlockSeenHeight());
            if (bundled) {
                // Each branch has its OWN keys; emit only that branch's script type so the
                // watched addresses match Coinomi's per-purpose derivation exactly.
                for (int i = 0; i < keychains.size(); i++) {
                    AddressType purpose = purposes.get(i);
                    boolean segwitPurpose = purpose == AddressType.COMPATIBLE
                            || purpose == AddressType.NATIVE_SEGWIT;
                    SimpleHDKeyChain kc = keychains.get(i);
                    for (DeterministicKey key : kc.getActiveKeys()) {
                        // Pre-activation, skip a segwit branch's not-yet-issued keys.
                        if (segwitPurpose && !segwitOn && !kc.isIssued(key)) continue;
                        activeAddresses.add(type.addressFromKey(key, purpose));
                    }
                }
            } else {
                // Single-path pocket: emit legacy for every key, and segwit types either
                // once activated at the synced height or for already-issued (used) keys.
                Set<AddressType> supported = type.getSupportedAddressTypes();
                for (DeterministicKey key : keys.getActiveKeys()) {
                    activeAddresses.add(type.addressFromKey(key, AddressType.LEGACY));
                    boolean emitSegwit = segwitOn || keys.isIssued(key);
                    if (emitSegwit && supported.contains(AddressType.COMPATIBLE)) {
                        activeAddresses.add(type.addressFromKey(key, AddressType.COMPATIBLE));
                    }
                    if (emitSegwit && supported.contains(AddressType.NATIVE_SEGWIT)) {
                        activeAddresses.add(type.addressFromKey(key, AddressType.NATIVE_SEGWIT));
                    }
                    // TAPROOT is receive/display only — not included in watch list
                }
            }
            return activeAddresses.build();
        } finally {
            lock.unlock();
        }
```

Note: `getLastBlockSeenHeight()` takes the pocket lock, which is a reentrant `ReentrantLock` already held here — safe. `keys` is the single-keychain accessor (`keychains.get(0)`); `isIssued` is Task 2.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.NycSegwitRevealTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the BTC regression suites (single-path + bundled unchanged)**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.BundledAccountTest" --tests "com.openwallet.core.wallet.WalletPocketHDTest" --tests "com.openwallet.core.wallet.MarkAddressAsUsedTest"`
Expected: PASS — BTC/LTC pockets have `segwitActivationHeight = 0`, so `segwitOn` is always true and behavior is identical to before. (BTC bundled/single-path tests set no height → `getLastBlockSeenHeight()` returns -1, but BTC is always active regardless, so all script types still emit.)

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java core/src/test/java/com/openwallet/core/wallet/NycSegwitRevealTest.java
git commit -m "WalletPocketHD: gate SegWit address generation on synced height

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Receive UI shows effective address types

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java` (lines ~215, ~227, ~493, ~507)

**Interfaces:**
- Consumes: `CoinType.effectiveAddressTypes(int)` (Task 1); `WalletPocketHD.getLastBlockSeenHeight()`.
- Produces: receive selector + address resolution that use the account's effective types; no new public API.

- [ ] **Step 1: Add a height-aware helper and use it for the selector**

Near the top of `onCreateView`/setup where `type` is assigned (after `type = account.getCoinType();`, ~line 201), add a local helper value and reuse it. Introduce a private method:

```java
    /** Address types effective for this account right now, honoring height-gated SegWit. */
    private java.util.Set<AddressType> effectiveTypes() {
        int height = (account instanceof WalletPocketHD)
                ? ((WalletPocketHD) account).getLastBlockSeenHeight() : -1;
        return type.effectiveAddressTypes(height);
    }
```

Replace the selector guard and membership check (lines ~215 and ~227):

```java
        // Configure address type tab strip for SegWit-capable coins
        java.util.Set<AddressType> effective = effectiveTypes();
        if (effective.size() > 1) {
            addressTypeRadioGroup.setVisibility(View.VISIBLE);
            ...
            for (int i = 0; i < displayOrder.length; i++) {
                if (!effective.contains(displayOrder[i])) continue;
                ...
```

- [ ] **Step 2: Use effective types in address resolution + stale-selection fallback**

Replace the two remaining reads (custom-path check ~line 493 and the legacy/size-1 branch ~line 507):

```java
                            AddressType addrType = inferAddressType(path);
                            if (!effectiveTypes().contains(addrType)) {
                                addrType = AddressType.LEGACY;
                            }
```

```java
            } else if (selectedAddressType == AddressType.LEGACY
                    || effectiveTypes().size() == 1) {
                receiveAddress = legacyAddr;
                if (effectiveTypes().size() > 1 && account instanceof WalletPocketHD) {
```

Also, where `selectedAddressType` is initialized/used to pick the default button, guard against a stale persisted selection: if `!effectiveTypes().contains(selectedAddressType)`, set `selectedAddressType = AddressType.LEGACY;` before building the tab strip (add this line just above the `if (effective.size() > 1)` block from Step 1).

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew.bat :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL. (The gating logic itself is covered by `SegwitActivationHeightTest`; this fragment change is a mechanical substitution of `getSupportedAddressTypes()` → `effectiveTypes()`.)

- [ ] **Step 4: Commit**

```bash
git add wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java
git commit -m "Receive UI: show only height-effective address types for NYC

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Full baseline + add-coin gate verification

**Files:**
- Verify only: `wallet/src/main/java/com/openwallet/wallet/ui/dialogs/ConfirmAddCoinUnlockWalletDialog.java` (line ~87 reads `type.isSegwitActivated()`)

- [ ] **Step 1: Full core test baseline**

Run: `./gradlew.bat :core:test`
Expected: **314+ tests completed, exactly 36 failed** — the 36 pre-existing failures only, no NEW failures; the 9 new tests (Tasks 1-3) pass.

- [ ] **Step 2: Verify the add-coin dialog gate is preserved**

Read `ConfirmAddCoinUnlockWalletDialog.java` line ~87: it computes `segwitActivated = type.isSegwitActivated()` and disables/greys the COMPATIBLE/bundled rows when false. With the Task 1 redefinition, NYC's `isSegwitActivated()` still returns `false` (13,500,000 > 0) and BTC's returns `true` (0). So the dialog behaves exactly as before — no code change required. Confirm by reading the file; do not edit.

- [ ] **Step 3: Build the debug APK**

Run: `./gradlew.bat :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL; APK at `wallet/build/outputs/apk/debug/wallet-debug.apk`.

- [ ] **Step 4: Commit (if any doc/verification notes were added)**

```bash
git commit --allow-empty -m "Verify NYC SegWit reveal: baseline green, add-coin gate preserved

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Device verification (manual)

- [ ] **Step 1: Install and confirm pre-activation behavior**

```bash
./gradlew.bat :wallet:assembleDebug
& $adb install -r wallet/build/outputs/apk/debug/wallet-debug.apk
```

Open a NewYorkCoin account → **Receive** tab: the address-type selector must be **absent** (legacy only), and the shown address must start with `R` (never `nyc1`). Open **Add coin → NewYorkCoin**: the "All types (Coinomi-style)" / SegWit options must be disabled with the "not activated" note. Bitcoin's Receive tab must still show all three types (regression check).

- [ ] **Step 2: Confirm the reveal logic via logcat address subscriptions**

Capture ~60s of logcat while the NYC account syncs; confirm no `nyc1q…` NYC scripthashes are subscribed for unused keys (only `R…`/legacy), whereas before this change ~302 `nyc1q…` addresses were subscribed. (Post-activation behavior at real block 13,500,000 is validated by the Task 1/3 unit tests, since mainnet is ~672k blocks short of it.)

---

## Self-Review Notes

- **Spec coverage:** §1 CoinType → Task 1; §2 generation gate (both branches + used-key rule) → Task 3 (uses Task 2's `isIssued`); §3 receive UI → Task 4; §4 add-coin gate → Task 5 (no-op verification, preserved by Task 1's `isSegwitActivated()` redefinition). Testing + backward-compat → Tasks 1-3 unit tests + Task 5 baseline. Edge cases: unsynced `-1` (Task 1 test), used-key retention (Task 3 test), stale selection (Task 4 Step 2), incoming/spend not gated (untouched paths).
- **Type consistency:** `getSegwitActivationHeight`/`isSegwitActivatedAt`/`effectiveAddressTypes`/`isSegwitActivated` (Task 1) are consumed verbatim in Tasks 3-5; `isIssued(DeterministicKey)` (Task 2) is consumed in Task 3; single-keychain ctor and `getLastBlockSeenHeight()` match existing signatures.
- **Risk:** Balance-critical generation path (Task 3). Mitigated by the used-key retention test and the BTC regression suites (BTC always-active ⇒ identical behavior). The 36-failure baseline gates every task.
- **Independence:** Tasks 1-2 are pure additions; Task 3 depends on both; Task 4 depends on Task 1; Task 5 is verification. Each ends with an independently testable deliverable.
