# BTC Sync Reliability + Multi-Path Accounts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (A) Stop the reconnect-driven re-sync loop that makes multi-transaction Electrum accounts sync very slowly, and (B) let a single OpenWallet Bitcoin account track all three Coinomi derivation branches (44'/49'/84') under one account index, so it shows the complete history.

**Architecture:** Part A is a surgical change to `TransactionWatcherWallet`'s connect/disconnect lifecycle: stop nuking committed address statuses on every (re)connection, and instead reconcile UTXOs for already-known addresses when the in-memory UTXO set is empty. Part B generalizes `WalletPocketHD` from a single `SimpleHDKeyChain` to an ordered list of purpose-tagged keychains (44'→legacy, 49'→P2SH-segwit, 84'→native-segwit), aggregating address generation, key lookup, signing and serialization across them, with backward-compatible loading of existing single-path pockets.

**Tech Stack:** Java, bitcoinj-core-0.12.3-openwallet (vendored), Electrum/Stratum protocol, protobuf (`com.openwallet.core.protos.Protos`), JUnit.

## Global Constraints

- Wallet is balance-critical: no change may drop, double-count, or mis-attribute a UTXO. Every task ends with `:core:test` green at the established baseline (**304 tests, exactly 36 pre-existing failures** — MessagesTest serialization + others; no NEW failures).
- Preserve the existing wallet-file format for already-saved single-path pockets: an existing `wallet_1` protobuf MUST still deserialize and behave identically.
- Follow existing code conventions (class-name-only logging per the repo's SOC-2 rule; no secrets/seeds in logs).
- Build/verify commands: `./gradlew.bat :core:test` (unit), `./gradlew.bat :wallet:assembleDebug` (APK). `adb` is at `C:\Users\james\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on PATH); device paths go through PowerShell.
- Both apps `com.newyorkcoin.openwallet` (release, now the local key) and `.debug` install side-by-side; the debug build is the test vehicle.

---

## Part A — Reconnect Re-Sync Loop Fix

**Problem (root-caused 2026-07-05):** `TransactionWatcherWallet.onConnection()` calls `clearTransientState()` (clears `statusPendingUpdates`, `addressesSubscribed`, `addressesPendingSubscription`) **and**, at lines ~1237-1239, `if (unspentOutputs.isEmpty()) addressesStatus.clear();`. During the initial sync of an account with many txs, the UTXO set is empty until the *first* UTXO commits. On the Pixel Fold's flaky link the Bitcoin connection reconnects repeatedly, so every reconnect wipes all committed `addressesStatus` → every address re-appears "changed" (`isAddressStatusChanged` true) → `registerStatusForUpdate` true → the same addresses re-fetch history each cycle. Measured: 14 distinct `bc1q` addresses fetched 8× each with **identical** status hashes.

**Why the wipe exists:** it works around a real bug — a wallet persisted with committed non-null `addressesStatus` but an empty UTXO set. On reconnect the cached status equals the server's reply, so `onAddressStatusUpdate` treats it as unchanged and *skips* the UTXO fetch, leaving the balance stuck at 0.

**Fix strategy:** decouple the two concerns. Keep committed `addressesStatus` across reconnects (so unchanged addresses don't re-fetch history), and separately, when the in-memory UTXO set is empty on connect, re-issue an **unspent-only** fetch for each committed non-null-status address. This reconciles UTXOs without discarding discovery progress.

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/TransactionWatcherWallet.java` (onConnection ~1226-1243; add `reconcileUnspentForCommittedAddresses()`)
- Test: `core/src/test/java/com/openwallet/core/wallet/ReconnectResyncTest.java` (create)

**Interfaces:**
- Consumes (existing): `Map<AbstractAddress,String> addressesStatus`; `Map<TrimmedOutPoint,OutPointOutput> unspentOutputs`; `BitBlockchainConnection blockchainConnection` with `getUnspentTx(AddressStatus, BitTransactionEventListener)`; `AddressStatus(AbstractAddress,String)`; `void clearTransientState()`.
- Produces: `private void reconcileUnspentForCommittedAddresses()` (lock-held); modified `onConnection` that no longer clears `addressesStatus`.

- [ ] **Step 1: Write the failing test** — reconnect must NOT re-fetch history for an address whose committed status is unchanged.

Create `core/src/test/java/com/openwallet/core/wallet/ReconnectResyncTest.java`. Use the existing `WalletPocketHDTest` setup pattern (mnemonic `citizen fever scale nurse brief round ski fiction car fitness pluck act`, `BitcoinMain`). Use a fake `BitBlockchainConnection` that records `getUnspentTx`/`getHistoryTx`/`subscribeToAddresses` calls.

```java
@Test
public void reconnectWithUnchangedStatusDoesNotRefetchHistory() {
    // Arrange: pocket with one committed address + non-empty UTXO set
    WalletPocketHD pocket = /* build from hierarchy.get(BTC.getBip84Path(7), false, true) */;
    RecordingConnection conn = new RecordingConnection();
    pocket.onConnection(conn);
    AbstractAddress a = pocket.getReceiveAddress();
    pocket.onAddressStatusUpdate(new AddressStatus(a, "status-hash-1"));
    // feed unspent + history so the status commits and a UTXO exists
    conn.deliverUnspentAndHistoryFor(a, /* one utxo */);
    int historyCallsAfterFirstSync = conn.historyCalls;

    // Act: reconnect, server reports the SAME status
    pocket.onConnection(conn);
    pocket.onAddressStatusUpdate(new AddressStatus(a, "status-hash-1"));

    // Assert: no new history fetch for the unchanged, already-committed address
    assertEquals(historyCallsAfterFirstSync, conn.historyCalls);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.ReconnectResyncTest"`
Expected: FAIL — with today's code, `onConnection` clears `addressesStatus` (UTXO set is emptied by `clearTransientState`'s siblings on the second connect path) so the status is treated as changed and history is re-fetched.

