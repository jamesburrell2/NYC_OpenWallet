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
- `sensorPortrait` stays in portrait but still honours a 180° upside-down flip.
- Note: a 180° flip is still a config change; the existing `android:configChanges="orientation|keyboard|keyboardHidden"` does not include `screenLayout` or `screenSize`. This is acceptable — the scanner activity will restart on a 180° flip, which is an edge case of no practical consequence for QR scanning.
- `android:configChanges` line remains otherwise unchanged.

---

## Fix 2 — SegWit Addresses Accepted in Send

**Problem:** `GenericUtils.getPossibleTypes(String)` calls `tryBitcoinFamilyAddresses()`, which attempts to decode the string as base58check via `VersionedChecksummedBytes`. Bech32 strings (`bc1q…`, `ltc1q…`, `bc1p…`) are not valid base58, so decoding throws silently, the builder stays empty, and `getPossibleTypes()` throws `AddressMalformedException("Unsupported address: …")`. The Send screen displays this as an error.

**Changes:**

### A. `GenericUtils.java` — add `tryBech32Addresses()`

```java
private static void tryBech32Addresses(String addressStr, ImmutableList.Builder<CoinType> builder) {
    Bech32.DecodedBech32 decoded;
    try { decoded = Bech32.decode(addressStr); }
    catch (IllegalArgumentException e) { return; }  // not bech32, silently ignore

    for (CoinType type : CoinID.getSupportedCoins()) {
        String hrp = type.getBech32Hrp();
        if (hrp != null && hrp.equals(decoded.hrp)) {  // decoded.hrp is already lowercase
            builder.add(type);
            break;
        }
    }
}
```

Call `tryBech32Addresses(addressStr, builder)` from `getPossibleTypes()` immediately after the `tryBitcoinFamilyAddresses(addressStr, builder)` call.

**Taproot handling (witnessVersion == 1):** The coin type is still added to `possibleTypes`. The existing block in `SendFragment.validateAddress()` already detects `witnessVersion == 1` after `parseAddress()` succeeds and shows `R.string.taproot_destination_not_supported`. No change to `SendFragment` is needed.

### B. `TaprootAddress.java` — add `fromOutputKey()` factory

`TaprootAddress.fromXOnlyKey()` applies the BIP341 tweak. When parsing a bech32m string, `decoded.program` is already the tweaked 32-byte output key Q. Applying the tweak again would produce a wrong address. A new factory method that stores the key without re-tweaking is needed:

```java
/**
 * Create from an already-tweaked 32-byte x-only output key
 * (e.g. decoded directly from a bech32m address string).
 * Does NOT apply the BIP341 key-path tweak.
 */
public static TaprootAddress fromOutputKey(CoinType type, byte[] outputKey32) {
    if (outputKey32.length != 32)
        throw new IllegalArgumentException("output key must be 32 bytes, got: " + outputKey32.length);
    return new TaprootAddress(type, outputKey32);
}
```

### C. `BitFamily.java` — extend `newAddress(String)` with bech32 fallback

```java
@Override
public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
    // Try base58check first (Legacy and P2SH-SegWit addresses)
    try { return BitAddress.from(this, addressStr); }
    catch (AddressMalformedException ignored) {}

    // Try bech32 for coins with a configured HRP
    if (getBech32Hrp() != null) {
        try {
            Bech32.DecodedBech32 decoded = Bech32.decode(addressStr);
            if (decoded.witnessVersion == 0) {
                // P2WPKH: program is the 20-byte key hash
                return SegwitAddress.fromHash160(this, decoded.program);
            }
            if (decoded.witnessVersion == 1) {
                // P2TR: program is the already-tweaked 32-byte x-only output key
                return TaprootAddress.fromOutputKey(this, decoded.program);
            }
        } catch (IllegalArgumentException ignored) {}
    }

    throw new AddressMalformedException("Unsupported address: " + addressStr);
}
```

**Required imports in `BitFamily.java`:**
```java
import com.openwallet.core.util.Bech32;
import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
```

---

## Fix 3 — Balance Covers All Address Types (markAddressAsUsed)

**Problem:** `WalletPocketHD.markAddressAsUsed(AbstractAddress)` checks `instanceof BitAddress` and throws `IllegalArgumentException` for `SegwitAddress`. When a SegWit address receives a UTXO, the Electrum notification path calls `markAddressAsUsed()`, which currently throws — the HD keychain does not advance, and the next receive address is not generated.

