# NewYorkCoin height-gated SegWit reveal — Design

**Date:** 2026-07-08
**Status:** Approved (design), pending implementation plan
**Author:** brainstormed with James Burrell

## Problem

NewYorkCoin's SegWit soft fork is a **buried deployment that activates at block height 13,500,000** (verified on the live v2.1.0 node: `getblockchaininfo` → `softforks.segwit.type = "buried"`, `active = false`, `height = 13500000`; current tip ≈ 12,828,400). Until then, and until the peer/miner network (today dominated by `/NewYorkCoin:1.14.3/` and `/NewYorkCoin:1.3.1.20/`) enforces it, SegWit outputs are effectively anyone-can-spend to old nodes. **Legacy P2PKH ("R…", version 60) is the only NYC address type that is safe to use today.**

However, the wallet **already exposes SegWit for NYC prematurely**. `NewYorkCoinMain` declares:

```java
supportedAddressTypes = {LEGACY, COMPATIBLE, NATIVE_SEGWIT}
segwitActivated = false   // "flip to true once SegWit activates on mainnet"
```

but the address-generation path (`WalletPocketHD.getActiveAddresses`) and the receive UI (`AddressRequestFragment`) gate only on `getSupportedAddressTypes()` — **neither consults `isSegwitActivated()`**. Only the add-coin dialog checks the flag. As a result the wallet generates, watches, and can hand out `nyc1q…` (and P2SH-SegWit `3…`-style) NYC addresses today. Field evidence: a live logcat capture showed ~302 distinct `nyc1q…` NYC scripthashes being subscribed.

## Goal

Make NYC expose **only Legacy** address types until the wallet's **own synced NYC height** reaches 13,500,000, then **automatically reveal** COMPATIBLE (P2SH-SegWit) and NATIVE_SEGWIT (bech32). Fully self-contained in the wallet — no server, no remote flag, no manual flip. Generalize the mechanism so any future-activating coin can reuse it, and leave already-active coins (BTC/LTC/DGB/VTC) unchanged.

## Decisions (from brainstorming)

1. **Trigger source:** the wallet's own `WalletPocketHD.lastBlockSeenHeight` for NYC (updated on every NYC block header). Self-contained, offline-capable, flips at the true consensus height for every install.
2. **Pre-activation behavior:** the receive screen offers **LEGACY only**, and **no new SegWit gap-limit addresses are generated** — but any **already-used** SegWit address stays in the watch list so its balance still shows and can be swept.
3. **Structure:** a **stateless, height-aware `CoinType`** (no new persisted state). The gate is computed live from `lastBlockSeenHeight`. Generalizes via a per-coin `segwitActivationHeight`.

## Design

### 1. `CoinType` (`core/src/main/java/com/openwallet/core/coins/CoinType.java`)

- Add field `protected int segwitActivationHeight = 0;` (0 ⇒ already active — the default for all existing SegWit coins).
- Add `public int getSegwitActivationHeight()`.
- Add `public boolean isSegwitActivatedAt(int height)`:
  `return segwitActivationHeight <= 0 || (height >= 0 && height >= segwitActivationHeight);`
- Add `public Set<AddressType> effectiveAddressTypes(int height)`:
  if `isSegwitActivatedAt(height)` return `supportedAddressTypes`; else return `supportedAddressTypes` with `COMPATIBLE` and `NATIVE_SEGWIT` removed (i.e. LEGACY plus any non-SegWit types).
- Keep the existing `isSegwitActivated()` but redefine it as the **static "always-active" check**: `return segwitActivationHeight <= 0;`. It is used only by the add-coin path (§4), where no synced height exists yet.
- `NewYorkCoinMain`: set `segwitActivationHeight = 13_500_000;` and **remove** the now-superseded `segwitActivated = false;` line. `supportedAddressTypes` stays `{LEGACY, COMPATIBLE, NATIVE_SEGWIT}` (these are the *eventually-supported* types; the height gate decides what is *effective*). BTC/LTC/DGB/VTC are untouched (default `segwitActivationHeight = 0`).

### 2. Generation gate — `WalletPocketHD.getActiveAddresses()`

Compute once under the lock: `boolean segwitOn = type.isSegwitActivatedAt(lastBlockSeenHeight);`