- [ ] **Step 3: Implement the fix in `onConnection`**

Replace the body of `onConnection` (currently clears `addressesStatus` when `unspentOutputs.isEmpty()`):

```java
@Override
public void onConnection(BlockchainConnection blockchainConnection) {
    lock.lock();
    try {
        this.blockchainConnection = (BitBlockchainConnection) blockchainConnection;
        clearTransientState(); // clears subscription tracking + in-flight; NOT addressesStatus
        // Do NOT wipe committed addressesStatus on reconnect — that restarts
        // discovery every time the link flaps. Instead, if we have committed
        // statuses but no UTXOs in memory yet, reconcile UTXOs directly so the
        // balance is not stuck at zero (the case the old clear worked around).
        if (unspentOutputs.isEmpty() && !addressesStatus.isEmpty()) {
            reconcileUnspentForCommittedAddresses();
        }
        queueOnConnectivity();
    } finally {
        lock.unlock();
    }
}

/** Re-fetch UTXOs (only) for every address with a committed non-null status,
 *  without discarding the committed status (so history is not re-fetched). */
private void reconcileUnspentForCommittedAddresses() {
    checkState(lock.isHeldByCurrentThread(), "Lock is held by another thread");
    if (blockchainConnection == null) return;
    for (Map.Entry<AbstractAddress, String> e : addressesStatus.entrySet()) {
        if (e.getValue() == null) continue;
        AddressStatus s = new AddressStatus(e.getKey(), e.getValue());
        // register so onUnspentTransactionUpdate accepts the reply, then fetch unspent only
        registerStatusForUpdate(s);
        blockchainConnection.getUnspentTx(s, this);
    }
}
```

Note: `clearTransientState()` already clears `statusPendingUpdates`; call `registerStatusForUpdate(s)` inside the reconcile so `onUnspentTransactionUpdate`'s `updatingStatus.equals(status)` guard passes. Do not call `getHistoryTx` here — history is already committed.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.ReconnectResyncTest"`
Expected: PASS.

- [ ] **Step 5: Add the empty-UTXO regression test** — reconnect with committed status but empty UTXO set must trigger an unspent fetch (guards the bug the old clear fixed).

