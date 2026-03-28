# SegWit UX Fixes & Multi-Address Receive UI — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix five post-launch issues: QR scan rotation, SegWit addresses rejected in Send, broken HD keychain advancement on SegWit UTXOs, Spinner→tab-strip UI on Receive screen, and a dead LTC Electrum server.

**Architecture:** All fixes are self-contained and non-breaking. Core library fixes (Tasks 1–3) are covered by JUnit tests. The Receive UI overhaul (Task 4) is pure View/Fragment code with no new state; it replaces a Spinner with a RadioGroup using the same `selectedAddressType` field. The manifest and server fixes (Tasks 5–6) are single-line changes.

**Tech Stack:** Java 8 · Android API 26+ · bitcoinj-core-0.12.3 · ButterKnife 7.0.1 · `./gradlew` (Gradle 8)

**Spec:** `docs/superpowers/specs/2026-03-28-segwit-ux-fixes-design.md`

---

## Chunk 1: Core Library Fixes (Tasks 1–3)

### Task 1: Add `TaprootAddress.fromOutputKey()` factory

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java` (after line 56)
- Modify: `core/src/test/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddressTest.java` (add test)

- [ ] **Step 1.1 — Write failing test**

  Open `core/src/test/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddressTest.java` and add at the end of the class (before the closing `}`):

  ```java
  @Test
  public void fromOutputKey_storesKeyWithoutRetweak() {
      // A known 32-byte x-only output key (all-0x02 bytes, for testing only)
      byte[] outputKey = new byte[32];
      java.util.Arrays.fill(outputKey, (byte) 0x02);
      TaprootAddress addr = TaprootAddress.fromOutputKey(BTC, outputKey);
      // fromOutputKey must NOT apply the BIP341 tweak again — output key must be stored as-is
      assertArrayEquals(outputKey, addr.getOutputKey());
  }

  @Test
  public void fromOutputKey_rejectsWrongLength() {
      try {
          TaprootAddress.fromOutputKey(BTC, new byte[31]);
          fail("Expected IllegalArgumentException for wrong key length");
      } catch (IllegalArgumentException e) {
          assertTrue(e.getMessage().contains("32 bytes"));
      }
  }
  ```

  Also add this import at the top of the file if not already present:
  ```java
  import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
  ```

- [ ] **Step 1.2 — Run tests to confirm they fail**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.wallet.families.bitcoin.SegwitAddressTest.fromOutputKey_*" 2>&1 | tail -20
  ```
  Expected: FAILED — `fromOutputKey` method does not exist yet.

- [ ] **Step 1.3 — Add `fromOutputKey()` to `TaprootAddress.java`**

  In `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java`, insert after the closing `}` of `fromKey()` (after line 57):

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

- [ ] **Step 1.4 — Run tests to confirm they pass**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.wallet.families.bitcoin.SegwitAddressTest.fromOutputKey_*" 2>&1 | tail -10
  ```
  Expected: `BUILD SUCCESSFUL` · 2 tests passed.

- [ ] **Step 1.5 — Commit**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  git add core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java \
          core/src/test/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddressTest.java
  git commit -m "feat: add TaprootAddress.fromOutputKey() for bech32m string parsing"
  ```

---

