# Multi-Address-Type Support Design
**Date:** 2026-03-27
**Project:** NYC OpenWallet Android
**Scope:** BTC, LTC, DGB, VTC — Legacy, Compatible (P2SH-SegWit), Native SegWit (bech32), Taproot (bech32m)

---

## Overview

Add live address-type selection to the Receive screen for BTC, LTC, DGB, and VTC. Users switch between Legacy, Compatible, Native SegWit, and Taproot formats on demand. A single BIP44 key chain is reused; the selected address type controls how the public key is encoded. BIP143 SegWit signing enables spending from Compatible and Native SegWit addresses. Taproot is receive-display only. ElectrumX address subscriptions are extended to cover all active SegWit address types so incoming SegWit UTXOs are tracked correctly.

---

## Approach

**Display-only re-encoding from a single BIP44 key chain, with extended ElectrumX subscriptions and BIP143 signing.**

The wallet retains one `WalletPocketHD` per coin. On the Receive screen a spinner lets the user pick an address type. The current receive key is re-encoded into the selected format. `getActiveAddresses()` is extended to emit all supported address types per key so ElectrumX monitors them all. Spending from SegWit inputs requires BIP143 signing, which bypasses `LocalTransactionSigner` for SegWit inputs.

This approach is not BIP49/84/86 derivation-path compliant (those specify separate account paths) but is functionally correct for receiving, balance tracking, and spending.

---

## Components

### 1. `Bech32` — new codec utility

**Location:** `core/src/main/java/com/openwallet/core/util/Bech32.java`

Implements BIP173 (bech32) and BIP350 (bech32m). No external dependencies. Provides:
- `encode(hrp, witnessVersion, data)` → `String`
- `decode(bech32String)` → `DecodedBech32` (hrp, witnessVersion, data, variant)

Bech32 vs bech32m is determined by the checksum constant: `1` for bech32, `0x2bc830a3` for bech32m. All output is lowercase — `addressSplitToGroupsMultiline()` must not alter case.

### 2. `AddressType` — new enum

**Location:** `core/src/main/java/com/openwallet/core/coins/AddressType.java`

```
LEGACY         — P2PKH, Base58Check
COMPATIBLE     — P2SH-P2WPKH, Base58Check P2SH encoding
NATIVE_SEGWIT  — P2WPKH, bech32 witness version 0
TAPROOT        — P2TR, bech32m witness version 1 (receive only)
```

### 3. `SegwitAddress` — new address class

**Location:** `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/SegwitAddress.java`

Implements `AbstractAddress`. Encodes a 20-byte key hash as bech32 with witness version 0 using the coin's HRP. Used for Native SegWit (P2WPKH) addresses (`bc1q...`, `ltc1q...`, etc.).

### 4. `TaprootAddress` — new address class

**Location:** `core/src/main/java/com/openwallet/core/wallet/families/bitcoin/TaprootAddress.java`

Implements `AbstractAddress`. Encodes a 32-byte tweaked output key as bech32m with witness version 1. Receive display only — no spending path in this release.

**Key-path tweak (BIP341):** Given compressed public key `P`, extract the 32-byte x-only encoding `bytes(P)`. Compute `t = int(hash_taptweak(bytes(P)))` where `hash_taptweak(x) = SHA256(SHA256("TapTweak") || SHA256("TapTweak") || x)`. Output key: `Q = lift_x(x(P)) + t·G`. Serialize `Q` as its 32-byte x-only encoding for the bech32m payload. This matches BIP341 §Script validation rules keypath spending.

### 5. `CoinType` extensions

**File:** `core/src/main/java/com/openwallet/core/coins/CoinType.java`

Two additions (both set only during construction — no runtime mutation, no threading issue):
- `protected Set<AddressType> supportedAddressTypes` — default `{LEGACY}`. BTC, LTC, DGB, VTC set to all four.
- `protected String bech32Hrp` — HRP for bech32/bech32m encoding. Null for non-SegWit coins.
- `public AbstractAddress addressFromKey(ECKey key, AddressType type)` — new overload:
  - `LEGACY` → `BitAddress.from(this, key.getPubKeyHash())`
  - `COMPATIBLE` → serialize redeem script `OP_0 <20-byte-hash>`, hash160 it, encode as P2SH using `p2shHeader`
  - `NATIVE_SEGWIT` → `SegwitAddress.fromKey(this, key)`
  - `TAPROOT` → `TaprootAddress.fromKey(this, key)`

