# Design Spec: SegWit UX Fixes & Multi-Address Receive UI
**Date:** 2026-03-28
**Branch:** `feature/nyc-coin-integration`

---

## Overview

Five targeted fixes to the NYC OpenWallet Android app following the multi-address-type implementation:

1. Lock QR scan screen to portrait orientation
2. Allow native SegWit (bech32) addresses to be entered in the Send screen
3. Ensure SegWit address activity marks the HD keychain as used (balance completeness)
4. Replace the address-type Spinner on the Receive screen with a segmented tab strip matching the preferred design reference
5. Replace dead LTC fallback Electrum server

---

## Fix 1 — QR Scan Orientation Lock

**Problem:** `ScanActivity` declares `android:screenOrientation="landscape"`, so opening the QR scanner rotates the device from portrait to landscape (and back on close).

**File:** `wallet/src/main/AndroidManifest.xml`

**Change:** Set `android:screenOrientation="sensorPortrait"` on `ScanActivity`.
- `sensorPortrait` stays in portrait regardless of camera orientation, but still honours a 180° upside-down flip, which is the most natural portrait lock.
- `android:configChanges="orientation|keyboard|keyboardHidden"` remains unchanged.

---

## Fix 2 — SegWit Addresses Accepted in Send

**Problem:** `GenericUtils.getPossibleTypes(String)` calls `tryBitcoinFamilyAddresses()`, which attempts to decode the string as base58check via `VersionedChecksummedBytes`. Bech32 strings (`bc1q…`, `ltc1q…`, `bc1p…`) are not valid base58, so decoding throws silently, the builder stays empty, and `getPossibleTypes()` throws `AddressMalformedException("Unsupported address: …")`. The Send screen displays this as an error.

**Changes:**

### `GenericUtils.java` — add `tryBech32Addresses()`

```
private static void tryBech32Addresses(String addressStr, ImmutableList.Builder<CoinType> builder) {
    Bech32.DecodedBech32 decoded;
    try { decoded = Bech32.decode(addressStr); }
    catch (IllegalArgumentException e) { return; }   // not bech32, ignore

    for (CoinType type : CoinID.getSupportedCoins()) {
        String hrp = type.getBech32Hrp();
        if (hrp != null && hrp.equalsIgnoreCase(decoded.hrp)) {
            builder.add(type);
            break;
        }
    }
}
```

Call `tryBech32Addresses()` from `getPossibleTypes()` after `tryBitcoinFamilyAddresses()`.

**Taproot handling (witnessVersion == 1):** The coin type is still added to `possibleTypes`. The existing block in `SendFragment.validateAddress()` already detects `witnessVersion == 1` after `parseAddress()` succeeds and shows the `R.string.taproot_destination_not_supported` error. No change needed in `SendFragment`.

### `BitFamily.newAddress(String)` — add bech32 fallback

```
@Override
public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
    // Try base58 first
    try { return BitAddress.from(this, addressStr); }
    catch (AddressMalformedException ignored) {}

    // Try bech32 if this coin has an HRP configured
    if (getBech32Hrp() != null) {
        try {
            Bech32.DecodedBech32 decoded = Bech32.decode(addressStr);
            if (decoded.witnessVersion == 0) return new SegwitAddress(this, decoded.data);
            if (decoded.witnessVersion == 1) return new TaprootAddress(this, decoded.data);
        } catch (IllegalArgumentException ignored) {}
    }

    throw new AddressMalformedException("Unsupported address: " + addressStr);
}
```

`SegwitAddress` and `TaprootAddress` already exist from the prior session.

---

## Fix 3 — Balance Covers All Address Types (markAddressAsUsed)

**Problem:** `WalletPocketHD.markAddressAsUsed(AbstractAddress)` checks `instanceof BitAddress` and throws `IllegalArgumentException` for `SegwitAddress` / `TaprootAddress`. When a SegWit address receives a UTXO, the Electrum notification path calls `markAddressAsUsed()`, which currently fails silently — the HD keychain does not advance, and the next receive address is not generated.

`getActiveAddresses()` already emits LEGACY + COMPATIBLE + NATIVE_SEGWIT for all SegWit-capable coins, so the subscriptions are in place.

**File:** `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java`

**Change:** Extend `markAddressAsUsed(AbstractAddress)` to extract `hash160` from `SegwitAddress` and `TaprootAddress`:

```java
@Override
public void markAddressAsUsed(AbstractAddress address) {
    checkArgument(address.getType().equals(type), "Wrong address type");
    if (address instanceof BitAddress) {
        markAddressAsUsed((BitAddress) address);
    } else if (address instanceof SegwitAddress) {
        keys.markPubHashAsUsed(((SegwitAddress) address).getPubKeyHash());
    } else if (address instanceof TaprootAddress) {
        keys.markPubHashAsUsed(((TaprootAddress) address).getPubKeyHash());
    } else {
        throw new IllegalArgumentException("Wrong address class: " + address.getClass());
    }
}
```

Both `SegwitAddress` and `TaprootAddress` already store `pubKeyHash` (the 20-byte HASH160 of the key). A `getPubKeyHash()` accessor must be added to each if not already present.

---

## Fix 4 — Receive Screen: Segmented Tab Strip + Derivation Path

**Design reference:** Image 1 (provided by user) shows:
- "My address" label
- Address text (large monospace)
- Derivation path (small gray, e.g. `M/44H/0H/0H/0/1`)
- Horizontal pill/tab strip: **Default | Compatibility | Legacy**
- QR code below

### Tab labels and address type mapping