**Single-path branch** (the historical per-key emission):
- Always emit `LEGACY` for every active key (unchanged).
- Emit `COMPATIBLE` / `NATIVE_SEGWIT` for a key when `segwitOn` **OR** that key is already *used* (its index is within the keychain's issued/used count). Pre-activation this stops the SegWit gap-limit fan-out while keeping any already-used SegWit address watched.

**Bundled branch** (per-purpose keychains): apply the same `segwitOn`-or-used gate defensively — for a keychain whose purpose is `COMPATIBLE`/`NATIVE_SEGWIT`, emit its addresses only when `segwitOn` or the key is used. (Belt-and-suspenders: §4 already prevents creating a bundled NYC account pre-activation.)

`lastBlockSeenHeight` is already a field on `TransactionWatcherWallet` (accessor `getLastBlockSeenHeight()`), updated from block headers — no new plumbing.

### 3. Receive-UI gate — `AddressRequestFragment`

Replace the two reads of `type.getSupportedAddressTypes()` (the selector build at ~line 227 and the per-type check at ~line 493) with `type.effectiveAddressTypes(account.getLastBlockSeenHeight())`. Pre-activation NYC then yields a single-element set → the address-type selector collapses exactly as for a legacy-only coin. If a persisted `selectedAddressType` is not in the effective set, fall back to `LEGACY`.

`account` is a `WalletAccount`; expose `getLastBlockSeenHeight()` on that interface (it already exists on `TransactionWatcherWallet`/`WalletPocketHD`) or cast, per existing conventions.

### 4. Add-coin gate — `ConfirmAddCoinUnlockWalletDialog` / `AddCoinTask`

A brand-new NYC account has no synced height, so it cannot use the live gate. Keep gating the "All types (Coinomi-style)" / SegWit account option on the **static** `type.isSegwitActivated()` (now `segwitActivationHeight <= 0`). Pre-activation NYC therefore offers only a legacy account; BTC/LTC/etc. are unaffected.

## Testing (TDD, mocked heights)

- **CoinType** (`NewYorkCoinTest` / a new `SegwitActivationHeightTest`): `isSegwitActivatedAt` and `effectiveAddressTypes` for NYC at `13,499,999` (legacy-only), `13,500,000` and above (all three); BTC always-active at any height including `-1`.
- **WalletPocketHD.getActiveAddresses** (test mnemonic `citizen fever scale nurse brief round ski fiction car fitness pluck act`): NYC below 13.5M → no `nyc1q`/`3…` for unused keys, but a *used* SegWit key remains in the set; at/above → all SegWit types emitted. BTC/LTC unchanged (regression).
- **Backward compat:** an existing single-keychain NYC pocket with `lastBlockSeenHeight < 13.5M` loads and behaves legacy-only for new addresses; serialized key list is byte-for-byte unchanged.
- Gate `:core:test` at the established baseline — **314 tests, exactly 36 pre-existing failures, no NEW failures**; new tests raise the pass count.
- Receive-UI change is a thin substitution; validate the `effectiveAddressTypes` logic via the CoinType unit tests rather than an Android UI test.

## Edge cases

1. **Unsynced / `-1` height** → `isSegwitActivatedAt(-1) = false` → legacy-only (fail-closed). Reveals once the wallet's own headers pass 13.5M. A near-boundary reorg could momentarily re-hide; acceptable for a stateless gate and a non-issue ~672k blocks out.
2. **Already-generated `nyc1q` gap-limit addresses** (existing installs) → pre-activation they stop being emitted for unused keys; the server simply stops receiving those subscriptions. No balance loss; legacy funds unaffected; used SegWit stays watched.
3. **Incoming/spend not gated** → `markAddressAsUsed`, output attribution (`BitAddressUtils.producesAddress`), and `TransactionCreator` signing are unchanged, so any funds that land on a watched SegWit address still register and stay spendable/sweepable. This is what makes "keep watching used" work.
4. **Stale receive selection** → falls back to LEGACY (see §3).

## Non-goals

- Changing NYC consensus, the node, or electrumx.
- Taproot/MWEB reveal (heights 14M/15M) — the same `CoinType` mechanism can be extended later but is out of scope here.
- Migrating or sweeping any funds already on pre-activation SegWit addresses.
- Testnet-specific activation heights.

## Files touched

- `core/src/main/java/com/openwallet/core/coins/CoinType.java` (add height field + helpers)
- `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java` (set `segwitActivationHeight`, drop `segwitActivated`)
- `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` (`getActiveAddresses` gate, both branches)
- `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java` (effective types + stale-selection fallback)
- `wallet/src/main/java/com/openwallet/wallet/ui/dialogs/ConfirmAddCoinUnlockWalletDialog.java` (static gate, likely already correct)
- Tests: `core/src/test/java/com/openwallet/core/coins/SegwitActivationHeightTest.java` (new), additions to `WalletPocketHD`/NYC generation tests