The existing `addressFromKey(ECKey key)` remains unchanged — returns P2PKH — used by all existing code paths.

### 6. Coin HRP configuration

Each SegWit-capable coin class sets `bech32Hrp` in its constructor:

| Coin | HRP | Native SegWit example | Taproot example |
|---|---|---|---|
| Bitcoin | `bc` | `bc1q...` | `bc1p...` |
| Litecoin | `ltc` | `ltc1q...` | `ltc1p...` |
| DigiByte | `dgb` | `dgb1q...` | `dgb1p...` |
| Vertcoin | `vtc` | `vtc1q...` | `vtc1p...` |

### 7. ElectrumX address subscriptions — extended

**Files:** `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java`
**Method:** `getActiveAddresses()` (and related `getAddressesToWatch()` in `TransactionWatcherWallet`)

**Problem:** ElectrumX matches by `scripthash` (the SHA256 of the output script), not by key hash. A P2WPKH address (`bc1q...`) and the P2PKH address (`1...`) from the same key produce entirely different scripthashes and must be subscribed independently. Without this, incoming SegWit UTXOs are invisible to the wallet.

**Fix:** For SegWit-capable coins, `getActiveAddresses()` emits all supported address types per issued key:
```
for each issued ECKey:
    emit addressFromKey(key, LEGACY)
    if coin supports COMPATIBLE:  emit addressFromKey(key, COMPATIBLE)
    if coin supports NATIVE_SEGWIT: emit addressFromKey(key, NATIVE_SEGWIT)
    // TAPROOT: not subscribed (receive-display only, no spending)
```
This multiplies ElectrumX subscriptions by up to 3× for BTC/LTC/DGB/VTC. At typical issued key counts (lookahead 20), this is ~60 subscriptions per coin — within ElectrumX limits.

`GenericUtils.addressSplitToGroupsMultiline()` gets new branches for `SegwitAddress` and `TaprootAddress`. bech32 addresses split at the `1` separator (HRP | `1` | data), with a line break at the midpoint of the data portion. Case is preserved as lowercase.

### 8. BIP143 SegWit signing

**Files:** `core/src/main/java/com/openwallet/core/wallet/TransactionCreator.java`
`core/src/main/java/com/openwallet/core/wallet/SegwitTransactionSerializer.java` (new)

#### 8a. Bypassing `LocalTransactionSigner` for SegWit inputs

`TransactionCreator.signTransaction()` currently delegates all signing to `LocalTransactionSigner.signInputs()`. This must be changed:

1. Before calling `LocalTransactionSigner`, classify each input by its connected output's script type (P2PKH, P2SH, P2WPKH, P2SH-P2WPKH).
2. Collect SegWit inputs (P2WPKH and P2SH-P2WPKH) and sign them via the new `signSegwitInput()` path.
3. Mark those inputs as already-signed so `LocalTransactionSigner` skips them. Pass only P2PKH inputs to `LocalTransactionSigner` (existing path, unchanged).

This prevents double-signing and ensures SegWit inputs receive BIP143 sighashes while P2PKH inputs continue through the existing path unmodified.

#### 8b. `estimateBytesForSigning()` — P2SH support required

`TransactionCreator.estimateBytesForSigning()` currently throws `ScriptException("Wallet does not currently support PayToScriptHash")` for any P2SH output. P2SH-P2WPKH (Compatible) UTXOs are P2SH outputs. This method must be extended to handle P2SH-P2WPKH:

- Detect P2SH outputs whose redeem script is `OP_0 <20-byte-hash>` (P2SH-P2WPKH pattern)
- Estimate size: scriptSig is 23 bytes (redeem script push); witness is 108 bytes (sig + pubkey)
- Other P2SH types continue to throw (unchanged behaviour for non-SegWit P2SH)

#### 8c. BIP143 sighash computation

`signSegwitInput(tx, inputIndex, key, utxoValue, scriptCode)`:

1. Serialize the BIP143 preimage: `nVersion`, `hashPrevouts`, `hashSequence`, `outpoint`, `scriptCode`, `value`, `nSequence`, `hashOutputs`, `nLocktime`, `nHashType`
2. Double-SHA256 the preimage to get the sighash
3. Sign with `ECKey.sign(sighash)` → DER signature, append `SIGHASH_ALL` byte (0x01)
4. Build witness stack: `[signature, compressed-pubkey]`
5. For P2SH-P2WPKH: set `scriptSig` to a single push of the redeem script `OP_0 <hash160(pubkey)>`; for P2WPKH: leave `scriptSig` empty