```java
@Test
public void reconnectWithEmptyUtxoSetReconcilesUnspent() {
    WalletPocketHD pocket = /* built as above, with a committed status but UTXO set emptied */;
    RecordingConnection conn = new RecordingConnection();
    pocket.onConnection(conn);
    assertTrue("must re-request unspent for committed addresses", conn.unspentCalls > 0);
}
```

Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.ReconnectResyncTest"` → Expected: PASS (both tests).

- [ ] **Step 6: Full baseline + commit**

Run: `./gradlew.bat :core:test` → Expected: `304 tests completed, 36 failed` (no NEW failures; +2 new tests pass → total rises accordingly, failures stay 36).

```bash
git add core/src/main/java/com/openwallet/core/wallet/TransactionWatcherWallet.java core/src/test/java/com/openwallet/core/wallet/ReconnectResyncTest.java
git commit -m "Don't restart Electrum discovery on every reconnect

onConnection() wiped all committed addressesStatus whenever the in-memory
UTXO set was empty, so on a flaky link every reconnect re-fetched history for
all addresses (measured 14 addresses x8). Keep committed statuses across
reconnects; when UTXOs are empty, reconcile UTXOs for committed addresses
directly instead of discarding discovery.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 7: Device verification** — build, install debug, restore StampHash `m/84'/0'/7'`, and confirm the re-fetch loop is gone.

```bash
./gradlew.bat :wallet:assembleDebug
& $adb install -r wallet/build/outputs/apk/debug/wallet-debug.apk
```
Open the StampHash account → Receive tab. Capture ~60s of logcat and count distinct vs total "Must get transactions": total should approach distinct (was 113 for 14). Confirm the balance reaches the full amount faster than before.

---

## Part B — Multi-Path Account Bundling (44'/49'/84')

**Problem:** Coinomi's "account N" spans three BIP purposes under one index: `m/44'/coin'/N'` (legacy `1…`), `m/49'/coin'/N'` (P2SH-segwit `3…`), `m/84'/coin'/N'` (native-segwit `bc1q…`) — each with **different keys**. OpenWallet models one pocket per derivation path, so a custom-path `m/84'/0'/7'` pocket only ever sees the `84'` branch; txs to the `1…`/`3…` addresses (e.g. the missing 320/321-sat internal transfers) are invisible. Goal: one Bitcoin account that watches, receives on, and can spend from all three branches, matching Coinomi.

**Design:** Generalize `WalletPocketHD` to hold an ordered `List<SimpleHDKeyChain> keychains`, each tagged with an `AddressType` (`LEGACY`→44', `COMPATIBLE`→49', `NATIVE_SEGWIT`→84'). All existing single-keychain behavior is the 1-element case. Address generation, "active address" enumeration, key lookup and signing iterate the list; the receive default is the native (84') keychain. Serialization stores each keychain's root; deserialization reconstructs the list, defaulting an old single-root pocket to one keychain (backward compatible).

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` (hold `List<SimpleHDKeyChain>` + purpose tags; update `getActiveAddresses`, `getReceiveAddress`/`currentAddress`, `findKeyFromPubHash`, `markAddressAsUsed`, `maybeInitializeAllKeys`, `getAccountIndex`, `getDerivationPath`)
- Modify: `core/src/main/java/com/openwallet/core/wallet/Wallet.java` (new `createBundledBitcoinAccount(coin, accountIndex, key)` building three roots via `getBip44Path`/`getBip49Path`/`getBip84Path`)
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketProtobufSerializer.java` (serialize/deserialize multiple keychains per pocket)
- Modify: `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java` (key lookup for signing across keychains — verify it already routes through `WalletPocketHD.findKeyFromPubHash`)
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/dialogs/ConfirmAddCoinUnlockWalletDialog.java` + `AddCoinTask` + `Wallet` (an "All address types (bundled)" option that creates a bundled account at a chosen index)
- Tests: `core/src/test/java/com/openwallet/core/wallet/BundledAccountTest.java`, `BundledAccountSerializationTest.java`