**Taproot is excluded:** `getActiveAddresses()` explicitly excludes `TAPROOT` from the watch list (`// TAPROOT is receive/display only — not included in watch list`). `subscribeToAddresses()` is therefore never called with a `TaprootAddress`, and `markAddressAsUsed()` will never receive one. No Taproot handling is needed here.

`getActiveAddresses()` already emits LEGACY + COMPATIBLE + NATIVE_SEGWIT for all SegWit-capable coins, so the subscriptions are in place.

**File:** `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java`

**Change:** Extend `markAddressAsUsed(AbstractAddress)` to handle `SegwitAddress`:

```java
@Override
public void markAddressAsUsed(AbstractAddress address) {
    checkArgument(address.getType().equals(type), "Wrong address type");
    if (address instanceof BitAddress) {
        markAddressAsUsed((BitAddress) address);
    } else if (address instanceof SegwitAddress) {
        // SegwitAddress.getHash160() returns the same 20-byte HASH160 used for the
        // corresponding legacy key, so markPubHashAsUsed correctly advances the keychain.
        keys.markPubHashAsUsed(((SegwitAddress) address).getHash160());
    } else {
        throw new IllegalArgumentException("Wrong address class: " + address.getClass());
    }
}
```

Required import: `import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;`

---

## Fix 4 — Receive Screen: Segmented Tab Strip + Derivation Path

**Design reference:** User-provided Image 1 shows:
- "My address" label
- Address text (large monospace)
- Derivation path (small gray, e.g. `M/44H/0H/0H/0/1`)
- Horizontal pill/tab strip: **Default | Compatibility | Legacy**
- QR code below

### Tab ordering and address type mapping

The tab buttons must appear in this specific left-to-right order (not the `EnumSet` iteration order, which is `LEGACY, COMPATIBLE, NATIVE_SEGWIT, TAPROOT`):

| Visual order | Button label | `AddressType` |
|---|---|---|
| 1 | Default | `NATIVE_SEGWIT` |
| 2 | Compatibility | `COMPATIBLE` |
| 3 | Legacy | `LEGACY` |

Taproot is **not** shown as a tab in the strip. It remains accessible only as the existing receive-only display (out of scope for tab strip).

Only coins that support more than one address type show the strip. NYC (single type: `LEGACY`) shows no strip.

Initial selection: `NATIVE_SEGWIT` for SegWit-capable coins (BTC, LTC, DGB, VTC).

Tabs are shown only if the coin supports that type — e.g. if `supportedAddressTypes` does not contain `COMPATIBLE`, its tab is omitted.

### `fragment_request.xml` changes

**Remove** the existing `<LinearLayout android:id="@+id/address_type_container">` block (which contains the `<Spinner>`).

**Add** in its place a `<RadioGroup>`:

```xml
<RadioGroup
    android:id="@+id/address_type_radio_group"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center_horizontal"
    android:orientation="horizontal"
    android:visibility="gone"
    android:paddingBottom="8dp" />
```

`RadioButton`s are created dynamically in Java (see below) — none are declared in XML.

**Add** a derivation path `TextView` below the address block (after `</RelativeLayout>`, before the `RadioGroup`):

```xml
<TextView
    android:id="@+id/derivation_path_view"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_gravity="center_horizontal"
    android:textSize="11sp"
    android:typeface="monospace"
    android:textColor="@color/gray_54_sec_text_icons"
    android:paddingBottom="4dp"
    android:visibility="gone" />
```

### `styles.xml` — new `AddressTypeTab` style

`RadioButton` requires `android:button="@null"` to suppress the default radio circle. The parent style should be `Widget.AppCompat.CompoundButton` (not `Widget.AppCompat.Button.Borderless`, which targets `Button` and may produce unexpected padding on a `RadioButton`):

```xml
<style name="AddressTypeTab" parent="Widget.AppCompat.CompoundButton">
    <item name="android:button">@null</item>
    <item name="android:background">@drawable/address_tab_selector</item>
    <item name="android:textSize">13sp</item>
    <item name="android:paddingLeft">12dp</item>
    <item name="android:paddingRight">12dp</item>
    <item name="android:minWidth">0dp</item>
    <item name="android:gravity">center</item>
    <item name="android:textColor">@drawable/address_tab_text_selector</item>
</style>
```