**UTXO value source:** `signSegwitInput()` obtains the input value from `txInput.getConnectedOutput().getValue()`. This is the same path used by the existing `TransactionCreator` for fee calculation (line 99). ElectrumX `listunspent` returns value per UTXO and it is stored on `TransactionOutput.value` when UTXOs are attached. If `getConnectedOutput()` returns null for a SegWit input, signing aborts with a clear `IllegalStateException("Missing connected output value for SegWit input")` — surfaced to the user as a toast. The send flow is aborted; no silent failure.

#### 8d. Witness serialization — `SegwitTransactionSerializer`

Bitcoinj 0.12.3's `Transaction.bitcoinSerialize()` does not emit the witness marker, flag, or witness fields. `SegwitTransactionSerializer` wraps a fully-signed `Transaction` and produces BIP141-compliant serialization:

- After the 4-byte version: insert marker `0x00`, flag `0x01`
- Serialize inputs and outputs normally
- After outputs: for each input, serialize its witness stack (count + items)
- Append 4-byte locktime

**Size check fix:** `completeTx()` calls `tx.bitcoinSerialize().length` against `MAX_STANDARD_TX_SIZE`. For SegWit transactions, the correct size to check is the **non-witness** serialized size (vbytes = base size + witness size / 4, but for standard size limit purposes, only the stripped size matters for relay). The existing `bitcoinSerialize().length` check is therefore correct for the relay size limit — the witness data does not count toward the 100kB standard tx size. No change needed to the size guard.

`SegwitTransactionSerializer.serialize(Transaction tx)` self-detects whether witness serialization is needed: it checks whether any input has a non-empty witness stack. If none do, it delegates directly to `tx.bitcoinSerialize()` and returns the result unmodified — standard P2PKH transactions are never emitted with a witness marker or empty witness stacks. Only when at least one input carries witness data does it emit the BIP141 format (marker `0x00`, flag `0x01`, witness fields). This method replaces the `tx.bitcoinSerialize()` call in `BitBlockchainConnection.broadcastTransaction()` for all transaction types; the self-detection guard ensures legacy transactions are unaffected.

---

## Receive Screen UI

**File:** `wallet/src/main/java/com/openwallet/wallet/ui/AddressRequestFragment.java`

A `Spinner` is added above the QR code. Visibility: `GONE` when `coinType.getSupportedAddressTypes().size() == 1` (NYC, DOGE, ZEC see no change). For SegWit-capable coins the spinner items are:

- Legacy (`R...` / `1...` / `L...` / `D...`)
- Compatible (`3...` / `M...`)
- Native SegWit (`bc1q...` etc.)
- Taproot (`bc1p...` etc.) — subtitle: *"Receive only in this version"*

On selection: calls `coinType.addressFromKey(currentReceiveKey, selectedType)` → updates QR + label. The selected type is a fragment instance variable; it resets to `LEGACY` on each screen open.

The Send screen validates destination addresses: if the destination decodes as a bech32m (Taproot) address, the Send button is disabled with the message *"Taproot destinations not yet supported — use a Taproot-capable wallet to send here."*

---

## Coin Support Matrix

| Coin | Receive types | Spend types | ElectrumX subscriptions |
|---|---|---|---|
| BTC, LTC, DGB, VTC | Legacy, Compatible, Native SegWit, Taproot | Legacy, Compatible, Native SegWit | 3× per issued key |
| NYC, DOGE, ZEC | Legacy | Legacy | 1× per issued key (unchanged) |

---

## Testing

- Unit tests for `Bech32` encode/decode round-trips against BIP173/BIP350 reference vectors
- Unit tests for `addressFromKey(key, type)` for each type against known BTC/LTC mainnet vectors
- Unit test for BIP143 sighash against the P2WPKH reference vector in BIP143 Appendix A
- Unit test for `estimateBytesForSigning()` with a P2SH-P2WPKH output — must not throw
- Manual: receive BTC to `bc1q...` address on testnet; verify balance appears; sweep — verify valid broadcast
- Manual: Taproot address displays on receive screen with "Receive only" subtitle
- Manual: Send screen rejects `bc1p...` destination with clear error message