**Interfaces:**
- Produces: `WalletPocketHD` gains `List<SimpleHDKeyChain> getKeychains()` and a constructor `WalletPocketHD(List<SimpleHDKeyChain> keychains, List<AddressType> purposes, CoinType, KeyCrypter, KeyParameter)`; `Wallet.createBundledBitcoinAccount(CoinType, int accountIndex, KeyParameter)`.
- Each `SimpleHDKeyChain` keeps its existing API; a keychain's purpose determines the `AddressType` emitted for its keys.

### Task B1: WalletPocketHD holds multiple keychains (address generation)

- [ ] **Step 1: Failing test** — a bundled pocket's active addresses include all three script types from the *correct* branches.

`BundledAccountTest.java`: build three roots from the test mnemonic at `BTC.getBip44Path(7)`, `getBip49Path(7)`, `getBip84Path(7)`; construct a bundled `WalletPocketHD`. Assert the account-0/0 addresses equal the independently-derived legacy(44'), P2SH(49'), bech32(84') addresses (compute expected with the same helper used in `BitAddressTest`). Assert `getActiveAddresses()` contains one address of each type per external key index.

```java
@Test
public void bundledActiveAddressesCoverAllThreeBranches() {
    WalletPocketHD acct = bundled(BTC, 7); // helper builds 44'/49'/84' roots
    Set<String> active = toStrings(acct.getActiveAddresses());
    assertTrue(active.contains(expectedBech32_84(7, 0, 0)));
    assertTrue(active.contains(expectedP2sh_49(7, 0, 0)));
    assertTrue(active.contains(expectedLegacy_44(7, 0, 0)));
}
```

- [ ] **Step 2: Run → FAIL** (`bundled(...)` / multi-keychain ctor doesn't exist).
Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.BundledAccountTest"`

- [ ] **Step 3: Implement multi-keychain storage.** Change `WalletPocketHD` field `protected SimpleHDKeyChain keys;` to `protected List<SimpleHDKeyChain> keychains;` plus a parallel `List<AddressType> purposes;`. Keep a `keys()` accessor returning `keychains.get(0)` for the receive/default path so existing single-path call sites stay valid. Rewrite `getActiveAddresses()` to iterate keychains and, for each active key, emit only that keychain's purpose address type:

```java
@Override
public List<AbstractAddress> getActiveAddresses() {
    lock.lock();
    try {
        ImmutableList.Builder<AbstractAddress> out = ImmutableList.builder();
        for (int i = 0; i < keychains.size(); i++) {
            AddressType t = purposes.get(i);
            for (DeterministicKey key : keychains.get(i).getActiveKeys()) {
                out.add(type.addressFromKey(key, t));
            }
        }
        return out.build();
    } finally { lock.unlock(); }
}
```

Add the single-keychain convenience constructor that wraps one keychain with its natural purpose (LEGACY for non-segwit coins, else the pocket's chosen type) so all existing constructors delegate here.

- [ ] **Step 4: Run → PASS.** Run the same command. Fix `getAccountIndex()`/`getDerivationPath()` to report the shared index and the set of purpose paths (e.g. `m/{44',49',84'}/0'/7'`).

- [ ] **Step 5: Commit.**
```bash
git add core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java core/src/test/java/com/openwallet/core/wallet/BundledAccountTest.java
git commit -m "WalletPocketHD: support multiple purpose keychains per account"
```

### Task B2: Key lookup, markAddressAsUsed, signing across keychains

- [ ] **Step 1: Failing test** — `findKeyFromPubHash` finds keys in every branch, and marking a `3…`/`1…` address used advances the right keychain.

```java
@Test
public void findsKeysAcrossAllBranches() {
    WalletPocketHD acct = bundled(BTC, 7);
    acct.maybeInitializeAllKeys();
    byte[] h49 = ((BitAddress) parse(expectedP2sh_49(7,0,0))).getHash160();
    assertNotNull(acct.findKeyFromPubHash(h49)); // was null before (only 84' searched)
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement.** Make `findKeyFromPubHash`, `findKeyFromPubKey`, `findRedeemDataFromScriptHash`, `markAddressAsUsed`, `maybeInitializeAllKeys`, `isPubKeyHashMine`, and any `keys.`-prefixed usage iterate `keychains` and dispatch to the first that matches. For `markAddressAsUsed(SegwitAddress)` and P2SH, resolve the hash160 to the owning keychain (native/legacy share hash160(pubkey); P2SH-segwit uses the redeem-script hash — search each keychain's compatible encoding).

- [ ] **Step 4: Run → PASS**, then run the existing `MarkAddressAsUsedTest` and `BitWalletBaseAddressTest` to ensure single-path behavior is unchanged.
Run: `./gradlew.bat :core:test --tests "com.openwallet.core.wallet.*Address*" --tests "com.openwallet.core.wallet.MarkAddressAsUsedTest"`

- [ ] **Step 5: Verify signing.** Confirm `TransactionCreator.signTransaction` obtains keys via the wallet's `findKeyFromPubHash`/`RedeemData` (not a single keychain). Add a test that spends a UTXO on the `49'` branch from a bundled account and asserts a valid signature. Commit.

### Task B3: Serialization (multi-keychain, backward compatible)

- [ ] **Step 1: Failing test** — round-trip a bundled pocket through protobuf and get all three roots back; AND an existing single-root pocket still loads as a 1-keychain pocket.

`BundledAccountSerializationTest.java`: serialize a bundled pocket, deserialize, assert `getKeychains().size()==3` and addresses match. Second test: hand-craft (or load a fixture of) a single-keychain pocket proto and assert it deserializes to a 1-element keychain list with identical addresses.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement in `WalletPocketProtobufSerializer`.** Serialize each keychain's key tree as today, but tag the pocket with the ordered purpose list (store the account-level path per keychain, which already encodes the purpose: `44'/49'/84'`). On load, group the deterministic keys by their account-level path prefix into one keychain each; if only one prefix is present, produce a single-keychain pocket (old format). Do not bump a format version that breaks old readers — the account-path prefix already disambiguates.

- [ ] **Step 4: Run → PASS**, plus the existing `WalletTest`/`WalletPocketHDTest` serialization paths.

- [ ] **Step 5: Commit.**

### Task B4: UI — create a bundled account

- [ ] **Step 1: Failing test (unit on Wallet).** `Wallet.createBundledBitcoinAccount(BTC, 7, null)` returns a `WalletPocketHD` with three keychains at `44'/49'/84' /0'/7'`.

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement** `createBundledBitcoinAccount` (mirror `createAndAddAccountAtPath`, deriving three roots via `coin.getBip44Path/getBip49Path/getBip84Path(index)`), and add an "All types (Coinomi-style)" choice to `ConfirmAddCoinUnlockWalletDialog` that routes through `AddCoinTask` → this method with the account index parsed from the advanced field.

- [ ] **Step 4: Run → PASS.** Commit.

- [ ] **Step 5: Device verification.** Build/install debug; add a bundled Bitcoin account at index 7; confirm it shows the `bc1q…`, `3…`, and `1…` histories together, including the 320/321-sat internal transfers, matching Coinomi's "Bitcoin #8 / Panda".

---

## Self-Review Notes
- **Spec coverage:** Part A covers the slow-sync loop (reconnect wipe); Part B covers the missing 44'/49' txs (single-account bundling), UI creation, signing, and file-format compatibility.
- **Independence:** Part A ships value alone (faster sync for all Electrum coins). Part B depends only on today's code, not on Part A. Execute A first (smaller, de-risks the device test loop).
- **Risk:** Both touch balance-critical code. Every task is TDD with the 36-failure baseline gate. Part B's serialization task is the highest-risk — its backward-compat test (old single-root pocket loads unchanged) is mandatory before merge.
- **Types:** `getKeychains()` (B1) is used by B3 tests; `createBundledBitcoinAccount` (B4) matches the name used in the UI wiring. Purpose→AddressType mapping is fixed: 44'→LEGACY, 49'→COMPATIBLE, 84'→NATIVE_SEGWIT.
