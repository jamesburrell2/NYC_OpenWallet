# OpenWallet v1.8.5 — Zcash Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the runtime defects in the v1.8.4 Zcash SDK integration so ZEC balances, sync, restore, and sends actually work; repair dead ElectrumX servers for the Bitcoin-family coins (Dogecoin, Feathercoin, Potcoin); fail closed on the broken EVM address path; then ship as v1.8.5.

**Architecture:** The wallet bridges the Android-only `zcash-android-sdk` (Kotlin, `wallet` module) to the pure-Java `core` module via the `ZcashBackendDelegate` interface. `CoinServiceImpl` constructs `ZcashSdkBackendImpl` and injects it into `ZcashSdkWallet` accounts. The fixes stay inside this existing bridge — no new modules.

**Tech Stack:** Java 8 (core module, JUnit 4 tests), Kotlin + coroutines (wallet module), zcash-android-sdk 2.x, Gradle. Build on Windows with `.\gradlew`.

## Global Constraints

- Repo: `D:\NewYorkCoin_2026\nyc-openwallet-android`, branch `feature/nyc-coin-integration`
- All v1.8.4 work is **uncommitted** — Task 1 commits it as the baseline before any changes
- Do NOT build locally with more than the configured Gradle heap (`gradle.properties` already sets `-Xmx4096m`; host machine OOMs above this — many `hs_err_pid*.log` files exist from past crashes)
- Core module tests: `.\gradlew :core:test` (pure JVM, safe to run)
- Full app builds: `.\gradlew :wallet:assembleDebug` for compile verification; `:wallet:assembleRelease` only in Task 9
- SOC-2 (user's global CLAUDE.md): never log addresses/keys/seed material; fail closed on invalid input
- Zcash constants: ZIP-317 marginal fee 5,000 zat/action (typical shielded send = 15,000 zat); Zcash block time 75 s; Sapling activation height 419,200
- Test device: Pixel Fold, serial `37131FDHS000J5`

---

## Defect Register (from 2026-07-02 code review)

| # | Severity | Defect | Where |
|---|----------|--------|-------|
| Z1 | Critical | Adding ZEC mid-session never injects a backend — `injectZcashBackends()` only runs when `clients == null`; `ACTION_CONNECT_COIN` / `ACTION_CONNECT_ALL_COIN` skip it. User adds Zcash → no address, 0 balance, "not synced" forever until full app restart. | `CoinServiceImpl.java:230,466-502` |
| Z2 | Critical | UI never learns about ZEC updates — `ZcashSdkWallet` keeps a listener list but nothing ever fires `onNewBalance`/`onWalletChanged`; the backend caches balance/txs silently. Balance appears stuck at 0. | `ZcashSdkWallet.java`, `ZcashSdkBackendImpl.kt` |
| Z3 | Critical | Restore-from-seed shows zero balance — first init always uses `WalletInitMode.NewWallet` + latest checkpoint birthday, so historical funds are never scanned. Also the SDK DB alias (`openwallet_zec`) is not tied to the seed: restoring a *different* seed reuses the previous wallet's SDK database. | `ZcashSdkBackendImpl.kt:61-95` |
| Z4 | High | Fee hardcoded at 10,000 zat — below the ZIP-317 conventional minimum (15,000 for a typical shielded send). "Send all" underpays and the SDK proposal will reject it; balance validation is wrong. | `ZcashSdkWallet.java:477,504,521` |
| Z5 | High | No reconnect after disconnect — `disconnect()` calls `backend.stopSync()`, but re-injection is skipped because `getBackend() != null`, and nothing calls `startSync()` again. ZEC stays offline after any service restart or network change. | `CoinServiceImpl.java:301`, `ZcashSdkWallet.java:227` |
| Z6 | Medium | Secondary server (`zec-node.cakewallet.com`) is never used; a startup failure leaves the account dead with no retry and no user-visible error. | `ZcashSdkBackendImpl.kt`, `CoinServiceImpl.java:307-313` |
| Z7 | Medium | `ZcashSdkFamily.newAddress()` accepts any non-empty string — typos and wrong-coin addresses only fail deep inside the SDK proposal, after a pending tx placeholder was already created. | `ZcashSdkFamily.java:20-25` |
| Z8 | Medium | Wallet addresses logged in plaintext (`Log.d(TAG, "UA=$cachedAddress t=$cachedTAddress sapling=…")`) — privacy leak, violates SOC-2 no-PII-in-logs rule, especially bad for a privacy coin. | `ZcashSdkBackendImpl.kt:104` |
| Z9 | Info | Encrypted wallets: backend injection is skipped (seed inaccessible) with only a log line. Acceptable for now — `ConfirmAddCoinUnlockWalletDialog` collects the password for account creation — but sync remains dead on encrypted wallets. Documented as a known limitation; not fixed in this plan. | `CoinServiceImpl.java:293-297` |
| Z10 | QA | `TransactionOverview.rawId` byte order may be reversed vs. block-explorer convention — verify displayed txid against an explorer during device QA and reverse bytes in `mapTransaction` if needed. | `ZcashSdkBackendImpl.kt:161` |
| O1 | High | **Both Dogecoin ElectrumX servers are dead** (`electrum.dogecoin.network:50002`, `electrumx-doge.lbr.network:50002` — TCP timeout, probed 2026-07-02). DOGE is in `SUPPORTED_COINS`; adding it hangs on "connecting" forever. Working replacements verified: `electrum1.cipig.net:20060` and `electrum2.cipig.net:20060` (SSL, ElectrumX 1.18, protocol 1.4). | `Constants.java:115-116` |
| O2 | Medium | **Feathercoin and Potcoin servers are dead** (`ftc-cce-1.coinomi.net:5017`, `pot-cce-1.coinomi.net:5039` — TCP timeout) with no known public replacements; `ltc-testnet-cce-1.coinomi.net:15002` accepts TCP but speaks no Electrum protocol. These coins are user-addable dead ends. | `Constants.java:113,123,129`, `SUPPORTED_COINS` |
| O3 | High (latent) | **EVM/Ethereum address derivation is cryptographically wrong** — uses SHA-256 of the *compressed* pubkey instead of Keccak-256 of the uncompressed pubkey. Any ETH received at a displayed address would be at a key-less address (fund loss). Currently unreachable (see below), but nothing marks it as unsafe — one line added to `CoinID`/`SUPPORTED_COINS` would expose it. | `EvmFamilyWallet.java:64-77` |
| O4 | Info | Ethereum wiring status (answers "was ETH wired?"): `EthereumMain`, `EvmFamily`, `EvmFamilyWallet`, `EvmAddress`, `EvmTransaction`, `EvmServerClient` all exist and `ServerClients` has an EVM branch — but `EthereumMain` is **not registered in the `CoinID` enum** (neither is `SolanaMain`), not in `SUPPORTED_COINS`, and no RPC URL is configured. `EvmServerClient` has real `eth_getBalance`/`eth_sendRawTransaction` JSON-RPC but history/subscribe/broadcast are TODOs; `signTransaction`/`completeTransaction` throw. It is dead code, roughly 30% of a working integration. Full enablement (Keccak-256, RLP + EIP-1559 signing, nonce/gas management, history API) is a separate feature project, out of scope for v1.8.5. | `CoinID.java:26-43`, `EvmServerClient.java` |

**Verified working (probed 2026-07-02, `server.version`/TCP):** NYC `electrum.paywith.nyc:50002` ✅ · BTC `electrum.blockstream.info` + `electrum.bitaroo.net:50002` ✅ · LTC `electrum-ltc.bysh.me` + `electrum.ltc.xurious.com:50002` ✅ (TCP open) · DASH/RDD/DGB/VTC `*-cce-*.coinomi.net` ✅ (respond ElectrumX 1.16–1.18, protocol 1.4 — still alive despite age) · ZEC `zec.rocks:443` + `zec-node.cakewallet.com:443` ✅.

**Verified non-issues:** `broadcastTxSync`'s 120 s latch runs inside `SignAndBroadcastTask` (AsyncTask background thread) — no ANR. EVM/Solana/Cardano/Chia stub families are *not* in `Constants.SUPPORTED_COINS`, so users cannot add them today. `ZcashNoopConnection` correctly prevents ServerClients from treating the lightwalletd endpoint as ElectrumX. `NewYorkCoinMain` params are correct (BIP44 179, R-prefix 0x3C, zero fee, SegWit UI-gated behind `segwitActivated=false`).

---

### Task 1: Commit the v1.8.4 baseline

**Files:**
- No source changes — git only.

**Interfaces:**
- Consumes: nothing
- Produces: a clean baseline commit so every later task is a reviewable diff

- [ ] **Step 1: Verify only expected files are dirty**

Run: `git -C D:\NewYorkCoin_2026\nyc-openwallet-android status --short`
Expected: the 18 modified + 3 untracked paths seen in review (build.gradle, core Zcash/SegWit files, wallet Constants/service/UI files, `ZcashSdkFamily.java`, `core/.../families/zcash/`, `ZcashSdkBackendImpl.kt`). Build outputs under `*/build/` must be ignored — do not add them.

- [ ] **Step 2: Commit**

```powershell
cd D:\NewYorkCoin_2026\nyc-openwallet-android
git add build.gradle gradle.properties core/build.gradle wallet/build.gradle wallet/proguard-rules.pro core/src/main/java/com/openwallet/core wallet/src/main/java/com/openwallet/wallet
git commit -m @'
feat: Phase 2 Zcash SDK integration baseline (v1.8.4)

- ZcashSdkFamily/ZcashSdkWallet/ZcashSdkAddress/ZcashSdkTransaction in core
- ZcashBackendDelegate bridge + ZcashSdkBackendImpl (Kotlin, zcash-android-sdk)
- Wire into Wallet, ServerClients, WalletProtobufSerializer, WalletApplication,
  AddCoinsActivity, CoinServiceImpl; lightwalletd servers in Constants
- SegWit P2WPKH recognition in TransactionWatcherWallet/BitTransaction
- Build fixes: protobuf pin, R8 dontwarn rules, 4GB Gradle heap

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

- [ ] **Step 3: Verify clean tree**

Run: `git status --short`
Expected: empty (or only `*/build/` noise, which stays untracked).

---

### Task 2: ZEC address validation (fixes Z7)

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/coins/families/ZcashSdkFamily.java`
- Test: `core/src/test/java/com/openwallet/core/coins/ZcashSdkAddressValidationTest.java` (create)

**Interfaces:**
- Consumes: `CoinType.newAddress(String)` contract, `AddressMalformedException`
- Produces: `ZcashSdkFamily.newAddress(String)` that throws `AddressMalformedException` for anything not shaped like a t1/t3/zs1/u1 address; returns `ZcashSdkAddress` otherwise. Trims whitespace.

- [ ] **Step 1: Write the failing test**

```java
package com.openwallet.core.coins;

import com.openwallet.core.exceptions.AddressMalformedException;
import com.openwallet.core.wallet.AbstractAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ZcashSdkAddressValidationTest {
    private final CoinType type = ZcashMain.get();

    @Test
    public void acceptsTransparentP2PKH() throws Exception {
        AbstractAddress a = type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi");
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi", a.toString());
    }

    @Test
    public void acceptsSaplingAddress() throws Exception {
        // structurally valid: zs1 + 75 bech32 chars = 78 total
        String zs = "zs1" + repeat("q", 75);
        assertEquals(zs, type.newAddress(zs).toString());
    }

    @Test
    public void acceptsUnifiedAddress() throws Exception {
        String ua = "u1" + repeat("q", 100);
        assertEquals(ua, type.newAddress(ua).toString());
    }

    @Test
    public void trimsWhitespace() throws Exception {
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi",
                type.newAddress("  t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi\n").toString());
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsEmpty() throws Exception { type.newAddress("   "); }

    @Test(expected = AddressMalformedException.class)
    public void rejectsBitcoinAddress() throws Exception {
        type.newAddress("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa");
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsShortTransparent() throws Exception { type.newAddress("t1abc"); }

    @Test(expected = AddressMalformedException.class)
    public void rejectsBadBase58InTransparent() throws Exception {
        // 'l' and 'O' are not in the Base58 alphabet; length is a valid 35
        type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBl".replace('B', 'O'));
    }

    @Test(expected = AddressMalformedException.class)
    public void rejectsUppercaseInBech32() throws Exception {
        type.newAddress("zs1" + repeat("Q", 75));
    }

    private static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :core:test --tests "com.openwallet.core.coins.ZcashSdkAddressValidationTest"`
Expected: FAIL — `rejectsBitcoinAddress`, `rejectsShortTransparent`, etc. fail because current `newAddress` accepts any non-empty string.

- [ ] **Step 3: Implement validation**

Replace `newAddress` in `ZcashSdkFamily.java`:

```java
    private static final String BASE58 = "[1-9A-HJ-NP-Za-km-z]+";
    private static final String BECH32 = "[02-9ac-hj-np-z]+";

    @Override
    public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
        if (addressStr == null) {
            throw new AddressMalformedException("ZEC address must not be empty");
        }
        String addr = addressStr.trim();
        if (addr.isEmpty()) {
            throw new AddressMalformedException("ZEC address must not be empty");
        }
        if (addr.startsWith("t1") || addr.startsWith("t3")) {
            if (addr.length() != 35 || !addr.matches(BASE58)) {
                throw new AddressMalformedException("Invalid Zcash transparent address");
            }
        } else if (addr.startsWith("zs1")) {
            if (addr.length() != 78 || !addr.substring(3).matches(BECH32)) {
                throw new AddressMalformedException("Invalid Zcash Sapling address");
            }
        } else if (addr.startsWith("u1")) {
            if (addr.length() < 40 || !addr.substring(2).matches(BECH32)) {
                throw new AddressMalformedException("Invalid Zcash Unified Address");
            }
        } else {
            throw new AddressMalformedException(
                    "Unrecognized Zcash address: must start with t1, t3, zs1, or u1");
        }
        return new ZcashSdkAddress(this, addr);
    }
```

(Checksum validation is intentionally shallow — the SDK fully validates at proposal time; this catches wrong-coin pastes and typos early. Full Base58Check for t-addresses already exists in `ZcashAddress` but its 2-byte-prefix path is only wired for the legacy `ZcashFamily`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :core:test --tests "com.openwallet.core.coins.ZcashSdkAddressValidationTest"`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/coins/families/ZcashSdkFamily.java core/src/test/java/com/openwallet/core/coins/ZcashSdkAddressValidationTest.java
git commit -m "fix(zec): validate address shape in ZcashSdkFamily.newAddress`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: ZIP-317 fee correction (fixes Z4)

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashSdkWallet.java` (lines 471-530)
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/ZcashSdkWalletFeeTest.java` (create)
- Test helper: `core/src/test/java/com/openwallet/core/wallet/families/zcash/FakeZcashBackend.java` (create — reused by Task 4)

**Interfaces:**
- Consumes: `ZcashBackendDelegate` (unchanged in this task)
- Produces: `public static final long ZIP317_STANDARD_FEE = 15_000L;` and `public static final long SEND_ALL_FEE_MARGIN = 20_000L;` on `ZcashSdkWallet`. All internal `10_000L` fee references replaced. Task 4's test reuses `FakeZcashBackend`.

- [ ] **Step 1: Write the fake backend (test fixture)**

```java
package com.openwallet.core.wallet.families.zcash;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/** Minimal scriptable ZcashBackendDelegate for core-module unit tests. */
public class FakeZcashBackend implements ZcashBackendDelegate {
    public long balanceZatoshi = 0L;
    public boolean connected = true;
    public List<ZcashSdkTransaction> transactions = new ArrayList<>();
    public String lastRecipient;
    public long lastZatoshi;

    @Override public void startSync() { }
    @Override public void stopSync() { }
    @Override public String getReceiveAddress() { return "u1" + fill(100); }
    @Override public String getTransparentAddress() { return "t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"; }
    @Nullable @Override public String getSaplingAddress() { return "zs1" + fill(75); }
    @Override public long getBalanceZatoshi() { return balanceZatoshi; }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isLoading() { return false; }
    @Override public int getSyncProgressPercent() { return 100; }
    @Override public List<ZcashSdkTransaction> getTransactions() { return transactions; }

    @Override
    public void sendTo(String recipient, long zatoshi, @Nullable String memo, SendCallback cb) {
        lastRecipient = recipient;
        lastZatoshi = zatoshi;
        cb.onSuccess(fill(64));
    }

    private static String fill(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('q');
        return b.toString();
    }
}
```

- [ ] **Step 2: Write the failing fee test**

```java
package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.wallet.SendRequest;
import com.openwallet.core.wallet.WalletAccount.WalletAccountException;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ZcashSdkWalletFeeTest {
    private final CoinType type = ZcashMain.get();
    private ZcashSdkWallet wallet;
    private FakeZcashBackend backend;

    @Before
    public void setUp() {
        wallet = new ZcashSdkWallet(type, "zcash.main:0");
        backend = new FakeZcashBackend();
        wallet.setBackend(backend);
    }

    @Test
    public void feeConstantsMatchZip317() {
        assertEquals(15_000L, ZcashSdkWallet.ZIP317_STANDARD_FEE);
        assertEquals(20_000L, ZcashSdkWallet.SEND_ALL_FEE_MARGIN);
    }

    @Test
    public void sendAllSubtractsMargin() throws Exception {
        backend.balanceZatoshi = 1_000_000L;
        SendRequest req = wallet.getEmptyWalletRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"));
        ZcashSdkTransaction tx = (ZcashSdkTransaction) req.tx;
        assertEquals(1_000_000L - 20_000L, tx.getValueZatoshi());
    }

    @Test(expected = WalletAccountException.class)
    public void sendAllRejectsDustBalance() throws Exception {
        backend.balanceZatoshi = 20_000L; // == margin, nothing left to send
        wallet.getEmptyWalletRequest(type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"));
    }

    @Test(expected = WalletAccountException.class)
    public void completeRejectsAmountPlusFeeOverBalance() throws Exception {
        backend.balanceZatoshi = 100_000L;
        // 90_000 + 15_000 fee > 100_000 must fail
        SendRequest req = wallet.getSendToRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"), type.value(90_000L));
        wallet.completeTransaction(req);
    }

    @Test
    public void completeAcceptsAffordableSend() throws Exception {
        backend.balanceZatoshi = 100_000L;
        SendRequest req = wallet.getSendToRequest(
                type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi"), type.value(80_000L));
        wallet.completeTransaction(req); // must not throw
    }
}
```

Note: `wallet.setBackend(backend)` calls `backend.startSync()` (no-op in the fake). In Task 4 `setBackend` also registers an update listener — the fake gets that method added there.

- [ ] **Step 3: Run tests to verify failure**

Run: `.\gradlew :core:test --tests "com.openwallet.core.wallet.families.zcash.ZcashSdkWalletFeeTest"`
Expected: FAIL — constants don't exist (compile error), then after adding constants, `sendAllSubtractsMargin` fails (10_000 vs 20_000).

- [ ] **Step 4: Implement**

In `ZcashSdkWallet.java`, add near the top of the class:

```java
    /** ZIP-317 conventional fee: 5,000 zat marginal fee × 3 logical actions (typical shielded send). */
    public static final long ZIP317_STANDARD_FEE = 15_000L;
    /** Conservative margin for "send all" — covers cross-pool sends (up to 4 actions). */
    public static final long SEND_ALL_FEE_MARGIN = 20_000L;
```

Then:
- `getEmptyWalletRequest`: replace `long fee = 10_000L;` with `long fee = SEND_ALL_FEE_MARGIN;`
- `getSendToRequest`: replace the placeholder fee argument `10_000L` with `ZIP317_STANDARD_FEE`
- `completeTransaction`: replace `+ 10_000L` with `+ ZIP317_STANDARD_FEE`

Known limitation (document in the javadoc of `SEND_ALL_FEE_MARGIN`): the SDK computes the exact ZIP-317 fee at proposal time; send-all may leave up to 5,000 zat behind. Exact-fee send-all needs a `proposeTransfer` round-trip and is deferred.

- [ ] **Step 5: Run tests to verify pass**

Run: `.\gradlew :core:test --tests "com.openwallet.core.wallet.families.zcash.ZcashSdkWalletFeeTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashSdkWallet.java core/src/test/java/com/openwallet/core/wallet/families/zcash/
git commit -m "fix(zec): raise fees to ZIP-317 levels (15k standard, 20k send-all margin)`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Backend→UI event bridge (fixes Z2)

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashBackendDelegate.java`
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashSdkWallet.java` (setBackend + new private method)
- Modify: `core/src/test/java/com/openwallet/core/wallet/families/zcash/FakeZcashBackend.java`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt`
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/ZcashSdkWalletEventsTest.java` (create)

**Interfaces:**
- Consumes: `WalletAccountEventListener` (`onNewBalance(Value)`, `onWalletChanged(WalletAccount)`), `ZcashSdkWallet.setBackend(ZcashBackendDelegate)`
- Produces: on `ZcashBackendDelegate` — nested `interface UpdateListener { void onBackendUpdated(); }` and method `void setUpdateListener(@Nullable UpdateListener listener);`. `ZcashSdkWallet.setBackend` registers itself and relays every update to its `WalletAccountEventListener`s.

- [ ] **Step 1: Write the failing test**

```java
package com.openwallet.core.wallet.families.zcash;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.Value;
import com.openwallet.core.coins.ZcashMain;
import com.openwallet.core.wallet.AbstractTransaction;
import com.openwallet.core.wallet.WalletAccount;
import com.openwallet.core.wallet.WalletAccountEventListener;
import com.openwallet.core.wallet.WalletConnectivityStatus;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;

public class ZcashSdkWalletEventsTest {
    private final CoinType type = ZcashMain.get();

    @Test
    public void backendUpdateFiresBalanceAndWalletChanged() {
        ZcashSdkWallet wallet = new ZcashSdkWallet(type, "zcash.main:0");
        FakeZcashBackend backend = new FakeZcashBackend();
        wallet.setBackend(backend);

        final AtomicReference<Value> gotBalance = new AtomicReference<>();
        final AtomicInteger walletChangedCount = new AtomicInteger();
        wallet.addEventListener(new WalletAccountEventListener() {
            @Override public void onNewBalance(Value newBalance) { gotBalance.set(newBalance); }
            @Override public void onWalletChanged(WalletAccount pocket) { walletChangedCount.incrementAndGet(); }
            @Override public void onNewBlock(WalletAccount pocket) { }
            @Override public void onTransactionConfidenceChanged(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastFailure(WalletAccount p, AbstractTransaction t) { }
            @Override public void onTransactionBroadcastSuccess(WalletAccount p, AbstractTransaction t) { }
            @Override public void onConnectivityStatus(WalletConnectivityStatus status) { }
        }, Runnable::run);

        backend.balanceZatoshi = 123_456L;
        backend.fireUpdate();

        assertEquals(type.value(123_456L), gotBalance.get());
        assertEquals(1, walletChangedCount.get());
    }
}
```

- [ ] **Step 2: Extend the fake**

Add to `FakeZcashBackend`:

```java
    @Nullable private UpdateListener updateListener;

    @Override
    public void setUpdateListener(@Nullable UpdateListener listener) {
        this.updateListener = listener;
    }

    /** Test hook: simulate the SDK pushing new data. */
    public void fireUpdate() {
        if (updateListener != null) updateListener.onBackendUpdated();
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew :core:test --tests "com.openwallet.core.wallet.families.zcash.ZcashSdkWalletEventsTest"`
Expected: FAIL — `UpdateListener` doesn't exist yet (compile error is the failure signal here).

- [ ] **Step 4: Implement**

`ZcashBackendDelegate.java` — add before `SendCallback`:

```java
    // ---- Update notifications ------------------------------------------------

    /** Fired by the backend whenever balance, transactions, or connection state change. */
    interface UpdateListener {
        void onBackendUpdated();
    }

    /**
     * Registers the single listener notified on backend data changes.
     * Pass null to clear. Called by ZcashSdkWallet.setBackend().
     */
    void setUpdateListener(@Nullable UpdateListener listener);
```

`ZcashSdkWallet.java` — replace `setBackend` and add the relay:

```java
    public void setBackend(ZcashBackendDelegate backend) {
        this.backend = backend;
        backend.setUpdateListener(new ZcashBackendDelegate.UpdateListener() {
            @Override
            public void onBackendUpdated() {
                notifyBackendUpdated();
            }
        });
        backend.startSync();
    }

    private void notifyBackendUpdated() {
        final Value newBalance = getBalance();
        for (final ListenerRegistration reg : listeners) {
            reg.executor.execute(new Runnable() {
                @Override
                public void run() {
                    reg.listener.onNewBalance(newBalance);
                    reg.listener.onWalletChanged(ZcashSdkWallet.this);
                }
            });
        }
        if (wallet != null) wallet.saveLater();
    }
```

`ZcashSdkBackendImpl.kt` — add the field + override, and call `notifyUpdated()` from every collector:

```kotlin
    @Volatile private var updateListener: ZcashBackendDelegate.UpdateListener? = null

    override fun setUpdateListener(listener: ZcashBackendDelegate.UpdateListener?) {
        updateListener = listener
    }

    private fun notifyUpdated() {
        runCatching { updateListener?.onBackendUpdated() }
    }
```

Insert `notifyUpdated()`:
- in the `walletBalances` collector, after `loading = false`
- in the `transactions` collector, after `cachedTransactions = ...`
- in the `progress` collector, only when the integer percent actually changed:

```kotlin
                launch {
                    sync.progress.collectLatest { pct ->
                        val newPct = (pct.decimal * 100f).toInt().coerceIn(0, 100)
                        if (newPct != syncProgressPercent) {
                            syncProgressPercent = newPct
                            notifyUpdated()
                        }
                    }
                }
```
- in the `catch` block of `startSync` after `connected = false` (so the UI leaves the spinner)

- [ ] **Step 5: Run core tests, then compile the app**

Run: `.\gradlew :core:test --tests "com.openwallet.core.wallet.families.zcash.*"`
Expected: PASS (events + fee tests).
Run: `.\gradlew :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL (verifies the Kotlin side implements the new interface method).

- [ ] **Step 6: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/wallet/families/zcash/ wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt core/src/test/java/com/openwallet/core/wallet/families/zcash/
git commit -m "fix(zec): notify UI listeners on backend balance/tx/progress updates`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Backend injection on coin-add and reconnect (fixes Z1, Z5)

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java` (lines ~224-235, ~292-320, ~466-502)

**Interfaces:**
- Consumes: `injectZcashBackends(Wallet)` (existing private method), `ZcashBackendDelegate.startSync()` (documented idempotent/restartable)
- Produces: `injectZcashBackends` is now safe to call on every connect path and restarts stopped backends.

No JVM-testable seam here (Android Service); verification is compile + device QA in Task 10.

- [ ] **Step 1: Make injection restart existing backends**

In `injectZcashBackends`, replace:

```java
            if (zecWallet.getBackend() != null) continue; // already injected
```

with:

```java
            if (zecWallet.getBackend() != null) {
                // Already injected — restart sync if it was stopped (service restart,
                // network change). startSync() is a no-op while already running.
                zecWallet.getBackend().startSync();
                continue;
            }
```

- [ ] **Step 2: Call injection from every connect path**

In the `check()` method's network-changed branch (line ~232), after `clients.resetConnections();` add:

```java
                injectZcashBackends(wallet);
```

In the `ACTION_CONNECT_COIN` handler (line ~477), after `if (clients != null) clients.startAsync(account);` add:

```java
                        injectZcashBackends(wallet);
```

In the `ACTION_CONNECT_ALL_COIN` handler (line ~495-499), after the `for` loop that calls `clients.startAsync(account)` add:

```java
                    injectZcashBackends(wallet);
```

- [ ] **Step 3: Compile**

Run: `.\gradlew :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java
git commit -m "fix(zec): inject/restart ZEC backend on coin add, reconnect, and network change`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Seed identity + restore birthday (fixes Z3)

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt`
- Modify: `core/src/main/java/com/openwallet/core/wallet/Wallet.java` (one accessor)
- Modify: `wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java` (constructor call site)

**Interfaces:**
- Consumes: bitcoinj `DeterministicKey.getCreationTimeSeconds()` on the wallet master key; SDK `Synchronizer.erase(...)`, `WalletInitMode.RestoreWallet`, `ZcashNetwork.Mainnet.saplingActivationHeight`
- Produces: `Wallet.getSeedCreationTimeSeconds(): long` (0 if unknown); `ZcashSdkBackendImpl` constructor gains `seedCreationTimeSeconds: Long = 0L` as its last parameter.

- [ ] **Step 1: Add the Wallet accessor**

In `Wallet.java`, next to `getSeedBytes()` (mirror its locking style exactly — if `getSeedBytes` acquires a lock, do the same):

```java
    /**
     * Unix-time (seconds) when the master key was created; 0 if unknown.
     * Used by the ZEC backend to estimate a restore birthday height.
     */
    public long getSeedCreationTimeSeconds() {
        if (masterKey == null) return 0L;
        return masterKey.getCreationTimeSeconds();
    }
```

- [ ] **Step 2: Add seed fingerprint + erase-on-mismatch + restore birthday to the backend**

In `ZcashSdkBackendImpl.kt`:

Constructor and companion additions:

```kotlin
class ZcashSdkBackendImpl(
    private val context: Context,
    private val seedBytes: ByteArray,
    private val primaryHost: String = "zec.rocks",
    private val primaryPort: Int = 443,
    private val seedCreationTimeSeconds: Long = 0L,
) : ZcashBackendDelegate {
```

```kotlin
        private const val KEY_SEED_FP = "seed_fingerprint"
        private const val ZCASH_BLOCK_TIME_SECONDS = 75L
```

New private helpers:

```kotlin
    private fun seedFingerprint(): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(seedBytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun estimateBirthday(isRestore: Boolean): BlockHeight? {
        val checkpoint = runCatching {
            BlockHeight.ofLatestCheckpoint(context, ZcashNetwork.Mainnet)
        }.getOrNull() ?: return null
        if (!isRestore) return checkpoint
        val ageSeconds = System.currentTimeMillis() / 1000L - seedCreationTimeSeconds
        if (ageSeconds <= 0) return checkpoint
        val blocksBack = ageSeconds / ZCASH_BLOCK_TIME_SECONDS
        val target = checkpoint.value - blocksBack
        val floor = ZcashNetwork.Mainnet.saplingActivationHeight.value
        return BlockHeight.new(maxOf(target, floor))
    }
```

In `startSync()`, replace the block from `val alreadyInitialized = ...` through the `birthday` declaration with:

```kotlin
                // If the app's seed changed (wallet restored/recreated), the SDK database
                // belongs to the old seed — erase it before initializing.
                val fp = seedFingerprint()
                val storedFp = prefs().getString(KEY_SEED_FP, null)
                if (storedFp != null && storedFp != fp) {
                    Log.i(TAG, "Seed changed since last init; erasing ZEC SDK database")
                    runCatching {
                        Synchronizer.erase(appContext = context,
                            network = ZcashNetwork.Mainnet, alias = SDK_ALIAS)
                    }
                    prefs().edit().clear().apply()
                }

                val alreadyInitialized = isWalletInitialized()
                // A seed older than a day that the SDK has never seen is a restore,
                // not a brand-new wallet — scan history from an estimated birthday.
                val isRestore = seedCreationTimeSeconds > 0L &&
                        (System.currentTimeMillis() / 1000L - seedCreationTimeSeconds) > 86_400L
                val initMode = when {
                    alreadyInitialized -> WalletInitMode.ExistingWallet
                    isRestore -> WalletInitMode.RestoreWallet
                    else -> WalletInitMode.NewWallet
                }
                Log.d(TAG, "Init mode=$initMode")
                val birthday: BlockHeight? =
                    if (!alreadyInitialized) estimateBirthday(isRestore)
                        .also { Log.d(TAG, "Birthday=$it") }
                    else null
```

And extend `markWalletInitialized` to record the fingerprint:

```kotlin
    private fun markWalletInitialized() =
        prefs().edit()
            .putBoolean(KEY_INITIALIZED, true)
            .putString(KEY_SEED_FP, seedFingerprint())
            .apply()
```

**Compile note:** verify the exact SDK symbols against the pinned SDK version during implementation — `WalletInitMode.RestoreWallet` (2.x naming), `Synchronizer.erase(appContext, network, alias)` (suspend — we are inside a coroutine), `BlockHeight.new(Long)`, and `ZcashNetwork.saplingActivationHeight`. If `BlockHeight.new` requires a network parameter in the pinned version, use that overload. These are the only four symbols at risk of signature drift.

- [ ] **Step 3: Pass the creation time from the service**

In `CoinServiceImpl.injectZcashBackends`, change the construction to:

```java
            ZcashSdkBackendImpl backend = new ZcashSdkBackendImpl(
                    this, seedBytes, host, port, wallet.getSeedCreationTimeSeconds());
```

- [ ] **Step 4: Compile + run core tests**

Run: `.\gradlew :core:test :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL, all core tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt core/src/main/java/com/openwallet/core/wallet/Wallet.java wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java
git commit -m "fix(zec): erase SDK db on seed change; use RestoreWallet mode with estimated birthday`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Fallback server + error surfacing (fixes Z6)

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt`
- Modify: `wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java` (pass all servers)
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashBackendDelegate.java` (one method)
- Modify: `core/src/test/java/com/openwallet/core/wallet/families/zcash/FakeZcashBackend.java` (implement it)

**Interfaces:**
- Consumes: `Constants.DEFAULT_COINS_SERVERS` (already lists `zec.rocks:443` then `zec-node.cakewallet.com:443`)
- Produces: `ZcashBackendDelegate.getLastErrorMessage(): @Nullable String` (null when healthy); `ZcashSdkBackendImpl` constructor takes `servers: List<HostPort>` instead of a single host/port.

- [ ] **Step 1: Delegate + fake**

`ZcashBackendDelegate.java`, in the Status section:

```java
    /**
     * Human-readable description of the last fatal sync error, or null if healthy.
     * Cleared on the next successful connection.
     */
    @Nullable String getLastErrorMessage();
```

`FakeZcashBackend`: add `public String lastError;` and `@Nullable @Override public String getLastErrorMessage() { return lastError; }`.

- [ ] **Step 2: Rework the backend constructor and startup loop**

`ZcashSdkBackendImpl.kt` — replace the single host/port params (keep `seedCreationTimeSeconds` last):

```kotlin
class ZcashSdkBackendImpl(
    private val context: Context,
    private val seedBytes: ByteArray,
    private val servers: List<HostPort>,
    private val seedCreationTimeSeconds: Long = 0L,
) : ZcashBackendDelegate {

    data class HostPort(val host: String, val port: Int)
```

Add state + accessor:

```kotlin
    @Volatile private var lastError: String? = null
    override fun getLastErrorMessage(): String? = lastError
```

Restructure `startSync()` so only synchronizer creation is retried per server; collectors attach once after success:

```kotlin
    override fun startSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            loading = true
            var sync: CloseableSynchronizer? = null
            var lastException: Exception? = null
            for (server in servers.ifEmpty { listOf(HostPort("zec.rocks", 443)) }) {
                try {
                    sync = openSynchronizer(server)
                    break
                } catch (e: Exception) {
                    lastException = e
                    Log.e(TAG, "ZEC init failed on ${server.host}: ${e.javaClass.simpleName}")
                }
            }
            if (sync == null) {
                loading = false
                connected = false
                lastError = lastException?.message ?: "Unable to reach any Zcash server"
                notifyUpdated()
                return@launch
            }
            lastError = null
            synchronizer = sync
            attachCollectors(sync)
        }
    }
```

Where `openSynchronizer(server: HostPort): CloseableSynchronizer` contains the existing seed-fingerprint check, init-mode/birthday logic, and `Synchronizer.new(...)` call (using `server.host`/`server.port`), plus `markWalletInitialized()` and the address caching; and `attachCollectors(sync)` contains the three existing `launch { ... collectLatest ... }` blocks. This is a mechanical extraction — keep the code from Tasks 4 and 6 intact inside them.

- [ ] **Step 3: Update the service call site**

In `CoinServiceImpl.injectZcashBackends`, replace the single host/port selection with the full list:

```java
            List<ZcashSdkBackendImpl.HostPort> servers = new ArrayList<>();
            for (CoinAddress addr : Constants.DEFAULT_COINS_SERVERS) {
                if (addr.getType().equals(type)) {
                    for (ServerAddress sa : addr.getAddresses()) {
                        servers.add(new ZcashSdkBackendImpl.HostPort(sa.getHost(), sa.getPort()));
                    }
                    break;
                }
            }
            ZcashSdkBackendImpl backend = new ZcashSdkBackendImpl(
                    this, seedBytes, servers, wallet.getSeedCreationTimeSeconds());
```

(Add `import java.util.ArrayList;` and `import java.util.List;` if missing.)

- [ ] **Step 4: Compile + tests**

Run: `.\gradlew :core:test :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL, core tests PASS.

- [ ] **Step 5: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/wallet/families/zcash/ wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java core/src/test/java/com/openwallet/core/wallet/families/zcash/FakeZcashBackend.java
git commit -m "fix(zec): try fallback lightwalletd servers; expose last sync error`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Redact address logging (fixes Z8, SOC-2)

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt`

**Interfaces:** none — log strings only.

- [ ] **Step 1: Replace address-leaking log lines**

In `openSynchronizer` (formerly `startSync`), replace:

```kotlin
Log.d(TAG, "UA=$cachedAddress t=$cachedTAddress sapling=$cachedSaplingAddress")
```

with:

```kotlin
Log.d(TAG, "Addresses derived: ua=${cachedAddress != null} t=${cachedTAddress != null} sapling=${cachedSaplingAddress != null}")
```

Audit the whole file for other leaks: exception messages from the SDK can embed the recipient address — in `sendTo`'s catch block, replace `Log.e(TAG, "ZEC send failed: ${e.message}", e)` with:

```kotlin
Log.e(TAG, "ZEC send failed: ${e.javaClass.simpleName}")
```

(The full exception still reaches the UI via `callback.onError(e)`; it just isn't written to logcat.) Keep txid logging — txids are public chain data.

- [ ] **Step 2: Compile and grep-verify**

Run: `.\gradlew :wallet:assembleDebug`
Then verify no `Log.*` call interpolates `cachedAddress`, `cachedTAddress`, `cachedSaplingAddress`, `recipient`, or `seedBytes`:
`Select-String -Path wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt -Pattern 'Log\..*(cachedAddress|cachedTAddress|cachedSapling|recipient|seed)'`
Expected: no matches.

- [ ] **Step 3: Commit**

```powershell
git add wallet/src/main/java/com/openwallet/wallet/ZcashSdkBackendImpl.kt
git commit -m "fix(zec): stop logging wallet addresses and send details (SOC-2)`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Bitcoin-family server refresh (fixes O1, O2)

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/Constants.java` (lines 113-130, 181-196)

**Interfaces:**
- Consumes: `ServerAddress(host, port, useSsl)` (three-arg SSL constructor already used for NYC/BTC/ZEC)
- Produces: DOGE pointed at live servers; dead-end coins removed from the Add Coins list.

- [ ] **Step 1: Replace the dead Dogecoin servers**

In `DEFAULT_COINS_SERVERS`, replace:

```java
            new CoinAddress(DogecoinMain.get(),     new ServerAddress("electrum.dogecoin.network", 50002, true),
                                                    new ServerAddress("electrumx-doge.lbr.network", 50002, true)),
```

with:

```java
            new CoinAddress(DogecoinMain.get(),     new ServerAddress("electrum1.cipig.net", 20060, true),
                                                    new ServerAddress("electrum2.cipig.net", 20060, true)),
```

(Both verified live 2026-07-02: ElectrumX 1.18, protocol 1.4, SSL. During Task 12 QA, confirm the served chain is Dogecoin by checking a known address balance — if wrong-chain, the app will show a checkpoint/header mismatch immediately.)

- [ ] **Step 2: Remove dead-end coins from the Add Coins list**

In `SUPPORTED_COINS`, delete the two lines:

```java
            FeathercoinMain.get(),
            PotcoinMain.get(),
```

Leave their `CoinAddress` entries, icons, and coin definitions in place — existing users who already added FTC/POT keep their accounts (they will still show "connecting"; funds remain recoverable via seed in any other wallet). Removing the `SUPPORTED_COINS` entries only stops *new* additions of coins with no reachable servers.

- [ ] **Step 3: Compile**

Run: `.\gradlew :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add wallet/src/main/java/com/openwallet/wallet/Constants.java
git commit -m "fix: replace dead Dogecoin ElectrumX servers; drop server-less FTC/POT from Add Coins`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Fail closed on broken EVM address derivation (fixes O3)

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/evm/EvmFamilyWallet.java` (lines 64-77)
- Test: `core/src/test/java/com/openwallet/core/wallet/families/evm/EvmFamilyWalletTest.java` (create)

**Interfaces:**
- Consumes: existing `EvmFamilyWallet(CoinType, String, DeterministicKey)` constructor
- Produces: that constructor throws `UnsupportedOperationException` instead of deriving an unspendable address. (SOC-2 processing integrity: fail closed rather than emit a bad result.) The `(CoinType, String)` and `(CoinType, String, String addressStr)` constructors are untouched.

Rationale: the current derivation (SHA-256 of the compressed pubkey) does not produce a real Ethereum address — Ethereum requires Keccak-256 of the 64-byte uncompressed pubkey. ETH is currently unreachable (not in `CoinID`), but this latent path is one registry line away from displaying receive addresses whose funds could never be spent. Until a correct Keccak implementation lands (separate feature), the wrong path must refuse to run.

- [ ] **Step 1: Write the failing test**

```java
package com.openwallet.core.wallet.families.evm;

import com.openwallet.core.coins.CoinType;
import com.openwallet.core.coins.EthereumMain;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.junit.Test;

public class EvmFamilyWalletTest {
    @Test(expected = UnsupportedOperationException.class)
    public void keyBasedConstructorFailsClosedUntilKeccakExists() {
        CoinType type = EthereumMain.get();
        DeterministicKey key = HDKeyDerivation.createMasterPrivateKey(new byte[32]);
        new EvmFamilyWallet(type, "ethereum.main:0", key);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :core:test --tests "com.openwallet.core.wallet.families.evm.EvmFamilyWalletTest"`
Expected: FAIL — constructor currently derives a (wrong) address without throwing.

- [ ] **Step 3: Implement**

Replace the constructor body:

```java
    public EvmFamilyWallet(CoinType coinType, String id, DeterministicKey rootKey) {
        super(coinType, id);
        // FAIL CLOSED: Ethereum addresses require Keccak-256 of the uncompressed public
        // key. The previous SHA-256-based derivation produced addresses with no spendable
        // key — funds sent to them would be lost. Refuse to construct until a correct
        // Keccak-256 derivation is implemented.
        throw new UnsupportedOperationException(
                "EVM key-based address derivation not implemented (requires Keccak-256)");
    }
```

Remove the now-unused `pubKey`/`hash`/`addr` locals; keep the `balance` field initialization out (unreachable). If `Sha256Hash` or `AddressMalformedException` imports become unused, remove them.

- [ ] **Step 4: Run tests to verify pass**

Run: `.\gradlew :core:test`
Expected: PASS — the new test plus all Zcash tests. If `Wallet.createAndAddAccount`'s EvmFamily branch now fails to compile or a test elsewhere constructs `EvmFamilyWallet` with a key, the exception propagates as an add-coin error — acceptable, since ETH is not in `CoinID` and cannot be added.

- [ ] **Step 5: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/wallet/families/evm/EvmFamilyWallet.java core/src/test/java/com/openwallet/core/wallet/families/evm/EvmFamilyWalletTest.java
git commit -m "fix(evm): fail closed on incorrect SHA-256 address derivation (needs Keccak-256)`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: Version bump + release build

**Files:**
- Modify: `wallet/build.gradle:18-19`

- [ ] **Step 1: Bump version**

```gradle
        versionCode 63
        versionName "v1.8.5"
```

- [ ] **Step 2: Full test + release build**

Run: `.\gradlew :core:test`
Expected: all tests PASS.
Run: `.\gradlew :wallet:assembleRelease`
Expected: BUILD SUCCESSFUL, signed `wallet-release.apk` produced (signing config already works per v1.8.4 build).

- [ ] **Step 3: Commit**

```powershell
git add wallet/build.gradle
git commit -m "release: v1.8.5 (versionCode 63) - Zcash sync, restore, fee, server, and event fixes`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: Device runtime validation (manual QA — fixes Z10 verification)

**Files:** none — QA on Pixel Fold `37131FDHS000J5`.

- [ ] **Step 1: Install**

```powershell
adb -s 37131FDHS000J5 install -r wallet\build\outputs\apk\release\wallet-release.apk
```

- [ ] **Step 2: Fresh-add flow (verifies Z1, Z2)**
  - Open app → Add Coins → Zcash → add. **Without restarting the app**, confirm a receive address appears (u1… or t1…) and the balance card leaves the loading state.
  - Watch logs: `adb -s 37131FDHS000J5 logcat -s ZcashSdkBackend` — expect init-mode + birthday lines, no addresses.

- [ ] **Step 3: Live-update flow (verifies Z2)**
  - Send a small ZEC amount TO the wallet from an external wallet. The balance must update on-screen without navigating away or restarting.

- [ ] **Step 4: Restore flow (verifies Z3)**
  - Uninstall the app (or clear data), restore the wallet from its seed phrase, re-add Zcash. The previously received balance must reappear after sync (may take minutes — progress percent should climb).

- [ ] **Step 5: Send flow (verifies Z4, Z7, Z10)**
  - Send a small amount to an external t-address and to a zs1 address. Both must succeed.
  - Paste a BTC address into the send field — must be rejected immediately as malformed.
  - Attempt "empty wallet" — must propose balance − 20,000 zat and succeed.
  - Compare the displayed txid with the one on a block explorer (e.g. blockchair.com/zcash). **If the hex is byte-reversed**, fix `mapTransaction` in `ZcashSdkBackendImpl.kt` to reverse the byte array before hex-encoding, rebuild, and re-verify.

- [ ] **Step 6: Reconnect flow (verifies Z5, Z6)**
  - Toggle airplane mode on → wait 30 s → off. ZEC must resume syncing (progress/balance updates) without an app restart.

- [ ] **Step 7: Other-coin smoke test (verifies O1, O2)**
  - Add Dogecoin → must reach "connected" and show a D-prefix receive address within ~30 s (confirms cipig serves the Dogecoin chain; a wrong-chain server fails with a header/checkpoint mismatch in logcat).
  - Confirm Feathercoin and Potcoin no longer appear in Add Coins.
  - Spot-check Bitcoin and Litecoin: add each, confirm they connect and display an address (servers verified live 2026-07-02, this confirms end-to-end protocol compat).

- [ ] **Step 8: Ship**
  - Push the branch: `git push origin feature/nyc-coin-integration`
  - Then follow the existing Play Store checklist in `D:\NewYorkCoin_2026\To_Do.md` §4 (AAB via `:wallet:bundleRelease`, release notes, internal track).

---

## Known limitations accepted in v1.8.5

- **Encrypted wallets (Z9):** ZEC sync stays inactive while the wallet is encrypted (seed inaccessible to the backend). Fix requires deriving/caching the ZEC spending key at unlock time — deferred.
- **Send-all dust:** up to 5,000 zat may remain after "empty wallet" (fee margin vs. exact ZIP-317 fee).
- **Sent tx history gap:** after a successful send the placeholder is removed and the real tx appears only once the synchronizer sees it (typically < 2 minutes).
- **Orchard/UA phase 3** and **memo UI** remain out of scope per the PRD.
- **Ethereum stays disabled (O4):** the EVM scaffolding (~30% complete, wrong address crypto, no signing) is left out of `CoinID`/`SUPPORTED_COINS` and now fails closed. Enabling ETH is a separate feature: Keccak-256 derivation, RLP + EIP-1559 signing, nonce/gas management, an RPC provider (Infura/Alchemy key), and a history API. Same applies to Solana/Cardano/Chia stubs.
- **FTC/POT accounts of existing users** remain visible but permanently "connecting" — no public ElectrumX servers exist for these chains; funds are recoverable via seed elsewhere.
