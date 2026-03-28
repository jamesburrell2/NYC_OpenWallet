# Multi-Address-Type Support Design
**Date:** 2026-03-27
**Project:** NYC OpenWallet Android
**Scope:** BTC, LTC, DGB, VTC — Legacy, Compatible (P2SH-SegWit), Native SegWit (bech32), Taproot (bech32m)

---

## Overview

Add live address-type selection to the Receive screen for BTC, LTC, DGB, and VTC. Users can switch between Legacy, Compatible, Native SegWit, and Taproot formats on demand without changing wallet accounts. A single BIP44 key chain is reused; the selected address type controls how the public key is encoded. BIP143 SegWit signing is implemented to enable spending from Compatible and Native SegWit addresses. Taproot is receive-display only in this release.

---

## Approach

**Display-only re-encoding from a single BIP44 key chain (Approach A).**

The wallet retains one `WalletPocketHD` per coin (existing BIP44 account). On the Receive screen a spinner lets the user pick an address type. The current receive key is re-encoded into the selected format. No new accounts, no additional ElectrumX subscriptions for address monitoring. Spending from SegWit inputs is enabled by implementing BIP143 in `TransactionCreator`.

This is consistent with how Coinomi, Exodus, and Trust Wallet handled initial SegWit support. It is not BIP49/84/86 path-compliant (those specify separate derivation paths per address type) but is functionally correct for receiving and spending.

---

## Components

### 1. `Bech32` — new codec utility

**Location:** `core/src/main/java/com/openwallet/core/util/Bech32.java`

Implements BIP173 (bech32) and BIP350 (bech32m). No external dependencies. Provides:
- `encode(hrp, witnessVersion, data)` → `String`
- `decode(bech32String)` → `DecodedBech32` (hrp, witnessVersion, data)

The distinction between bech32 and bech32m is the checksum constant: `1` for bech32, `0x2bc830a3` for bech32m.

### 2. `AddressType` — new enum

**Location:** `core/src/main/java/com/openwallet/core/coins/AddressType.java`

```
LEGACY         — P2PKH, Base58Check (existing)
COMPATIBLE     — P2SH-P2WPKH, Base58Check P2SH encoding
NATIVE_SEGWIT  — P2WPKH, bech32 witness version 0
TAPROOT        — P2TR, bech32m witness version 1
```

### 3. `SegwitAddress` — new address class

**Location:** `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddress.java`

Implements `AbstractAddress`. Encodes a 20-byte key hash as bech32 with witness version 0 using the coin's HRP. Used for Native SegWit (P2WPKH) addresses.

### 4. `TaprootAddress` — new address class

**Location:** `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java`

Implements `AbstractAddress`. Encodes a 32-byte tweaked x-only public key as bech32m with witness version 1. Taproot key-path tweak: `P + hash_taptweak(P)·G` where `P` is the x-only public key. Receive display only — no spending path.

### 5. `CoinType` extensions

**File:** `core/src/main/java/com/openwallet/core/coins/CoinType.java`

Two additions:
- `protected Set<AddressType> supportedAddressTypes` — each coin declares supported types. Default: `{LEGACY}`. BTC, LTC, DGB, VTC set to all four.
- `public AbstractAddress addressFromKey(ECKey key, AddressType type)` — new overload. Switches on type:
  - `LEGACY` → existing `BitAddress.from(this, key.getPubKeyHash())`
  - `COMPATIBLE` → hash `OP_0 <key.getPubKeyHash()>` redeem script → `BitAddress.from(this_p2sh, scriptHash)` using `p2shHeader`
  - `NATIVE_SEGWIT` → `SegwitAddress.fromKey(this, key)`
  - `TAPROOT` → `TaprootAddress.fromKey(this, key)`

The zero-arg `addressFromKey(ECKey key)` is unchanged — continues to return P2PKH. All existing code paths are unaffected.

### 6. Coin HRP configuration

Each SegWit-capable coin declares its bech32 HRP in its coin definition class:

| Coin | HRP | Native SegWit example | Taproot example |
|---|---|---|---|
| Bitcoin | `bc` | `bc1q...` | `bc1p...` |
| Litecoin | `ltc` | `ltc1q...` | `ltc1p...` |
| DigiByte | `dgb` | `dgb1q...` | `dgb1p...` |
| Vertcoin | `vtc` | `vtc1q...` | `vtc1p...` |

### 7. `GenericUtils` extension

**File:** `core/src/main/java/com/openwallet/core/util/GenericUtils.java`

`addressSplitToGroupsMultiline()` gets new branches for `SegwitAddress` and `TaprootAddress`. bech32 addresses split at the separator character `1` (HRP | `1` | data). Groups of 4 chars after the separator with a line break at the midpoint.

### 8. BIP143 SegWit signing

**File:** `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java` + new `SegwitTransactionSerializer.java`

`TransactionCreator.signTransaction()` inspects each input's connected output script type:
- P2PKH → existing `hashForSignature()` path (unchanged)
- P2WPKH / P2SH-P2WPKH → new `signSegwitInput()`:
  1. Computes BIP143 sighash (commits to input value, outpoint, sequence, scriptCode)
  2. Signs with `ECKey.sign(sighash)`
  3. Builds witness stack `[signature || SIGHASH_ALL, compressed-pubkey]`
  4. For P2SH-P2WPKH: sets `scriptSig` to single push of redeem script `OP_0 <hash160(pubkey)>`

`SegwitTransactionSerializer` wraps `Transaction.bitcoinSerialize()` and injects:
- Marker byte `0x00` and flag byte `0x01` after version
- Witness fields per input after all inputs/outputs
- Recalculates final 4-byte locktime

Input value (required by BIP143) is already stored on `TransactionOutput` from ElectrumX `listunspent` — no additional network calls.

### 9. Receive screen UI

**File:** `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`

A `Spinner` is added above the QR code. Visibility: `GONE` for coins where `supportedAddressTypes.size() == 1` (NYC, DOGE, ZEC see no change). For SegWit-capable coins, the spinner shows:
- Legacy (`R...` / `1...` / `L...` / `D...`)
- Compatible (`3...` / `M...`)
- Native SegWit (`bc1q...` etc.)
- Taproot (`bc1p...` etc.) — with subtitle: *"Receive only in this version"*

On selection change: calls `coinType.addressFromKey(currentReceiveKey, selectedType)` and updates QR + label. The selected type is a fragment instance variable — resets to `LEGACY` on each screen open.

---

## Coin Support Matrix

| Coin | Receive types | Spend types |
|---|---|---|
| BTC, LTC, DGB, VTC | Legacy, Compatible, Native SegWit, Taproot | Legacy, Compatible, Native SegWit |
| NYC, DOGE, ZEC | Legacy | Legacy |

---

## What Is Not Changing

- `WalletPocketHD` — no new accounts, no path changes
- ElectrumX subscriptions — still subscribes to the BIP44 Legacy address only; incoming funds to SegWit addresses at the same key will be detected via the shared key hash
- Existing `addressFromKey(ECKey)` single-arg method — unchanged, returns P2PKH
- BTC, LTC, DOGE, DGB, VTC coin definitions — only `supportedAddressTypes` and `bech32Hrp` fields added

---

## Error Handling

- Invalid bech32 input to `Bech32.decode()` throws `IllegalArgumentException` caught at the UI layer
- BIP143 signing failure (null input value, unknown script type) falls back to a user-visible error toast and aborts the transaction — no silent failure
- Taproot send attempts blocked at the UI layer; the Send button is disabled when the destination address is a known bech32m address

---

## Testing

- Unit tests for `Bech32` encode/decode round-trips against BIP173/BIP350 reference vectors
- Unit tests for `addressFromKey(key, type)` for each type against known BTC/LTC vectors
- Unit test for BIP143 sighash computation against the P2WPKH reference vector in BIP143 appendix
- Manual: receive BTC to bc1q address on testnet, sweep with wallet — verify correct broadcast
- Manual: Taproot address displays correctly; Send screen rejects taproot destination with clear message