### `drawable/address_tab_selector.xml` — checked/unchecked background

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true">
        <shape android:shape="rectangle">
            <solid android:color="@color/colorPrimary" />
            <corners android:radius="4dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
            <stroke android:width="1dp" android:color="@color/colorPrimary" />
            <corners android:radius="4dp" />
        </shape>
    </item>
</selector>
```

### `drawable/address_tab_text_selector.xml` — text colour

```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_checked="true" android:color="@android:color/white" />
    <item android:color="@color/colorPrimary" />
</selector>
```

### `AddressRequestFragment.java` changes

**Field declarations to remove:**
```java
@Bind(R.id.address_type_spinner)   Spinner addressTypeSpinner;
@Bind(R.id.address_type_container) LinearLayout addressTypeContainer;
```
Also remove the `import android.widget.Spinner;`, `import android.widget.ArrayAdapter;`, and `import android.widget.AdapterView;` lines if they become unused.

**Field declarations to add:**
```java
@Bind(R.id.address_type_radio_group) RadioGroup addressTypeRadioGroup;
@Bind(R.id.derivation_path_view)     TextView derivationPathView;
```

**Import additions:**
```java
import android.widget.RadioButton;
import android.widget.RadioGroup;
```

**Initial `selectedAddressType`:** Change from `AddressType.LEGACY` to:
```java
private AddressType selectedAddressType = AddressType.NATIVE_SEGWIT;
```
(For single-type coins like NYC, `selectedAddressType` is unused — the spinner/radio group is hidden and `updateView()` always falls back to `account.getReceiveAddress()`.)

**In `onCreateView()`:** Replace the spinner-building block with:

```java
if (type.getSupportedAddressTypes().size() > 1) {
    addressTypeRadioGroup.setVisibility(View.VISIBLE);
    derivationPathView.setVisibility(View.VISIBLE);

    // Fixed display order: Default (NATIVE_SEGWIT), Compatibility (COMPATIBLE), Legacy (LEGACY)
    AddressType[] displayOrder = { AddressType.NATIVE_SEGWIT, AddressType.COMPATIBLE, AddressType.LEGACY };
    String[] labels = {
        getString(R.string.address_type_default),
        getString(R.string.address_type_compatible),
        getString(R.string.address_type_legacy)
    };

    int firstId = View.generateViewId();
    for (int i = 0; i < displayOrder.length; i++) {
        if (!type.getSupportedAddressTypes().contains(displayOrder[i])) continue;
        RadioButton btn = new RadioButton(getActivity());
        btn.setId(firstId + i);
        btn.setText(labels[i]);
        btn.setTag(displayOrder[i]);
        btn.setLayoutParams(new RadioGroup.LayoutParams(
                RadioGroup.LayoutParams.WRAP_CONTENT,
                RadioGroup.LayoutParams.WRAP_CONTENT));
        // Apply style programmatically (AppCompat RadioButton styling)
        btn.setButtonDrawable(android.R.color.transparent);
        btn.setBackgroundResource(R.drawable.address_tab_selector);
        // Wire text colour selector explicitly — styles.xml alone does not apply when
        // the RadioButton is constructed programmatically without a ContextThemeWrapper.
        btn.setTextColor(getResources().getColorStateList(R.drawable.address_tab_text_selector));
        btn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        btn.setTextSize(13f);
        if (displayOrder[i] == selectedAddressType) btn.setChecked(true);
        addressTypeRadioGroup.addView(btn);
    }

    addressTypeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
        View btn = group.findViewById(checkedId);
        if (btn != null) {
            selectedAddressType = (AddressType) btn.getTag();
            updateView();
        }
    });
}
```

Add helper in the fragment:
```java
private int dpToPx(int dp) {
    return Math.round(dp * getResources().getDisplayMetrics().density);
}
```

Note: `minSdkVersion` is 26 — lambda syntax and `View.generateViewId()` (API 17+) are both safe to use without desugaring.

**In `updateView()`:** After computing `receiveAddress`, populate the derivation path:

```java
if (type.getSupportedAddressTypes().size() > 1) {
    try {
        AbstractAddress legacyAddr = account.getReceiveAddress();
        byte[] hash160 = ((BitAddress) legacyAddr).getHash160();
        WalletPocketHD pocketHD = (WalletPocketHD) account;
        ECKey rawKey = pocketHD.findKeyFromPubHash(hash160);
        if (rawKey instanceof DeterministicKey) {
            DeterministicKey dk = (DeterministicKey) rawKey;
            StringBuilder path = new StringBuilder("M");
            for (ChildNumber child : dk.getPath()) {
                path.append('/').append(child.toString());
            }
            derivationPathView.setText(path.toString());
            derivationPathView.setVisibility(View.VISIBLE);
        } else {
            derivationPathView.setVisibility(View.GONE);
        }
    } catch (Exception e) {
        derivationPathView.setVisibility(View.GONE);
    }
}
```

**Derivation path accuracy note:** This app derives all address types (Legacy, Compatible, Native SegWit) from the **same BIP44 HD key** rather than separate per-purpose key trees (BIP49/BIP84/BIP86). The displayed path therefore reflects the single underlying HD key path (e.g. `M/44H/0H/0H/0/1`) for all three tabs — this is an intentional architectural simplification, not a display bug. The path shown is always accurate for the key being used, even if the convention differs from multi-account wallets that use separate derivation paths per address type.

Required imports:
```java
import com.google.bitcoin.crypto.DeterministicKey;
import com.google.bitcoin.crypto.ChildNumber;
import com.openwallet.core.wallet.WalletPocketHD;
```

### `strings.xml` — new string

```xml
<string name="address_type_default">Default</string>
```

Existing strings `address_type_legacy`, `address_type_compatible`, `address_type_native_segwit`, `address_type_taproot` are retained but `address_type_native_segwit` is no longer used as a tab label.

---

## Fix 5 — LTC Fallback Server Replacement

**Problem:** `ltc.electrum.bitaroo.net:50002` fails DNS resolution (confirmed via Python TLS probe).

**File:** `wallet/src/main/java/com/openwallet/wallet/Constants.java`

**Change:** Replace the second LTC `ServerAddress`:
```java
// Before (dead DNS):
new ServerAddress("ltc.electrum.bitaroo.net", 50002)

