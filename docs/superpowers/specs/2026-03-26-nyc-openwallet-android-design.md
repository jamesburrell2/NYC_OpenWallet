# NYC OpenWallet Android — Phase 1 Coin Integration Design

**Date:** 2026-03-26
**Branch:** `feature/nyc-coin-integration`
**PRD Reference:** NewYorkCoin Core PRD v1.3, §5 (Deliverable B), §10.2 (Agent B prompt)
**Author:** Burrell Harper + Co. LLC / James Burrell

---

## Overview

Add NewYorkCoin (NYC) as the primary coin to the `nyc-openwallet-android` app (fork of
`jamesburrell2/openwallet-android`), and add Zcash (ZEC) transparent-address support. NYC appears
first in all coin lists and is the default coin. BTC, LTC, and DOGE retain their existing
definitions unchanged.

The NYC ElectrumX server (`electrum.paywith.nyc:50002`) is not yet deployed. NYC server
configuration is handled via a first-run dialog (Option A) — no hardcoded server default.

---

## Section 1: New Files

### 1a. `NewYorkCoinMain.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/NewYorkCoinMain.java`
**Extends:** `BitFamily`

| Field | Value |
|-------|-------|
| `id` | `"nyc.main"` |
| `name` | `"NewYorkCoin"` |
| `symbol` | `"NYC"` |
| `uriScheme` | `"newyorkcoin"` |
| `addressHeader` | `60` (0x3C → `N...` prefix) |
| `p2shHeader` | `22` (0x16) |
| `acceptableAddressCodes` | `{60, 22}` |
| `dumpedPrivateKeyHeader` | `188` (0xBC) |
| `addressPrefix` | `""` (empty string — Bitcoin-family convention) |
| `bip44Index` | `179` (SLIP-0044 confirmed) |
| `unitExponent` | `8` |
| `feeValue` | `value(0)` — NYC is fee-free |
| `minNonDust` | `value(0)` — consistent with fee-free policy |
| `softDustLimit` | `value(0)` — no dust threshold |
| `softDustPolicy` | `SoftDustPolicy.NO_POLICY` |
| `feePolicy` | `FeePolicy.FLAT_FEE` — explicit; prevents "per KB" label in fee UI |
| `spendableCoinbaseDepth` | `100` |
| `signedMessageHeader` | `toBytes("NewYorkCoin Signed Message:\n")` |

Singleton pattern: private constructor, `private static NewYorkCoinMain instance`,
`public static synchronized CoinType get()`.

---

### 1b. `ZcashFamily.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/families/ZcashFamily.java`
**Extends:** `BitFamily`

Zcash transparent addresses use a 2-byte version prefix (t1: `0x1C 0xB8`, t3: `0x1C 0xBD`).
The existing `BitFamily` → `VersionedChecksummedBytes` stack hard-limits version bytes to < 256
and silently truncates on encode. A dedicated `ZcashFamily` is required.

**Implementation:** `ZcashFamily extends BitFamily` and overrides `newAddress(ECKey, boolean)` to
return a `ZcashAddress` instance instead of a `BitAddress` instance. Extending `BitFamily`
(rather than `CoinType` directly) is mandatory because the three wallet dispatch points in
`Wallet.java`, `ServerClients.java`, and `WalletProtobufSerializer.java` all use
`instanceof BitFamily` to route ZEC to the Bitcoin-family wallet pocket, ElectrumX connection,
and protobuf serialization stacks. No `family` field override is needed — `BitFamily`'s
instance initializer already sets `family = Families.BITCOIN`.

### 1c. `ZcashAddress.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/ZcashAddress.java`
**Extends:** `AbstractAddress` (or implements `Address`)

Two-byte-aware Base58Check address type:

- Constructor takes `(CoinType type, byte[] hash160)`. Version prefix is derived from `type.addressHeader` split into `(byte)(version >> 8)` and `(byte)(version & 0xFF)`.
- `toString()`: prepend both version bytes before the 20-byte hash160, compute double-SHA256 checksum over the 22-byte payload, Base58-encode the result (22 + 4 = 26 bytes; typically 35 characters for the `0x1CB8`/`0x1CBD` version bytes — do not validate by fixed length).
- `fromString(CoinType type, String address)`: Base58-decode, verify 4-byte checksum, extract version (first 2 bytes), match against `type.addressHeader` or `type.p2shHeader`.

This class is self-contained and does not modify any existing address or coin types.

### 1d. `ZcashMain.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/ZcashMain.java`
**Extends:** `ZcashFamily`
**Scope:** Transparent (t-address) support only. Shielded (Sapling/Orchard) deferred to v2.1.