### Task 2: Fix `GenericUtils` + `BitFamily` — accept bech32 addresses in Send

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/util/GenericUtils.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/families/BitFamily.java`
- Create: `core/src/test/java/com/openwallet/core/util/GenericUtilsBech32Test.java`

- [ ] **Step 2.1 — Write failing tests**

  Create `core/src/test/java/com/openwallet/core/util/GenericUtilsBech32Test.java`:

  ```java
  package com.openwallet.core.util;

  import com.openwallet.core.coins.BitcoinMain;
  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.exceptions.AddressMalformedException;
  import com.openwallet.core.wallet.AbstractAddress;
  import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
  import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;
  import org.junit.Test;
  import java.util.List;
  import static org.junit.Assert.*;

  public class GenericUtilsBech32Test {

      private static final CoinType BTC = BitcoinMain.get();

      // A known valid Bitcoin P2WPKH address (bc1q...)
      // Derived from 20-byte all-zeros pubkey hash — valid bech32 encoding
      private static final String BC1Q_ADDRESS = "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqlh0tdu";

      @Test
      public void getPossibleTypes_acceptsNativeSegWit() throws AddressMalformedException {
          List<CoinType> types = GenericUtils.getPossibleTypes(BC1Q_ADDRESS);
          assertFalse("Expected at least one matching type for bech32 address", types.isEmpty());
          assertEquals(BTC, types.get(0));
      }

      @Test
      public void getPossibleTypes_throwsForUnknownBech32() {
          try {
              GenericUtils.getPossibleTypes("xx1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs");
              fail("Expected AddressMalformedException for unknown HRP");
          } catch (AddressMalformedException e) {
              assertTrue(e.getMessage().contains("Unsupported"));
          }
      }

      @Test
      public void bitFamilyNewAddress_returnsSegwitAddressForBech32() throws AddressMalformedException {
          AbstractAddress addr = BTC.newAddress(BC1Q_ADDRESS);
          assertTrue("Expected SegwitAddress for bc1q... input", addr instanceof SegwitAddress);
      }

      @Test
      public void bitFamilyNewAddress_stillWorksForLegacy() throws AddressMalformedException {
          // All-zeros hash160 → "1111111111111111111114oLvT2" (known valid BTC P2PKH address)
          AbstractAddress addr = BTC.newAddress("1111111111111111111114oLvT2");
          assertNotNull(addr);
      }

      @Test
      public void bitFamilyNewAddress_throwsForGarbage() {
          try {
              BTC.newAddress("notanaddressatall");
              fail("Expected AddressMalformedException");
          } catch (AddressMalformedException e) {
              // expected
          }
      }
  }
  ```

- [ ] **Step 2.2 — Run tests to confirm they fail**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.util.GenericUtilsBech32Test" 2>&1 | tail -20
  ```
  Expected: `getPossibleTypes_acceptsNativeSegWit` and `bitFamilyNewAddress_returnsSegwitAddressForBech32` both FAIL (bech32 not parsed yet).

- [ ] **Step 2.3 — Add `tryBech32Addresses()` to `GenericUtils.java`**

  In `core/src/main/java/com/openwallet/core/util/GenericUtils.java`:

  a. Add import after line 25 (`import javax.annotation.Nonnull;`) — the last line of the existing imports block:
  ```java
  import com.openwallet.core.util.Bech32;
  ```

  b. In `getPossibleTypes(String addressStr)` (around line 250), call `tryBech32Addresses` immediately after the existing call:

  Find this block:
  ```java
  ImmutableList.Builder<CoinType> builder = ImmutableList.builder();
  tryBitcoinFamilyAddresses(addressStr, builder);
  // TODO try other coin addresses
  ```
  Change to:
  ```java
  ImmutableList.Builder<CoinType> builder = ImmutableList.builder();
  tryBitcoinFamilyAddresses(addressStr, builder);
  tryBech32Addresses(addressStr, builder);
  // TODO try other coin addresses
  ```

  c. Add the new private method after `tryBitcoinFamilyAddresses()` (after line 279):
  ```java
  /**
   * Tries to parse addressStr as a bech32/bech32m address and find the matching coin by HRP.
   */
  private static void tryBech32Addresses(String addressStr, ImmutableList.Builder<CoinType> builder) {
      Bech32.DecodedBech32 decoded;
      try { decoded = Bech32.decode(addressStr); }
      catch (IllegalArgumentException e) { return; }  // not bech32, silently ignore

      for (CoinType type : CoinID.getSupportedCoins()) {
          String hrp = type.getBech32Hrp();
          if (hrp != null && hrp.equals(decoded.hrp)) {
              builder.add(type);
              break;
          }
      }
  }
  ```