// After:
new ServerAddress("electrumx.ltc.aranguren.org", 50002)
```

**Verification gate:** Before committing, run the Python TLS probe and confirm `[TLS OK]` response from `electrumx.ltc.aranguren.org:50002`.

---

## Files Changed Summary

| File | Change |
|---|---|
| `wallet/src/main/AndroidManifest.xml` | `sensorPortrait` for ScanActivity |
| `core/.../util/GenericUtils.java` | Add `tryBech32Addresses()`; call it from `getPossibleTypes()` |
| `core/.../coins/families/BitFamily.java` | Bech32 fallback in `newAddress()`; add 3 imports |
| `core/.../wallet/families/bitcoin/TaprootAddress.java` | Add `fromOutputKey()` factory method |
| `core/.../wallet/WalletPocketHD.java` | Extend `markAddressAsUsed()` for SegwitAddress; add import |
| `wallet/src/main/res/layout/fragment_request.xml` | Remove Spinner block; add RadioGroup + derivation path TextView |
| `wallet/src/main/res/values/styles.xml` | Add `AddressTypeTab` style |
| `wallet/src/main/res/drawable/address_tab_selector.xml` | New file: checked/unchecked background drawable |
| `wallet/src/main/res/drawable/address_tab_text_selector.xml` | New file: checked/unchecked text colour selector |
| `wallet/src/main/res/values/strings.xml` | Add `address_type_default` string |
| `wallet/src/main/java/.../AddressRequestFragment.java` | Wire RadioGroup + derivation path; remove Spinner fields |
| `wallet/src/main/java/.../Constants.java` | Replace dead LTC server |

---

## Testing Checklist

- [ ] Open QR scanner from Send screen → screen stays in portrait
- [ ] Type `bc1q…` (native SegWit) address in Send → accepted, QR shown
- [ ] Type `bc1p…` (Taproot) address in Send → "Taproot not supported" error shown
- [ ] Type base58 address for wrong coin in Send → "Unsupported address" error shown
- [ ] Receive screen (BTC): three tabs shown (Default | Compatibility | Legacy); Default selected; bech32 address shown
- [ ] Tap Compatibility tab → P2SH (3…) address shown, QR updates
- [ ] Tap Legacy tab → base58 (1…) address shown, QR updates
- [ ] Derivation path (e.g. `M/44H/0H/0H/0/1`) visible below address on all three tabs
- [ ] Receive screen (NYC): no tab strip shown, no derivation path shown
- [ ] LTC server TLS probe confirms `electrumx.ltc.aranguren.org:50002` returns `[TLS OK]` before merge
- [ ] `./gradlew :wallet:assembleDebug` BUILD SUCCESSFUL
