# NYC OpenWallet Android — Phase 1 Coin Integration Design

**Date:** 2026-03-26
**Branch:** `feature/nyc-coin-integration`
**PRD Reference:** NewYorkCoin Core PRD v1.3, §5 (Deliverable B), §10.2 (Agent B prompt)
**Author:** Burrell Harper + Co. LLC / James Burrell

---

## Overview

Add NewYorkCoin (NYC) as the primary coin to the `nyc-openwallet-android` app (fork of `jamesburrell2/openwallet-android`), and add Zcash (ZEC) transparent-address support. NYC appears first in all coin lists and is the default coin. BTC, LTC, and DOGE retain their existing definitions unchanged.

The NYC ElectrumX server (`electrum.paywith.nyc:50002`) is not yet deployed. NYC server configuration is handled via a first-run dialog (Option A) — no hardcoded server default.

---

## Section 1: New Files

### `NewYorkCoinMain.java`
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
| `bip44Index` | `179` (SLIP-0044 confirmed) |
| `unitExponent` | `8` |
| `feeValue` | `value(0)` — NYC is fee-free |
| `spendableCoinbaseDepth` | `100` |
| `signedMessageHeader` | `"NewYorkCoin Signed Message:\n"` |

Singleton pattern: private constructor, `private static NewYorkCoinMain instance`, `public static synchronized CoinType get()`.

### `ZcashMain.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/ZcashMain.java`
**Extends:** `BitFamily`
**Scope:** Transparent (t-address) support only. Shielded (Sapling/Orchard) deferred to v2.1.

| Field | Value |
|-------|-------|
| `id` | `"zcash.main"` |
| `name` | `"Zcash"` |
| `symbol` | `"ZEC"` |
| `uriScheme` | `"zcash"` |
| `addressHeader` | `7352` (0x1CB8 → `t1...` prefix) |
| `p2shHeader` | `7357` (0x1CBD → `t3...` prefix) |
| `acceptableAddressCodes` | `{7352, 7357}` |
| `dumpedPrivateKeyHeader` | `128` (0x80) |
| `bip44Index` | `133` (SLIP-0044) |
| `unitExponent` | `8` |
| `feeValue` | `value(1000)` |
| `spendableCoinbaseDepth` | `100` |
| `signedMessageHeader` | `"Zcash Signed Message:\n"` |

### `NycServerConfigDialog.java`
**Path:** `wallet/src/main/java/com/openwallet/wallet/ui/NycServerConfigDialog.java`
**Type:** `DialogFragment`

Shown once when a user adds the NYC coin for the first time (triggered from `AddCoinsActivity` before wallet pocket creation).

- Single `EditText` with hint `electrum.paywith.nyc:50002` (blank by default — hint only)
- **Save** button: validates `host:port` format, persists to `SharedPreferences` via `Configuration.setNycElectrumServer()`, then proceeds to create the wallet pocket
- **Skip** button: proceeds without a server; NYC wallet is created but displays a "No server configured" badge with a tap-to-configure action in the NYC wallet screen
- Input validation: must match `hostname:port` pattern; port must be numeric 1–65535

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

**3. `SUPPORTED_COINS`** — NYC first, then BTC, LTC, DOGE, ZEC, then existing coins:
```java
public static final List<CoinType> SUPPORTED_COINS = ImmutableList.of(
    NewYorkCoinMain.get(),   // PRIMARY
    BitcoinMain.get(),
    LitecoinMain.get(),
    DogecoinMain.get(),
    ZcashMain.get(),
    // ... all existing coins follow
);
```

**4. `DEFAULT_COINS_SERVERS`** — add ZEC; **no NYC entry** (server configured at runtime):
```java
new CoinAddress(ZcashMain.get(),
    new ServerAddress("electrum.z.cash", 50002),
    new ServerAddress("electrum.z.cash", 50002)),
```

**5. `COINS_ICONS`**
```java
COINS_ICONS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), R.drawable.newyorkcoin);
COINS_ICONS.put(CoinID.ZCASH_MAIN.getCoinType(), R.drawable.zcash);
```

**6. `COINS_BLOCK_EXPLORERS`**
```java
COINS_BLOCK_EXPLORERS.put(CoinID.NEWYORKCOIN_MAIN.getCoinType(), "https://explorer.newyorkcoin.net/tx/%s");
COINS_BLOCK_EXPLORERS.put(CoinID.ZCASH_MAIN.getCoinType(), "https://explorer.zcha.in/transactions/%s");
```

### `Configuration.java`
Add two methods backed by `SharedPreferences`:
```java
public String getNycElectrumServer() // returns null if not set
public void setNycElectrumServer(String hostPort)
```
Key: `"nyc_electrum_server"`

### `wallet/build.gradle`
```groovy
applicationId "com.newyorkcoin.openwallet"
```

### `wallet/src/main/AndroidManifest.xml`
```xml
package="com.newyorkcoin.openwallet"
```

---

## Section 3: NYC Server Connection at Runtime

In the wallet's coin connection logic (where `DEFAULT_COINS_SERVERS` is consumed to build `CoinAddress` objects), add a conditional branch for NYC:

```java
if (coinType.equals(NewYorkCoinMain.get())) {
    String server = configuration.getNycElectrumServer();
    if (server != null && !server.isEmpty()) {
        // parse host:port and build CoinAddress dynamically
    }
    // else: no connection attempt, show "no server" badge
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

## Section 5: Branch Strategy & Success Criteria

**Branch:** `feature/nyc-coin-integration` from `main`

### Success Criteria (PRD §12, Phase 1)
- App compiles and installs on Android API 26+ (Android 8.0 Oreo)
- NYC appears first in the coin selection list and is the default coin
- Adding NYC for the first time triggers `NycServerConfigDialog`
- User can create wallet, generate a legacy `N...` NYC address, and view balance (once server configured)
- BTC, LTC, DOGE behavior unchanged — no regressions
- ZEC transparent `t1...` address generation works
- Package name is `com.newyorkcoin.openwallet`
- No analytics SDKs introduced; no modifications to existing BTC/LTC/DOGE/ZEC coin logic

### Out of Scope (deferred per PRD)
- bech32 `nyc1...` address support (post-SegWit activation)
- MWEB private send UI
- Atomic Swap UI
- ZEC shielded (Sapling/Orchard) addresses
- FCM push notifications
- iOS wallet