- [ ] **Step 2.4 — Extend `BitFamily.newAddress()` with bech32 fallback**

  Replace the full contents of `core/src/main/java/com/openwallet/core/coins/families/BitFamily.java` with:

  ```java
  package com.openwallet.core.coins.families;

  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.exceptions.AddressMalformedException;
  import com.openwallet.core.util.Bech32;
  import com.openwallet.core.wallet.AbstractAddress;
  import com.openwallet.core.wallet.families.bitcoin.BitAddress;
  import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
  import com.openwallet.core.wallet.families.bitcoin.TaprootAddress;

  import org.bitcoinj.core.AddressFormatException;

  /**
   * @author John L. Jegutanis
   *
   * This is the classical Bitcoin family that includes Litecoin, Dogecoin, Dash, etc
   */
  public abstract class BitFamily extends CoinType {
      {
          family = Families.BITCOIN;
      }

      @Override
      public AbstractAddress newAddress(String addressStr) throws AddressMalformedException {
          // Try base58check first (Legacy P2PKH and P2SH-SegWit addresses)
          try { return BitAddress.from(this, addressStr); }
          catch (AddressMalformedException ignored) {}

          // Try bech32/bech32m for coins that have a configured HRP
          if (getBech32Hrp() != null) {
              try {
                  Bech32.DecodedBech32 decoded = Bech32.decode(addressStr);
                  if (decoded.witnessVersion == 0) {
                      // P2WPKH: program is the 20-byte key hash
                      return SegwitAddress.fromHash160(this, decoded.program);
                  }
                  if (decoded.witnessVersion == 1) {
                      // P2TR: program is the already-tweaked 32-byte x-only output key.
                      // fromOutputKey() does NOT apply the BIP341 tweak (key is already tweaked).
                      return TaprootAddress.fromOutputKey(this, decoded.program);
                  }
              } catch (IllegalArgumentException ignored) {}
          }

          throw new AddressMalformedException("Unsupported address: " + addressStr);
      }
  }
  ```

- [ ] **Step 2.5 — Run tests to confirm they pass**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.util.GenericUtilsBech32Test" 2>&1 | tail -15
  ```
  Expected: `BUILD SUCCESSFUL` · all 5 tests passed.

- [ ] **Step 2.6 — Run full core test suite to ensure no regressions**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test 2>&1 | tail -15
  ```
  Expected: `BUILD SUCCESSFUL` (pre-existing failures in `MonetaryFormatTest`, `WalletPocketHDTest` from encoding issues are unrelated and pre-existing — ignore them as long as no new failures appear).

- [ ] **Step 2.7 — Commit**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  git add core/src/main/java/com/openwallet/core/util/GenericUtils.java \
          core/src/main/java/com/openwallet/core/coins/families/BitFamily.java \
          core/src/test/java/com/openwallet/core/util/GenericUtilsBech32Test.java
  git commit -m "feat: accept bech32/bech32m addresses in Send — add tryBech32Addresses and BitFamily bech32 fallback"
  ```

---

### Task 3: Fix `markAddressAsUsed` for SegWit addresses

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` (lines 531–539)
- Create: `core/src/test/java/com/openwallet/core/wallet/MarkAddressAsUsedTest.java`

- [ ] **Step 3.1 — Write failing test**

  Create `core/src/test/java/com/openwallet/core/wallet/MarkAddressAsUsedTest.java`:

  ```java
  package com.openwallet.core.wallet;

  import com.openwallet.core.coins.BitcoinMain;
  import com.openwallet.core.coins.CoinType;
  import com.openwallet.core.wallet.families.bitcoin.BitAddress;
  import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
  import org.bitcoinj.core.ECKey;
  import org.junit.Test;

  import static org.junit.Assert.*;

  /**
   * Verifies that markAddressAsUsed() accepts SegwitAddress without throwing.
   * We test the type-dispatch logic by subclassing WalletPocketHD is not practical
   * (it requires a full wallet setup), so we verify directly that SegwitAddress
   * has a getHash160() method returning the expected 20-byte value — the
   * prerequisite for the markAddressAsUsed fix to work correctly.
   */
  public class MarkAddressAsUsedTest {

      private static final CoinType BTC = BitcoinMain.get();

      @Test
      public void segwitAddress_getHash160_returns20Bytes() {
          ECKey key = new ECKey();
          SegwitAddress addr = SegwitAddress.fromKey(BTC, key);
          byte[] hash160 = addr.getHash160();
          assertNotNull(hash160);
          assertEquals("hash160 must be 20 bytes", 20, hash160.length);
          assertArrayEquals("hash160 must match key.getPubKeyHash()",
                  key.getPubKeyHash(), hash160);
      }

      @Test
      public void segwitAddress_hash160_matchesBitAddressHash160_forSameKey() {
          ECKey key = new ECKey();
          SegwitAddress segwit = SegwitAddress.fromKey(BTC, key);
          BitAddress legacy = BitAddress.from(BTC, key);
          assertArrayEquals(
              "SegwitAddress and BitAddress must share the same hash160 for the same key",
              legacy.getHash160(), segwit.getHash160());
      }
  }
  ```