| Field | Value |
|-------|-------|
| `id` | `"zcash.main"` |
| `name` | `"Zcash"` |
| `symbol` | `"ZEC"` |
| `uriScheme` | `"zcash"` |
| `addressHeader` | `7352` (0x1CB8 → `t1...` prefix; stored as int, 2 bytes: `0x1C`, `0xB8`) |
| `p2shHeader` | `7357` (0x1CBD → `t3...` prefix; 2 bytes: `0x1C`, `0xBD`) |
| `acceptableAddressCodes` | `{7352, 7357}` |
| `dumpedPrivateKeyHeader` | `128` (0x80) |
| `addressPrefix` | `""` |
| `bip44Index` | `133` (SLIP-0044) |
| `unitExponent` | `8` |
| `feeValue` | `value(1000)` |
| `minNonDust` | `value(1000)` |
| `softDustLimit` | `value(1000)` |
| `softDustPolicy` | `SoftDustPolicy.BASE_FEE_FOR_EACH_SOFT_DUST_TXO` |
| `spendableCoinbaseDepth` | `100` |
| `signedMessageHeader` | `toBytes("Zcash Signed Message:\n")` |

Note: `ZcashFamily.newAddress()` reads `addressHeader` and applies the 2-byte split. The int
value 7352 is never passed to `VersionedChecksummedBytes` — it is handled exclusively inside
`ZcashAddress`.

---

### 1e. `NycServerConfigDialog.java`
**Path:** `wallet/src/main/java/com/openwallet/wallet/ui/NycServerConfigDialog.java`
**Type:** `DialogFragment`

Shown once when a user adds the NYC coin for the first time (triggered from `AddCoinsActivity`
before wallet pocket creation).

- Single `EditText` with hint `electrum.paywith.nyc:50002` (blank by default — hint only)
- **Save** button: validates `host:port` format (regex `^[^:]+:\d{1,5}$`, port 1–65535),
  persists to `SharedPreferences` via `Configuration.setNycElectrumServer()`, then calls the
  caller's callback to proceed with wallet pocket creation
- **Skip** button: calls callback with `null` server; wallet pocket is created without a server
- Implements interface `OnNycServerConfiguredListener` with method
  `onNycServerConfigured(@Nullable String hostPort)`; `AddCoinsActivity` implements this interface

**"No server" badge — UI specification:**
When `Configuration.getNycElectrumServer()` returns `null`:

- **Target file:** `wallet/src/main/java/com/openwallet/wallet/ui/WalletFragment.java` (the per-coin
  wallet detail screen) — add a `TextView` or `Chip` view (id: `no_server_banner`) to the
  fragment layout (`wallet/src/main/res/layout/fragment_wallet.xml`) with text
  "No ElectrumX server configured — tap to set up"
- **Visibility:** `VISIBLE` when `coinType == NewYorkCoinMain.get() && getNycElectrumServer() == null`;
  `GONE` otherwise
- **Tap action:** calls `NycServerConfigDialog.show(getChildFragmentManager(), "nyc_server")`
- **Persistence:** banner re-evaluates on `onResume()` so it disappears immediately after the
  user saves a server address
- Banner is shown for NYC only — zero impact on BTC/LTC/DOGE/ZEC wallet screens

---

## Section 2: Changes to Existing Files

### `CoinID.java`
Add two entries. NYC must be **first** in the enum so it takes priority when resolving URI schemes:

```java
NEWYORKCOIN_MAIN(NewYorkCoinMain.get()),  // first — primary coin
ZCASH_MAIN(ZcashMain.get()),
BITCOIN_MAIN(BitcoinMain.get()),
// ... existing entries unchanged
```

### `Constants.java`

**1. `DEFAULT_COIN`**
```java
public static final CoinType DEFAULT_COIN = NewYorkCoinMain.get();
```

**2. `DEFAULT_COINS`**
```java
public static final List<CoinType> DEFAULT_COINS = ImmutableList.of((CoinType) NewYorkCoinMain.get());
```

**3. `SUPPORTED_COINS`** — NYC first, then BTC, LTC, DOGE, ZEC, then all existing coins (unchanged order among existing entries):
```java
public static final List<CoinType> SUPPORTED_COINS = ImmutableList.of(
    NewYorkCoinMain.get(),   // PRIMARY
    BitcoinMain.get(),
    LitecoinMain.get(),
    DogecoinMain.get(),
    ZcashMain.get(),
    // ... all existing coins follow in their current order
);
```

**4. `DEFAULT_COINS_SERVERS`** — append ZEC entry inside the existing `ImmutableList.of(...)` call.
Do **not** replace the list — add alongside existing entries. **No NYC entry** (server configured at runtime):
```java
new CoinAddress(ZcashMain.get(),
    new ServerAddress("electrum.z.cash", 50002),
    new ServerAddress("electrum2.z.cash", 50002)),
```
Primary: `electrum.z.cash:50002`; fallback: `electrum2.z.cash:50002` (distinct hosts).

**5. `COINS_ICONS`** — add inside existing `static { }` block:
```java
COINS_ICONS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), R.drawable.newyorkcoin);
COINS_ICONS.put(CoinID.ZCASH_MAIN.getCoinType(), R.drawable.zcash);
```

**6. `COINS_BLOCK_EXPLORERS`** — add inside existing `static { }` block:
```java
COINS_BLOCK_EXPLORERS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), "https://explorer.newyorkcoin.net/tx/%s");
COINS_BLOCK_EXPLORERS.put(CoinID.ZCASH_MAIN.getCoinType(), "https://explorer.zcha.in/transactions/%s");
```

