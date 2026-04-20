# NYC OpenWallet — Address Formats, SimpleSwap Exchange & NYC Address Fix
**Date:** 2026-03-27
**Status:** Approved
**Scope:** Current release

---

## 1. Overview

Four changes ship together in this release:

| # | Change | Complexity |
|---|--------|-----------|
| 1 | Fix NYC address prefix (`N` → `R`) | Trivial |
| 2 | Replace ShapeShift exchange with SimpleSwap WebView | Small |
| 3 | BIP address-type selector on Receive tab (BTC/LTC/DGB/VTC) | Medium |
| 4 | Litecoin visible in coin list | Already present — resolved by #2 |

---

## 2. NYC Address Prefix Fix

### Problem
`NewYorkCoinMain.java` sets `addressHeader = 52` (0x34), which produces `N…` addresses via base58check. The real NewYorkCoin network uses version byte 60 (0x3C), producing `R…` addresses.

### Fix
```java
// NewYorkCoinMain.java
addressHeader = 60;  // 0x3C → R... prefix
// acceptableAddressCodes = new int[] { addressHeader, p2shHeader } already present in the file
// and references addressHeader by name, so it picks up the new value automatically — no edit needed
```

### Impact
- No migration needed. Private keys are unchanged; addresses re-derive from the same keys with the corrected version byte on next wallet load.
- Any `N…` addresses previously shown were invalid on the real network. This correction aligns the wallet with mainnet.
- `dumpedPrivateKeyHeader = 188` (0xBC) and `p2shHeader = 22` (0x16) are correct and unchanged.

---

## 3. SimpleSwap Exchange WebView (Option B — pre-fill)

### Problem
The Exchange screen is powered by the ShapeShift.io API, which is no longer operational. All exchange dropdowns are empty and no trades can be initiated.

### Design

**URL format:**
```
https://simpleswap.io/?from={from_ticker}&to=nyc&addressTo={nyc_receive_address}
```

- `from_ticker` = lowercase symbol of the last active wallet account (e.g. `btc`, `ltc`, `doge`)
- If the active account *is* NYC, `from_ticker` defaults to `btc`
- `addressTo` = the user's current NYC receive address (`R…`) — note the parameter name is `addressTo`, not `address`

**Code changes:**

1. **`TradeActivity.java`** — strip all ShapeShift fragment transactions; inflate a single `SimpleSwapFragment` instead
2. **`SimpleSwapFragment.java`** (new) — hosts a `WebView` with:
   - JavaScript enabled
   - DOM storage enabled
   - External links (e.g. block explorers, confirmations) open in system browser via `Intent.ACTION_VIEW`
   - Back-press intercepted: if `webView.canGoBack()` → `webView.goBack()`; otherwise finish activity
3. **Remove** `MakeTransactionFragment.java`, `TradeStatusFragment.java`, `TradeStatusActivity.java`, `TradeSelectFragment.java` — ShapeShift-specific; unused after this change
4. **`PayWithDialog.java`** — remove "Powered by ShapeShift" click handler and badge; replace with plain "Exchange via SimpleSwap" text link
5. **`strings.xml`** — replace ShapeShift strings with SimpleSwap equivalents:
   - `about_shapeshift_title` → `about_simpleswap_title`
   - `about_shapeshift_message` → `about_simpleswap_message`
6. **Navigation drawer** — "Trade" item label unchanged; entry point unchanged

**What is NOT changed:**
- The ShapeShift core library (`com.openwallet.core.exchange.shapeshift`) is left in place to avoid compile breaks from other residual references. It simply goes unused.

### Litecoin visibility
Litecoin (`LitecoinMain`) is already present in `Constants.SUPPORTED_COINS` and will appear in both the Add Coins list and the SimpleSwap `from` coin dropdown. No additional change required.

---

## 4. BIP Address-Type Selector on Receive Tab

### Supported coins and address types