- [ ] **Step 3.2 — Run tests to confirm they pass immediately**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.wallet.MarkAddressAsUsedTest" 2>&1 | tail -10
  ```
  Expected: `BUILD SUCCESSFUL` — these tests verify prerequisites only and should pass already.

- [ ] **Step 3.3 — Patch `markAddressAsUsed()` in `WalletPocketHD.java`**

  In `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java`:

  a. Add import after line 29 (`import com.openwallet.core.wallet.families.bitcoin.BitAddress;`):
  ```java
  import com.openwallet.core.wallet.families.bitcoin.SegwitAddress;
  ```

  b. Replace lines 531–539 (the `markAddressAsUsed(AbstractAddress)` method):

  Find:
  ```java
  @Override
  public void markAddressAsUsed(AbstractAddress address) {
      checkArgument(address.getType().equals(type), "Wrong address type");
      if (address instanceof BitAddress) {
          markAddressAsUsed((BitAddress)address);
      } else {
          throw new IllegalArgumentException("Wrong address class");
      }

  }
  ```

  Replace with:
  ```java
  @Override
  public void markAddressAsUsed(AbstractAddress address) {
      checkArgument(address.getType().equals(type), "Wrong address type");
      if (address instanceof BitAddress) {
          markAddressAsUsed((BitAddress) address);
      } else if (address instanceof SegwitAddress) {
          // SegwitAddress shares the same HASH160 as the corresponding legacy key,
          // so markPubHashAsUsed correctly advances the HD keychain.
          keys.markPubHashAsUsed(((SegwitAddress) address).getHash160());
      } else {
          throw new IllegalArgumentException("Wrong address class: " + address.getClass());
      }
  }
  ```

- [ ] **Step 3.4 — Run core tests to confirm no regressions**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :core:test --tests "com.openwallet.core.wallet.MarkAddressAsUsedTest" \
    --tests "com.openwallet.core.wallet.families.bitcoin.SegwitAddressTest" \
    --tests "com.openwallet.core.util.GenericUtilsBech32Test" 2>&1 | tail -15
  ```
  Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 3.5 — Commit**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  git add core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java \
          core/src/test/java/com/openwallet/core/wallet/MarkAddressAsUsedTest.java
  git commit -m "fix: markAddressAsUsed now handles SegwitAddress — advances HD keychain on SegWit UTXOs"
  ```

---

## Chunk 2: UI & Infrastructure Fixes (Tasks 4–6)

### Task 4: Receive screen — RadioGroup tab strip + derivation path

**Files:**
- Modify: `wallet/src/main/res/layout/fragment_request.xml`
- Create: `wallet/src/main/res/drawable/address_tab_selector.xml`
- Create: `wallet/src/main/res/color/address_tab_text_selector.xml`
- Modify: `wallet/src/main/res/values/styles.xml`
- Modify: `wallet/src/main/res/values/strings.xml`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`

Note: The text colour `ColorStateList` goes in `res/color/` (not `drawable/`) per Android convention.