| Button label | `AddressType` |
|---|---|
| Default | `NATIVE_SEGWIT` |
| Compatibility | `COMPATIBLE` |
| Legacy | `LEGACY` |
| Taproot | `TAPROOT` *(receive-only; informational note shown below QR)* |

Initial selection: `NATIVE_SEGWIT` for SegWit-capable coins. No tab strip shown for NYC (single type).

### `fragment_request.xml` changes

Remove the existing `<LinearLayout id="address_type_container">` containing the `<Spinner>`.

Replace with a `<RadioGroup>` styled as a horizontal pill strip:
- `android:orientation="horizontal"`
- Each `<RadioButton>` uses `style="@style/AddressTypeTab"` (defined in `styles.xml`)
- Buttons are created dynamically in Java (only show buttons for types the coin supports)
- IDs assigned programmatically

Add a `<TextView id="derivation_path_view">` below the address TextView:
- `textSize="11sp"`, `textColor="@color/gray_54_sec_text_icons"`, `fontFamily="monospace"`

Remove `@Bind` annotation for `addressTypeSpinner` and `addressTypeContainer`; add `@Bind` for `RadioGroup` and `derivationPathView`.

### `styles.xml` — new `AddressTypeTab` style

```xml
<style name="AddressTypeTab" parent="Widget.AppCompat.Button.Borderless">
    <item name="android:background">@drawable/address_tab_selector</item>
    <item name="android:textSize">13sp</item>
    <item name="android:paddingLeft">12dp</item>
    <item name="android:paddingRight">12dp</item>
    <item name="android:minWidth">0dp</item>
    <item name="android:button">@null</item>
    <item name="android:gravity">center</item>
</style>
```

A `drawable/address_tab_selector.xml` provides the checked/unchecked background (solid accent colour when checked, outline/transparent when unchecked).

### `AddressRequestFragment.java` changes

- Remove `Spinner addressTypeSpinner`, `LinearLayout addressTypeContainer` fields and `@Bind` annotations.
- Add `@Bind(R.id.address_type_radio_group) RadioGroup addressTypeRadioGroup` and `@Bind(R.id.derivation_path_view) TextView derivationPathView`.
- In `onCreateView()`: if `type.getSupportedAddressTypes().size() > 1`, dynamically create `RadioButton`s for each supported type (except TAPROOT for now), set initial check to NATIVE_SEGWIT, attach `OnCheckedChangeListener` → `selectedAddressType` → `updateView()`.
- Initial `selectedAddressType` changes from `AddressType.LEGACY` to `AddressType.NATIVE_SEGWIT` for SegWit-capable coins.
- In `updateView()`: after computing `receiveAddress`, retrieve the underlying `DeterministicKey` from `pocketHD.findKeyFromPubHash(hash160)`, call `key.getPath()` and format as `M/…H/…` string, set on `derivationPathView`.

### `strings.xml` — new string

```xml
<string name="address_type_default">Default</string>
```

(Existing strings `address_type_legacy`, `address_type_compatible`, `address_type_native_segwit`, `address_type_taproot` are reused.)

---

## Fix 5 — LTC Fallback Server Replacement

**Problem:** `ltc.electrum.bitaroo.net:50002` fails DNS resolution.

**File:** `wallet/src/main/java/com/openwallet/wallet/Constants.java`

**Change:** Replace second LTC `ServerAddress` entry:
```java
// Before (dead):
new ServerAddress("ltc.electrum.bitaroo.net", 50002)

// After:
new ServerAddress("electrumx.ltc.aranguren.org", 50002)
```

Connectivity will be verified with the same Python TLS probe used in the investigation phase before committing.

---

## Files Changed Summary

| File | Change |
|---|---|
| `wallet/src/main/AndroidManifest.xml` | `sensorPortrait` for ScanActivity |
| `core/.../util/GenericUtils.java` | Add `tryBech32Addresses()` |
| `core/.../coins/families/BitFamily.java` | Bech32 fallback in `newAddress()` |
| `core/.../wallet/WalletPocketHD.java` | Extend `markAddressAsUsed()` for SegWit/Taproot |
| `core/.../wallet/families/bitcoin/SegwitAddress.java` | Add `getPubKeyHash()` if missing |
| `core/.../wallet/families/bitcoin/TaprootAddress.java` | Add `getPubKeyHash()` if missing |
| `wallet/src/main/res/layout/fragment_request.xml` | Swap Spinner for RadioGroup; add derivation path TextView |
| `wallet/src/main/res/values/styles.xml` | Add `AddressTypeTab` style |
| `wallet/src/main/res/drawable/address_tab_selector.xml` | New checked/unchecked drawable |
| `wallet/src/main/res/values/strings.xml` | Add `address_type_default` string |
| `wallet/src/main/java/.../AddressRequestFragment.java` | Wire RadioGroup + derivation path |
| `wallet/src/main/java/.../Constants.java` | Replace dead LTC server |

---

## Testing Checklist

- [ ] Open QR scanner from Send screen → stays in portrait
- [ ] Type `bc1q…` address in Send → accepted, QR shown
- [ ] Type `bc1p…` address in Send → "Taproot not supported" error shown
- [ ] Type non-SegWit address for wrong coin → "Unsupported address" shown
- [ ] Receive screen (BTC): three tabs shown — Default selected, bech32 address shown
- [ ] Receive screen (BTC): tap Compatibility → P2SH address shown
- [ ] Receive screen (BTC): tap Legacy → base58 address shown
- [ ] Derivation path string is visible and non-empty under address
- [ ] Receive screen (NYC): no tab strip shown
- [ ] LTC server connectivity passes Python TLS probe before merge