### `Configuration.java`
Add two methods backed by `SharedPreferences`:
```java
@Nullable
public String getNycElectrumServer() {
    return prefs.getString("nyc_electrum_server", null);
}

public void setNycElectrumServer(String hostPort) {
    prefs.edit().putString("nyc_electrum_server", hostPort).apply();
}
```

### `wallet/build.gradle`
```groovy
applicationId "com.newyorkcoin.openwallet"
compileSdkVersion 35
minSdkVersion 26
targetSdkVersion 35
```
Note: existing `compile` dependency declarations must be migrated to `implementation` (required
by AGP 3+ under SDK 35).

### `wallet/src/main/AndroidManifest.xml`
```xml
package="com.newyorkcoin.openwallet"
```

### `wallet/src/main/java/com/openwallet/wallet/ui/WalletFragment.java`
Add "no server" banner logic per Section 1e: visibility driven by
`configuration.getNycElectrumServer() == null && coinType == NewYorkCoinMain.get()`,
evaluated on `onResume()`. Tap opens `NycServerConfigDialog`.

### `wallet/src/main/res/layout/fragment_wallet.xml`
Add `TextView` (id: `no_server_banner`) for the "No ElectrumX server configured — tap to set up"
banner. Visibility controlled programmatically from `WalletFragment`.

---

## Section 3: NYC Server Connection at Runtime

In the wallet's coin connection logic (where `DEFAULT_COINS_SERVERS` is consumed to build
`CoinAddress` objects at startup), add a conditional branch for NYC:

```java
if (coinType.equals(NewYorkCoinMain.get())) {
    String server = configuration.getNycElectrumServer();
    if (server != null && !server.isEmpty()) {
        String host = server.substring(0, server.lastIndexOf(':'));
        int port = Integer.parseInt(server.substring(server.lastIndexOf(':') + 1));
        // build CoinAddress and add to connection list
    }
    // else: skip — WalletFragment shows the "no server" banner
    continue;
}
```

No changes to BTC, LTC, DOGE, or ZEC connection logic.

---

## Section 4: Icon Assets

Source: `D:\NewYorkCoin_v2\Main-File-JPEG_200x200.jpg` → converted to PNG, resized per density.

| Drawable folder | Size | Filename |
|-----------------|------|----------|
| `drawable-mdpi` | 32×32 | `newyorkcoin.png` |
| `drawable-hdpi` | 48×48 | `newyorkcoin.png` |
| `drawable-xhdpi` | 64×64 | `newyorkcoin.png` |
| `drawable-xxhdpi` | 96×96 | `newyorkcoin.png` |
| `drawable-xxxhdpi` | 128×128 | `newyorkcoin.png` |

ZEC: download standard Zcash brand PNG, resize to same density set as `zcash.png`.

---

## Section 5: Unit Tests

**`NewYorkCoinTest.java`**
Path: `core/src/test/java/com/openwallet/core/coins/NewYorkCoinTest.java`
Pattern: follow `LitecoinTest.java`. Assert that a known BIP44 seed derivation at path
`m/44'/179'/0'/0/0` produces a valid `N...` address with the correct P2PKH prefix (0x3C = 60).

**`ZcashTest.java`**
Path: `core/src/test/java/com/openwallet/core/coins/ZcashTest.java`
Assert:
1. A known seed at `m/44'/133'/0'/0/0` produces a `t1...` address (first char after `t` is `1`).
2. `ZcashAddress.fromString(ZcashMain.get(), address).toString()` round-trips correctly.
3. A P2SH address encodes to `t3...` prefix.
4. An address with version 0x1C 0xB8 decodes without error; a version 0x1C 0xBD decodes as P2SH.

These tests must pass before the branch is considered ready for review.

---

## Section 6: Branch Strategy & Success Criteria

**Branch:** `feature/nyc-coin-integration` from `main`

### Success Criteria (PRD §12, Phase 1)
- App compiles with `compileSdkVersion 35`, `minSdkVersion 26`; `applicationId` is `com.newyorkcoin.openwallet`
- NYC appears first in the coin selection list and is the default coin
- Adding NYC for the first time triggers `NycServerConfigDialog`
- After saving a server address, NYC connects to ElectrumX; wallet screen shows balance
- Skipping server config shows the "No ElectrumX server configured" banner in the NYC wallet screen; tapping reopens the dialog
- User can generate a legacy `N...` NYC address
- BTC, LTC, DOGE behavior unchanged — no regressions
- ZEC transparent `t1...` address generation works; `ZcashTest` passes
- `NewYorkCoinTest` and `ZcashTest` pass

### Out of Scope (deferred per PRD)
- bech32 `nyc1...` address support (post-SegWit activation)
- MWEB private send UI
- Atomic Swap UI
- ZEC shielded (Sapling/Orchard) addresses (requires ZIP-32 + lightwalletd, deferred to v2.1)
- FCM push notifications
- iOS wallet