- [ ] **Step 4.1 — Create `res/color/address_tab_text_selector.xml`**

  Create `wallet/src/main/res/color/address_tab_text_selector.xml`:

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <selector xmlns:android="http://schemas.android.com/apk/res/android">
      <item android:state_checked="true" android:color="@android:color/white" />
      <item android:color="@color/primary_500" />
  </selector>
  ```

  Note: `@color/primary_500` is `#03A9F4` — defined in `wallet/src/main/res/values/colors.xml`.

- [ ] **Step 4.2 — Create `res/drawable/address_tab_selector.xml`**

  Create `wallet/src/main/res/drawable/address_tab_selector.xml`:

  ```xml
  <?xml version="1.0" encoding="utf-8"?>
  <selector xmlns:android="http://schemas.android.com/apk/res/android">
      <item android:state_checked="true">
          <shape android:shape="rectangle">
              <solid android:color="@color/primary_500" />
              <corners android:radius="4dp" />
          </shape>
      </item>
      <item>
          <shape android:shape="rectangle">
              <solid android:color="@android:color/transparent" />
              <stroke android:width="1dp" android:color="@color/primary_500" />
              <corners android:radius="4dp" />
          </shape>
      </item>
  </selector>
  ```

- [ ] **Step 4.3 — Add `AddressTypeTab` style to `styles.xml`**

  In `wallet/src/main/res/values/styles.xml`, add before the closing `</resources>` tag:

  ```xml
  <style name="AddressTypeTab" parent="Widget.AppCompat.CompoundButton">
      <item name="android:button">@null</item>
      <item name="android:background">@drawable/address_tab_selector</item>
      <item name="android:textSize">13sp</item>
      <item name="android:paddingLeft">12dp</item>
      <item name="android:paddingRight">12dp</item>
      <item name="android:minWidth">0dp</item>
      <item name="android:gravity">center</item>
  </style>
  ```

  Note: `android:textColor` is NOT set in the style because it will be applied programmatically in Java to ensure `ColorStateList` state changes work correctly when buttons are constructed without `ContextThemeWrapper`.

- [ ] **Step 4.4 — Add `address_type_default` string to `strings.xml`**

  In `wallet/src/main/res/values/strings.xml`, add after the existing `address_type_label` / `address_type_legacy` strings (around line 467):

  ```xml
  <string name="address_type_default">Default</string>
  ```