| Coin | Legacy (P2PKH) | Compatibility (P2SH-P2WPKH) | Native SegWit (P2WPKH) | Taproot (P2TR) | Bech32 HRP |
|------|:-:|:-:|:-:|:-:|:---:|
| Bitcoin (BTC) | ✓ `1…` | ✓ `3…` | ✓ `bc1q…` | ✓ `bc1p…` | `bc` |
| Litecoin (LTC) | ✓ `L…` | ✓ `M…` | ✓ `ltc1q…` | — | `ltc` |
| DigiByte (DGB) | ✓ `D…` | — | ✓ `dgb1q…` | — | `dgb` |
| Vertcoin (VTC) | ✓ `V…` | — | ✓ `vtc1q…` | — | `vtc` |
| NewYorkCoin + others | ✓ only | — | — | — | — |

**Note on Litecoin `M…` addresses:** The current `LitecoinMain.p2shHeader = 5` (0x05) produces `3…` P2SH addresses (same version byte as Bitcoin). To generate the canonical Litecoin Compatibility (`M…`) addresses, `p2shHeader` must be updated to `50` (0x32). This change is included in this release (see Files Changed). The `acceptableAddressCodes` field in `LitecoinMain` is declared as `new int[] { addressHeader, p2shHeader }`, so it automatically picks up the new value; no separate update to that field is needed.

### Architecture decision: same key, multiple encodings

All address types are derived from the **same BIP44 receive key** already in the wallet keychain. Bitcoin does not enforce a relationship between derivation path and address format — the wallet holds the private key and can spend UTXOs regardless of which address format was used to receive them.

This avoids creating parallel key chains (BIP49/84/86), which would require rewriting wallet serialisation and is deferred to a future phase.

### Address encoding rules

| Type | Encoding |
|------|----------|
| LEGACY | `base58check(addressHeader ‖ hash160(pubkey))` |
| COMPATIBILITY | `base58check(p2shHeader ‖ hash160(OP_0 ‖ hash160(pubkey)))` |
| NATIVE_SEGWIT | `bech32(hrp, witnessVersion=0, hash160(pubkey))` |
| TAPROOT | `bech32m(hrp, witnessVersion=1, tapTweakedXOnlyPubkey)` where tweak follows BIP86: `Q = P + hashTapTweak(Px)·G` |

**Taproot implementation note:** The BIP86 key tweak requires EC point addition (`Q = P + scalar·G`). This is available via the SpongyCastle library already present in the build (`com.madgag.spongycastle:core:1.51.0.0`). The `AddressEncoder` will use `org.spongycastle.math.ec.ECPoint` for the tweak calculation. Specifically: compute `t = SHA256(tag ‖ tag ‖ Px)` where `tag = SHA256("TapTweak")`, then `Q = ECKey.fromPublicOnly(P.add(ECKey.fromPrivate(t).getPubKeyPoint()))` and take the x-only coordinate.

### New code — core module

**`com.openwallet.core.coins.AddressType`** (new enum)
```
LEGACY, COMPATIBILITY, NATIVE_SEGWIT, TAPROOT
```

**`com.openwallet.core.util.Bech32`** (new utility, ~150 lines)
- `encode(String hrp, int witnessVersion, byte[] data) → String` (uses bech32 checksum for v0, bech32m for v1+)
- `decode(String hrp, String addr) → DecodedBech32` (validates checksum and witness version)
- Self-contained; no external dependencies beyond standard Java

**`com.openwallet.core.util.AddressEncoder`** (new utility)
- `encode(ECKey key, CoinType type, AddressType addrType) → String`
- Dispatches to the four encoding paths above
- Throws `UnsupportedAddressTypeException` if `addrType` not in `type.getSupportedAddressTypes()`
- For TAPROOT, uses SpongyCastle `org.spongycastle.math.ec.ECPoint` for BIP86 key tweak

**`CoinType.java`** additions
```java
protected String bech32Hrp = null;
protected List<AddressType> supportedAddressTypes = ImmutableList.of(AddressType.LEGACY);

public String getBech32Hrp() { return bech32Hrp; }
public List<AddressType> getSupportedAddressTypes() { return supportedAddressTypes; }
```

**Per-coin declarations** (`BitcoinMain`, `LitecoinMain`, `DigibyteMain`, `VertcoinMain`)
```java
// Example — BitcoinMain
bech32Hrp = "bc";
supportedAddressTypes = ImmutableList.of(
    AddressType.LEGACY,
    AddressType.COMPATIBILITY,
    AddressType.NATIVE_SEGWIT,
    AddressType.TAPROOT
);

// LitecoinMain — also update p2shHeader for M... addresses
p2shHeader = 50;  // 0x32 → M... Compatibility addresses (was 5)
bech32Hrp = "ltc";
supportedAddressTypes = ImmutableList.of(
    AddressType.LEGACY,
    AddressType.COMPATIBILITY,
    AddressType.NATIVE_SEGWIT
);
```