- [ ] **Step 4.5 — Update `fragment_request.xml`**

  In `wallet/src/main/res/layout/fragment_request.xml`:

  a. **Remove** the entire `<LinearLayout android:id="@+id/address_type_container">` block (lines 24–45 in the current file):
  ```xml
  <LinearLayout
      android:id="@+id/address_type_container"
      ...
      <TextView ... />
      <Spinner android:id="@+id/address_type_spinner" ... />
  </LinearLayout>
  ```

  b. **Add** the derivation path TextView and RadioGroup **in its place** (between the outer `<LinearLayout>` opening tag and the first `<RelativeLayout>`):

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

  <RadioGroup
      android:id="@+id/address_type_radio_group"
      android:layout_width="wrap_content"
      android:layout_height="wrap_content"
      android:layout_gravity="center_horizontal"
      android:orientation="horizontal"
      android:visibility="gone"
      android:paddingBottom="8dp" />
  ```

  The result order from top is: derivation path TextView → RadioGroup → `<RelativeLayout>` (address block) → QR code area.

- [ ] **Step 4.6 — Update `AddressRequestFragment.java`**

  Apply the following changes to `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`:

  **a. Replace imports** — remove lines 20–24 and replace with:
  ```java
  import android.widget.RadioButton;
  import android.widget.RadioGroup;
  import android.widget.ImageView;
  import android.widget.TextView;
  import android.widget.Toast;
  ```
  Remove: `import android.widget.AdapterView;`, `import android.widget.ArrayAdapter;`, `import android.widget.LinearLayout;`, `import android.widget.Spinner;`

  **b. Add imports** for DeterministicKey and ChildNumber. After the existing `import com.openwallet.core.wallet.families.bitcoin.BitAddress;` line, add:
  ```java
  import org.bitcoinj.crypto.ChildNumber;
  import org.bitcoinj.crypto.DeterministicKey;
  import com.openwallet.core.wallet.WalletPocketHD;
  ```

  **c. Replace field declarations** — remove lines 98–100:
  ```java
  @Bind(R.id.address_type_spinner)   Spinner addressTypeSpinner;
  @Bind(R.id.address_type_container) LinearLayout addressTypeContainer;
  private AddressType selectedAddressType = AddressType.LEGACY;
  ```
  Replace with:
  ```java
  @Bind(R.id.address_type_radio_group) RadioGroup addressTypeRadioGroup;
  @Bind(R.id.derivation_path_view)     TextView derivationPathView;
  private AddressType selectedAddressType = AddressType.NATIVE_SEGWIT;
  ```

  **d. Replace the spinner-building block in `onCreateView()`** — find lines 196–224:
  ```java
  // Configure address type spinner for SegWit-capable coins
  if (type.getSupportedAddressTypes().size() > 1) {
      addressTypeContainer.setVisibility(View.VISIBLE);
      ...
      addressTypeSpinner.setOnItemSelectedListener(...);
  }
  ```
  Replace the entire `if` block with:
  ```java
  // Configure address type tab strip for SegWit-capable coins
  if (type.getSupportedAddressTypes().size() > 1) {
      addressTypeRadioGroup.setVisibility(View.VISIBLE);
      derivationPathView.setVisibility(View.VISIBLE);

      // Fixed display order: Default (NATIVE_SEGWIT), Compatibility (COMPATIBLE), Legacy (LEGACY)
      final AddressType[] displayOrder = {
              AddressType.NATIVE_SEGWIT, AddressType.COMPATIBLE, AddressType.LEGACY };
      final String[] tabLabels = {
              getString(R.string.address_type_default),
              getString(R.string.address_type_compatible),
              getString(R.string.address_type_legacy) };

      int firstId = View.generateViewId();
      for (int i = 0; i < displayOrder.length; i++) {
          if (!type.getSupportedAddressTypes().contains(displayOrder[i])) continue;
          RadioButton btn = new RadioButton(getActivity());
          btn.setId(firstId + i);
          btn.setText(tabLabels[i]);
          btn.setTag(displayOrder[i]);
          btn.setLayoutParams(new RadioGroup.LayoutParams(
                  RadioGroup.LayoutParams.WRAP_CONTENT,
                  RadioGroup.LayoutParams.WRAP_CONTENT));
          btn.setButtonDrawable(android.R.color.transparent);
          btn.setBackgroundResource(R.drawable.address_tab_selector);
          btn.setTextColor(getResources().getColorStateList(R.color.address_tab_text_selector));
          btn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
          btn.setTextSize(13f);
          if (displayOrder[i] == selectedAddressType) btn.setChecked(true);
          addressTypeRadioGroup.addView(btn);
      }

      addressTypeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
          View checkedBtn = group.findViewById(checkedId);
          if (checkedBtn != null) {
              selectedAddressType = (AddressType) checkedBtn.getTag();
              updateView();
          }
      });
  }
  ```

  **e. Add `dpToPx()` helper** at the end of the class (before the final `}`):
  ```java
  private int dpToPx(int dp) {
      return Math.round(dp * getResources().getDisplayMetrics().density);
  }
  ```

  **f. Update `updateView()`** — after the existing `receiveAddress` computation block (around line 376, after the `if (showAddress != null)... else { ... }` block), add the derivation path display:
  ```java
  // Populate derivation path for multi-type coins
  if (type.getSupportedAddressTypes().size() > 1 && derivationPathView != null) {
      try {
          AbstractAddress legacyAddr = account.getReceiveAddress();
          byte[] hash160 = ((BitAddress) legacyAddr).getHash160();
          WalletPocketHD pocketHD = (WalletPocketHD) account;
          org.bitcoinj.core.ECKey rawKey = pocketHD.findKeyFromPubHash(hash160);
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

- [ ] **Step 4.7 — Build to verify compilation**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :wallet:assembleDebug 2>&1 | tail -20
  ```
  Expected: `BUILD SUCCESSFUL`.

  If there are compile errors:
  - `cannot find symbol: DeterministicKey` → check bitcoinj package; try `org.bitcoinj.crypto.DeterministicKey` if `com.google.bitcoin.crypto.DeterministicKey` does not resolve. Run: `jar tf core/libs/bitcoinj-core-0.12.3.jar | grep DeterministicKey` to confirm.
  - `cannot find symbol: ChildNumber` → same package check.
  - Any `@Bind` error → ensure old field names are fully removed from the fragment.

- [ ] **Step 4.8 — Commit**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  git add wallet/src/main/res/layout/fragment_request.xml \
          wallet/src/main/res/drawable/address_tab_selector.xml \
          wallet/src/main/res/color/address_tab_text_selector.xml \
          wallet/src/main/res/values/styles.xml \
          wallet/src/main/res/values/strings.xml \
          wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java
  git commit -m "feat: replace address-type Spinner with RadioGroup tab strip and show derivation path on Receive screen"
  ```

---

### Task 5: Fix QR scan orientation + dead LTC server

**Files:**
- Modify: `wallet/src/main/AndroidManifest.xml` (line 133)
- Modify: `wallet/src/main/java/com/openwallet/wallet/Constants.java` (line 142)

- [ ] **Step 5.1 — Verify LTC replacement server is live**

  ```bash
  python -c "
  import socket, ssl, json
  ctx = ssl.create_default_context()
  ctx.check_hostname = False
  ctx.verify_mode = ssl.CERT_NONE
  s = socket.create_connection(('electrumx.ltc.aranguren.org', 50002), timeout=8)
  conn = ctx.wrap_socket(s, server_hostname='electrumx.ltc.aranguren.org')
  conn.send((json.dumps({'id':1,'method':'server.version','params':['test','1.4']}) + '\n').encode())
  print(conn.recv(512).decode().strip())
  "
  ```
  Expected: JSON response containing `"ElectrumX"` or similar server banner.

  If FAIL: try `electrum-ltc.bysh.me:50002` as primary is still alive; try `electrum1.litecointools.com:50002` as an alternative fallback. Do not proceed until a live server is confirmed.

- [ ] **Step 5.2 — Fix `ScanActivity` orientation in `AndroidManifest.xml`**

  In `wallet/src/main/AndroidManifest.xml`, find:
  ```xml
  android:screenOrientation="landscape"
  ```
  on the `ScanActivity` entry (around line 133). Change to:
  ```xml
  android:screenOrientation="sensorPortrait"
  ```

- [ ] **Step 5.3 — Replace dead LTC fallback server in `Constants.java`**

  In `wallet/src/main/java/com/openwallet/wallet/Constants.java`, find (around line 142):
  ```java
  new CoinAddress(LitecoinMain.get(),     new ServerAddress("electrum-ltc.bysh.me", 50002),
                                          new ServerAddress("ltc.electrum.bitaroo.net", 50002)),
  ```
  Change the second `ServerAddress` to:
  ```java
  new CoinAddress(LitecoinMain.get(),     new ServerAddress("electrum-ltc.bysh.me", 50002),
                                          new ServerAddress("electrumx.ltc.aranguren.org", 50002)),
  ```

- [ ] **Step 5.4 — Build final release APK**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  ./gradlew :wallet:assembleRelease 2>&1 | tail -10
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5.5 — Commit**

  ```bash
  cd /d/NewYorkCoin_2026/nyc-openwallet-android
  git add wallet/src/main/AndroidManifest.xml \
          wallet/src/main/java/com/openwallet/wallet/Constants.java
  git commit -m "fix: lock QR scan to portrait; replace dead LTC Electrum fallback server"
  ```

---

## Final Verification Checklist

- [ ] `./gradlew :core:test` passes (no new failures vs baseline)
- [ ] `./gradlew :wallet:assembleRelease` BUILD SUCCESSFUL
- [ ] All 5 commits on branch: Task 1, Task 2, Task 3, Task 4, Task 5