### New code — wallet module

**`AddressRequestFragment.java`** changes
- Add `selectedAddressType` field; initialised from `SharedPreferences` key `"addr_type_{coinId}"`, defaulting to `NATIVE_SEGWIT` for multi-type coins, `LEGACY` otherwise
- If `type.getSupportedAddressTypes().size() > 1`: inflate a horizontal `ChipGroup` above the QR code with one chip per supported type; chips are single-selection
- `updateView()` calls `AddressEncoder.encode(currentReceiveKey, type, selectedAddressType)` instead of `account.getReceiveAddress()` when an address type is selected
- On chip selection change: update `selectedAddressType`, persist to `SharedPreferences`, call `updateView()`
- Coins with only `LEGACY` show no chip group — UI unchanged from current

**`fragment_request.xml`** additions
- `ChipGroup` with `id="address_type_chips"`, `visibility="gone"` by default
- Fragment shows/hides and populates chips programmatically based on `getSupportedAddressTypes()`

### Key extraction
`account.getReceiveAddress()` returns an `AbstractAddress`. The underlying `ECKey` is obtained via `account.findKeyFromPubHash(address.getHash160())`. This call compiles directly on `WalletAccount` because `WalletAccount` extends the `org.bitcoinj.wallet.KeyBag` interface, which declares `findKeyFromPubHash`. No cast is required. The returned key is passed to `AddressEncoder`.

### Persistence
Selected address type stored in `SharedPreferences` as `"addr_type_bitcoin.main"`, `"addr_type_litecoin.main"`, etc. Defaults to `NATIVE_SEGWIT` for coins that support it; `LEGACY` for all others.

---

## 5. Files Changed Summary

### Core module
| File | Change |
|------|--------|
| `coins/NewYorkCoinMain.java` | `addressHeader` 52 → 60 (one-line change; `acceptableAddressCodes` auto-propagates) |
| `coins/AddressType.java` | New enum |
| `coins/CoinType.java` | Add `bech32Hrp`, `supportedAddressTypes` fields |
| `coins/BitcoinMain.java` | Set `bech32Hrp="bc"`, full address type list |
| `coins/LitecoinMain.java` | `p2shHeader` 5 → 50 (for `M…`); set `bech32Hrp="ltc"`, Legacy/Compat/NativeSegwit |
| `coins/DigibyteMain.java` | Set `bech32Hrp="dgb"`, Legacy/NativeSegwit |
| `coins/VertcoinMain.java` | Set `bech32Hrp="vtc"`, Legacy/NativeSegwit |
| `util/Bech32.java` | New utility |
| `util/AddressEncoder.java` | New utility (uses SpongyCastle ECPoint for Taproot tweak) |

### Wallet module
| File | Change |
|------|--------|
| `ui/TradeActivity.java` | Replace ShapeShift fragments with SimpleSwapFragment |
| `ui/SimpleSwapFragment.java` | New — WebView loading SimpleSwap URL |
| `ui/MakeTransactionFragment.java` | Remove (ShapeShift-specific, unused) |
| `ui/TradeStatusFragment.java` | Remove (ShapeShift-specific, unused) |
| `ui/TradeStatusActivity.java` | Remove (ShapeShift-specific, unused) |
| `ui/TradeSelectFragment.java` | Remove (ShapeShift-specific, unused) |
| `ui/AddressRequestFragment.java` | Add ChipGroup address-type selector |
| `res/layout/fragment_request.xml` | Add ChipGroup |
| `res/values/strings.xml` | Replace ShapeShift strings with SimpleSwap |

---

## 6. Out of Scope (Future Phase)

- Proper BIP49/84/86 separate key chains with independent wallet serialisation
- Spending from SegWit/Taproot UTXOs using witness transaction inputs (requires bitcoinj upgrade)
- NYC SegWit/Taproot address support
- `N…` address backward-compatibility mode for NYC
