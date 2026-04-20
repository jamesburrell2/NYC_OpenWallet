# NYC OpenWallet Android — Multi-Protocol Roadmap
## Phases 2–4: Ethereum, Solana, and Zcash Shielded Addresses

**Date:** 2026-03-27
**Author:** Burrell Harper + Co. LLC / James Burrell
**Supersedes:** `2026-03-26-nyc-openwallet-android-design.md` (Phase 1 remains unchanged)
**Branch strategy:** Each phase is a separate feature branch from `main` after the prior phase is merged.

---

## Background: Existing Architecture (Phase 1 Baseline)

Phase 1 (branch `feature/nyc-coin-integration`) adds NYC as the primary coin and ZEC transparent
addresses. It established the architectural extension points that all subsequent phases must follow.

The app has three required extension points for every new coin family:

| Layer | Interface | Bitcoin example | NXT example |
|---|---|---|---|
| Coin descriptor | `CoinType extends NetworkParameters` | `BitcoinMain` | `NxtMain` |
| Wallet account | `AbstractWallet<T,A> implements WalletAccount<T,A>` | `WalletPocketHD` | `NxtFamilyWallet` |
| Network client | `BlockchainConnection<T>` | `ServerClient` (Stratum/TCP) | `NxtServerClient` (HTTP polling) |

New families must also add an entry to the `Families` enum and a dispatch branch in
`Wallet.createAndAddAccount()`.

The `ServerClients` dispatcher routes coins by `instanceof` check on the family class. The
`ServerClients.startAsync()` method now guards against unconfigured servers with `hasAddress()`
(added in Phase 1 crash fix), enabling coins with runtime-configured servers to coexist with
coins that have hardcoded defaults.

---

## Why These Protocols Require New Families

| Property | Bitcoin-family (BTC, NYC, ZEC-t) | Ethereum | Solana | Zcash Shielded |
|---|---|---|---|---|
| Ledger model | UTXO | Account/balance | Account/balance | Note-based (shielded pool) |
| Key curve | secp256k1 | secp256k1 | Ed25519 | Jubjub (Sapling) / Pallas (Orchard) |
| HD derivation | BIP32 | BIP32 + BIP44 | **SLIP-0010** | **ZIP-32** |
| Address encoding | Base58Check | Keccak-256 + EIP-55 hex | Base58 (no checksum) | Bech32 / Bech32m |
| Network protocol | Electrum/Stratum TCP | JSON-RPC HTTP/WS | JSON-RPC HTTP/WS | gRPC (lightwalletd) |
| Existing Java lib | bitcoinj (bundled) | SpongyCastle (partial) | SpongyCastle | zcash-android-wallet-sdk (Rust JNI) |

---

---

# Phase 2: Ethereum (ETH) Support

**Branch:** `feature/ethereum-integration`
**Target:** NYC OpenWallet v1.1.0

---

## Phase 2 — Section 1: Coin Descriptor

### `EthereumMain.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/EthereumMain.java`
**Extends:** `EthFamily`

| Field | Value |
|---|---|
| `id` | `"ethereum.main"` |
| `name` | `"Ethereum"` |
| `symbol` | `"ETH"` |
| `uriScheme` | `"ethereum"` (EIP-681) |
| `bip44Index` | `60` (SLIP-0044) |
| `unitExponent` | `18` (1 ETH = 10^18 Wei) |
| `chainId` | `1` (stored as extra field; passed into every signed tx) |
| `feePolicy` | `FeePolicy.FEE_PER_GAS` (new enum value — see §2.6) |

Also create `EthereumSepolia.java` (`id="ethereum.sepolia"`, `chainId=11155111`) and
`EthereumHolesky.java` (`id="ethereum.holesky"`, `chainId=17000`) for testnets.
Do **not** implement Goerli — deprecated March 2024.

### `EthFamily.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/families/EthFamily.java`

Marker class. Add `ETHEREUM("ethereum")` to `Families` enum.

```java
public abstract class EthFamily extends CoinType {
    { family = Families.ETHEREUM; }
}
```

---

## Phase 2 — Section 2: Address

### `EthAddress.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/ethereum/EthAddress.java`
**Implements:** `AbstractAddress`

**Derivation from EC key:**
1. Take the uncompressed 64-byte public key body (strip `0x04` prefix).
2. Compute `Keccak-256` (not SHA3-256) over those 64 bytes via SpongyCastle:
   `new KeccakDigest(256)` — already in dependency `spongycastle:core:1.51.0.0`.
3. Take the last 20 bytes of the 32-byte digest.

**EIP-55 mixed-case checksum encoding (`toString()`):**
1. Hex-encode the 20 bytes lowercase.
2. Compute `Keccak-256` of that lowercase hex string.
3. For each character at index `i`: if nibble `i` of the hash is `>= 8`, uppercase; else lowercase.
4. Prepend `"0x"`.

**Validation (`fromString()`):** Accept both checksummed and all-lowercase 42-character strings.
Verify EIP-55 checksum if mixed-case is present. Throw `AddressMalformedException` on length
mismatch or invalid checksum.

**Keccak-256 note:** `MessageDigest.getInstance("SHA3-256")` produces NIST SHA3, which differs
from Ethereum's Keccak-256. Always use SpongyCastle's `KeccakDigest(256)` for Ethereum addresses.
This is safe on `minSdk 26` — no API-level restriction.

---

## Phase 2 — Section 3: Transaction

### `EthTransaction.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/ethereum/EthTransaction.java`
**Implements:** `AbstractTransaction`

Stores the signed EIP-1559 (Type 2) transaction fields:

| Field | Java type | Notes |
|---|---|---|
| `chainId` | `long` | From `EthereumMain.chainId` |
| `nonce` | `long` | Account transaction count |
| `maxPriorityFeePerGas` | `BigInteger` | Wei; tip to validator |
| `maxFeePerGas` | `BigInteger` | Wei; total fee cap |
| `gasLimit` | `long` | 21000 for ETH transfer; more for ERC-20 |
| `to` | `EthAddress` | Recipient |
| `value` | `BigInteger` | Wei |
| `data` | `byte[]` | Empty for ETH transfer; ABI data for contracts |
| `v`, `r`, `s` | `int`, `BigInteger`, `BigInteger` | Signature; `v` ∈ {0,1} |
| `txHash` | `byte[32]` | Keccak-256 of the signed serialized tx |
| `blockNumber` | `long` | -1 if pending |
| `gasUsed` | `long` | From receipt; -1 until confirmed |

`getHash()` returns a `Sha256Hash` wrapping the 32-byte Keccak tx hash. Semantically it is
Keccak, not SHA-256, but `Sha256Hash` is used as a 32-byte container throughout the app — this
is the same pattern used for Solana (see Phase 3).

### `RlpEncoder.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/ethereum/RlpEncoder.java`

Self-contained ~200-line RLP encoder. No external dependency. Required for EIP-1559 transaction
serialization. Must handle: empty byte string, single byte, byte array, list of items, and
`BigInteger` (serialize as minimal big-endian, stripping leading zeros).

### EIP-1559 signing flow (`EthTransactionSigner`):
1. Assemble fields: `[chainId, nonce, maxPriorityFeePerGas, maxFeePerGas, gasLimit, to, value, data, accessList=[]]`.
2. RLP-encode.
3. Prepend type byte `0x02`.
4. Compute `Keccak-256` of the result — this is the signing hash.
5. Sign with SpongyCastle `ECDSASigner` over secp256k1.
6. Extract `r`, `s`; set `v` to recovery bit (0 or 1).
7. Re-RLP-encode: `0x02 || RLP([chainId, nonce, maxPriority, maxFee, gasLimit, to, value, data, accessList, v, r, s])`.

### `EthValue` — BigInteger-safe value type
The existing `Value` class stores amounts as `long` (max ~9.2 ETH in Wei). Any account over
9.2 ETH overflows. Create `EthValue extends Value` overriding `toBigInteger()` and
`toFriendlyString()` using `BigInteger` arithmetic. `unitExponent = 18`; display as ETH with
up to 6 decimal places.

---

## Phase 2 — Section 4: Network Client

### `EthServerClient.java`
**Path:** `core/src/main/java/com/openwallet/core/network/EthServerClient.java`
**Implements:** `BlockchainConnection<EthTransaction>`

Protocol: Ethereum JSON-RPC over HTTPS. Template: `NxtServerClient` (OkHttp 2.x polling).

**Server address model:** `EthServerAddress extends ServerAddress` adding a full URL field.
Default: `https://cloudflare-eth.com` (public, no API key). For production, a provider URL
(Infura, Alchemy, QuickNode) is user-configurable in `Configuration` (same pattern as
`getNycElectrumServer()`).

**Required RPC calls and their Stratum analogues:**

| Stratum (existing) | Ethereum JSON-RPC | Purpose |
|---|---|---|
| `blockchain.address.subscribe` | Poll `eth_getBalance` every 30s | Balance updates |
| `blockchain.address.get_history` | `eth_getTransactionCount` + `eth_getLogs` + block scan | Tx history |
| `blockchain.transaction.broadcast` | `eth_sendRawTransaction` | Broadcast |
| `blockchain.headers.subscribe` | `eth_blockNumber` + `eth_getBlockByNumber` | Block height / `baseFeePerGas` |
| (no equivalent) | `eth_getTransactionReceipt` | Confirmation status |
| (no equivalent) | `eth_estimateGas` | Gas estimation for non-ETH txs |

**Polling loop (foreground):**
- Every 30 seconds: `eth_getBalance` → compare to cached value → if changed, fire `onAddressStatusChanged`.
- Every new block (detected via `eth_blockNumber` polling): scan for incoming plain ETH transfers
  by calling `eth_getBlockByNumber(blockNum, true)` and filtering `tx.to == ownAddress`.
- For ERC-20: `eth_getLogs` with `Transfer(address,address,uint256)` topic filter.

**Incoming ETH detection note:** Ethereum JSON-RPC has no address push-subscription equivalent
to Electrum's `blockchain.address.subscribe`. Plain ETH incoming transfers are NOT detectable
via `eth_getLogs` — they require block scanning. The polling loop must check every new block.
WebSocket `eth_subscribe` (newHeads) is a v2 upgrade that eliminates polling overhead.

**OkHttp version conflict:** web3j requires OkHttp 4.x; the existing Stratum client uses
`com.squareup.okhttp:okhttp:2.7.5` (OkHttp 2.x, different package namespace `com.squareup.okhttp`
vs `okhttp3`). Both can coexist. The `EthServerClient` should use OkHttp 4.x
(`implementation 'com.squareup.okhttp3:okhttp:4.12.0'`) while the Stratum client keeps 2.7.5.

---

## Phase 2 — Section 5: Wallet Account

### `EthWallet.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/ethereum/EthWallet.java`
**Extends:** `AbstractWallet<EthTransaction, EthAddress>`

Key differences from `WalletPocketHD` (Bitcoin UTXO wallet):

- **No UTXO model.** Balance = single `BigInteger` (Wei). No coin selection, no change addresses,
  no look-ahead window.
- **Single address per account.** One `EthAddress` at path `m/44'/60'/account'/0/0`. Ethereum
  uses address reuse by convention.
- **Nonce management.** Cache the last known nonce from `eth_getTransactionCount`. Increment
  locally for each broadcast transaction; resync from network on reconnect.
- **Gas estimation.** `getSendRequest()` must call `eth_estimateGas` for non-ETH transfers.
  For simple ETH transfers, use `gasLimit = 21000` (exact, deterministic).

**`sendCoinsOffline(EthSendRequest)` flow:**
1. Fetch nonce from `eth_getTransactionCount(address, "pending")`.
2. Fetch `baseFeePerGas` from `eth_getBlockByNumber("latest", false)`.
3. Set `maxPriorityFeePerGas` = 1.5 Gwei (configurable default).
4. Set `maxFeePerGas` = `2 * baseFeePerGas + maxPriorityFeePerGas`.
5. Sign with `EthTransactionSigner`.
6. Broadcast via `EthServerClient.broadcastTransaction()`.

### `EthSendRequest.java`
Extends `SendRequest`. Adds: `gasLimit (long)`, `maxFeePerGas (BigInteger)`,
`maxPriorityFeePerGas (BigInteger)`, `data (byte[])`.

---

## Phase 2 — Section 6: Changes to Existing Files

### `Families.java`
```java
ETHEREUM("ethereum"),
```

### `Wallet.java` — `createAndAddAccount()`
```java
} else if (coinType instanceof EthFamily) {
    newAccount = new EthWallet(coinType, keys, seed);
}
```

### `ServerClients.java` — `getConnection()`
```java
} else if (type instanceof EthFamily) {
    EthServerClient client = new EthServerClient(addresses.get(type), connectivityHelper);
    client.setCacheDir(cacheDir, cacheSize);
    connections.put(type, client);
    return client;
}
```

### `Constants.java`
Add to `DEFAULT_COINS_SERVERS`:
```java
new EthCoinAddress(EthereumMain.get(), "https://cloudflare-eth.com"),
new EthCoinAddress(EthereumSepolia.get(), "https://rpc.sepolia.org"),
```

Add to `SUPPORTED_COINS` (after ZEC, before remaining coins):
```java
EthereumMain.get(),
```

Add to `COINS_ICONS`:
```java
COINS_ICONS.put(CoinID.ETHEREUM_MAIN.getCoinType(), R.drawable.ethereum);
```

Add to `COINS_BLOCK_EXPLORERS`:
```java
COINS_BLOCK_EXPLORERS.put(CoinID.ETHEREUM_MAIN.getCoinType(), "https://etherscan.io/tx/%s");
```

### `CoinID.java`
```java
ETHEREUM_MAIN(EthereumMain.get()),
ETHEREUM_SEPOLIA(EthereumSepolia.get()),
```

### `FeePolicy.java` (or `CoinType.java`)
Add `FEE_PER_GAS` to the `FeePolicy` enum. UI fee display should show "Network fee: X Gwei"
for Ethereum instead of "X BTC/kB".

### `wallet/build.gradle`
```groovy
implementation 'com.squareup.okhttp3:okhttp:4.12.0'   // for EthServerClient
```
Note: Keep existing `com.squareup.okhttp:okhttp:2.7.5` for StratumJ. Both coexist.

---

## Phase 2 — Section 7: ERC-20 Token Support (deferred to v1.2)

ERC-20 tokens share the parent ETH address and private key. Each token is a `TokenType`
descriptor carrying `contractAddress`, `decimals`, `symbol`, `name`, `chainId`. Each token
account is a separate `WalletAccount` backed by the same `EthWallet` key — this fits the
existing multi-account model with minimal interface change.

Required RPC additions: `eth_call` for `balanceOf()`, `eth_estimateGas` for `transfer()`,
`eth_getLogs` for `Transfer` event monitoring. Deferred to Phase 2b.

---

## Phase 2 — Section 8: Unit Tests

**`EthereumTest.java`** (`core/src/test/`):
1. Known seed at `m/44'/60'/0'/0/0` produces the correct EIP-55 checksummed address.
2. `EthAddress.fromString()` round-trips with EIP-55 encoding.
3. `EthAddress.fromString()` accepts all-lowercase address.
4. RLP encoding of a known EIP-1559 tx matches a reference hex string.
5. Signed transaction hash matches a known test vector.
6. `EthValue(BigInteger.valueOf(1_000_000_000_000_000_000L)).toFriendlyString()` returns `"1.00 ETH"`.

---

## Phase 2 — Section 9: Success Criteria

- App compiles and all existing tests pass.
- Ethereum appears in coin selection after ZEC.
- HD address derivation at `m/44'/60'/0'/0/0` matches MetaMask / hardware wallet output for the
  same seed.
- Balance is fetched from RPC and displayed correctly in Wei/ETH.
- ETH send transaction is constructed, signed, and broadcast; confirmed on Sepolia testnet.
- Incoming ETH detected within one polling interval (≤ 30 seconds, app foregrounded).
- No regressions on NYC, BTC, LTC, DOGE, ZEC.

---

---

# Phase 3: Solana (SOL) Support

**Branch:** `feature/solana-integration`
**Target:** NYC OpenWallet v1.2.0
**Prerequisite:** Phase 2 merged and tagged.

---

## Phase 3 — Section 1: Key Insight — SLIP-0010 vs BIP32

Solana uses **Ed25519** keys. The existing `HDKeyDerivation` (bitcoinj, BIP32) operates on
secp256k1. It **cannot** derive valid Solana keys. A separate SLIP-0010 derivation must be
implemented.

**Derivation path:** `m / 44' / 501' / account' / 0'` (all hardened — SLIP-0010 Ed25519
supports only hardened child derivation).

**SLIP-0010 algorithm:**
```
master = HMAC-SHA512(key="ed25519 seed", data=seed_bytes_64)
left_32 = master[0..32]   // master private key
right_32 = master[32..64] // master chain code

// For each hardened level at index i (i | 0x80000000):
child = HMAC-SHA512(key=parent_chain_code,
                    data=0x00 || parent_private_key || index_BE_4_bytes)
child_private_key = child[0..32]
child_chain_code  = child[32..64]
```

`javax.crypto.Mac` with `HmacSHA512` is available on `minSdk 26` — no library needed.
Implement as `SolanaSlip10.deriveKeyFromSeed(byte[] seed64, long[] hardenedPath)` returning
a 32-byte private key seed.

---

## Phase 3 — Section 2: Coin Descriptor

### `SolanaMain.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/SolanaMain.java`
**Extends:** `SolanaFamily`

| Field | Value |
|---|---|
| `id` | `"solana.main"` |
| `name` | `"Solana"` |
| `symbol` | `"SOL"` |
| `uriScheme` | `"solana"` |
| `bip44Index` | `501` (SLIP-0044) |
| `unitExponent` | `9` (1 SOL = 10^9 lamports) |
| `feePolicy` | `FeePolicy.FEE_PER_SIGNATURE` (new enum value) |

Also create `SolanaDevnet.java` (`id="solana.devnet"`, endpoint `api.devnet.solana.com`) and
`SolanaTestnet.java` (`id="solana.testnet"`, endpoint `api.testnet.solana.com`).

### `SolanaFamily.java`
**Path:** `core/src/main/java/com/openwallet/core/coins/families/SolanaFamily.java`

```java
public abstract class SolanaFamily extends CoinType {
    { family = Families.SOLANA; }
}
```

Add `SOLANA("solana")` to `Families` enum.

---

## Phase 3 — Section 3: Address

### `SolanaAddress.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/solana/SolanaAddress.java`
**Implements:** `AbstractAddress`

A Solana address is the **raw 32-byte Ed25519 public key encoded in Bitcoin's Base58 alphabet
with no version byte and no checksum**.

```java
public String toString() {
    return Base58.encode(pubkeyBytes); // 32 bytes → 43-44 char string
}

public static SolanaAddress fromString(CoinType type, String address) {
    byte[] decoded = Base58.decode(address);
    if (decoded.length != 32) throw new AddressMalformedException(...);
    return new SolanaAddress(type, decoded);
}
```

The existing `Base58` utility class (from bitcoinj, in `core` module) uses the same alphabet
and can be reused for encoding. Decoding must strip the checksum behavior — use raw Base58
decode without the 4-byte checksum verification that `Base58.decodeChecked()` applies. Add
a `Base58.decodeRaw()` static method or use the solanaj utility.

`getId()` — return the first 8 bytes of the public key interpreted as a big-endian `long`.
This satisfies the `AbstractAddress` interface while being deterministic.

---

## Phase 3 — Section 4: Key Derivation

### `SolanaFamilyKey.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/solana/SolanaFamilyKey.java`
**Analogous to:** `NxtFamilyKey`

```java
public class SolanaFamilyKey {
    private final byte[] privateKeySeed; // 32 bytes — Ed25519 seed
    private final byte[] publicKey;      // 32 bytes — Ed25519 public key

    public SolanaFamilyKey(byte[] walletSeed64, int accountIndex) {
        long[] path = { 44 | HARDENED, 501 | HARDENED, accountIndex | HARDENED, 0 | HARDENED };
        this.privateKeySeed = SolanaSlip10.deriveKeyFromSeed(walletSeed64, path);
        this.publicKey = edPublicFromPrivate(this.privateKeySeed);
    }
}
```

**Ed25519 key generation** using SpongyCastle (already in project):
```java
Ed25519PrivateKeyParameters priv = new Ed25519PrivateKeyParameters(privateKeySeed, 0);
Ed25519PublicKeyParameters pub = priv.generatePublicKey();
publicKey = pub.getEncoded(); // 32 bytes
```

**Signing:**
```java
Ed25519Signer signer = new Ed25519Signer();
signer.init(true, new Ed25519PrivateKeyParameters(privateKeySeed, 0));
signer.update(message, 0, message.length);
byte[] signature = signer.generateSignature(); // 64 bytes
```

**Encryption of private key seed:** Use existing `KeyCrypterScrypt` + AES-256-CBC pattern from
`NxtFamilyKey`. The 32-byte seed is the secret material to encrypt; the pattern is identical to
how NXT encrypts its 32-byte private key.

---

## Phase 3 — Section 5: Transaction

### `SolanaTransaction.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/solana/SolanaTransaction.java`
**Implements:** `AbstractTransaction`

**Wire format for a SOL transfer (legacy format):**

```
[1 byte]         num_required_signatures = 1
[1 byte]         num_readonly_signed = 0
[1 byte]         num_readonly_unsigned = 1   (System Program is read-only unsigned)
[compact_u16]    num_accounts = 3
[32 bytes]       from_pubkey
[32 bytes]       to_pubkey
[32 bytes]       system_program = 11111111...
[32 bytes]       recent_blockhash
[compact_u16]    num_instructions = 1
[1 byte]         program_id_index = 2        (index into account list → System Program)
[compact_u16]    num_accounts_in_instruction = 2
[1 byte]         account[0] = 0              (from, writable+signer)
[1 byte]         account[1] = 1              (to, writable)
[compact_u16]    instruction_data_len = 12
[4 bytes LE]     instruction_type = 2        (SystemProgram::Transfer)
[8 bytes LE]     lamports
```

`compact_u16` encoding: values 0–127 are one byte; values 128–16383 are two bytes (LSB first,
high bit set on first byte). Implement as `SolanaCompactU16` utility.

**Full transaction = `[1-byte num_sigs][64-byte sig][message_bytes]`**

`getHash()` returns a `Sha256Hash` wrapping the SHA-256 of the 64-byte signature, for
compatibility with the app's internal ID model. The canonical Solana tx ID (Base58 of the
signature) is returned by a new `getSignatureBase58()` method.

### `SolanaSendRequest.java`
Extends `SendRequest`. Adds: `recentBlockhash (byte[32])`, `lamports (long)`,
`destinationAddress (SolanaAddress)`.

### `recent_blockhash` lifecycle
The `recent_blockhash` expires after ~150 slots (60–90 seconds). The wallet must:
1. Fetch `getLatestBlockhash` before every transaction construction.
2. If the transaction is not broadcast within 90 seconds of fetch, re-fetch.
3. `SolanaFamilyWallet` caches the last blockhash with a timestamp and auto-refreshes.

---

## Phase 3 — Section 6: Network Client

### `SolanaServerClient.java`
**Path:** `core/src/main/java/com/openwallet/core/network/SolanaServerClient.java`
**Implements:** `BlockchainConnection<SolanaTransaction>`
**Template:** `NxtServerClient`

Uses OkHttp 2.x (`com.squareup.okhttp:okhttp:2.7.5`, already in project). JSON-RPC 2.0 over
HTTPS. All requests use `application/json` body, not form encoding.

**Helper:** `SolanaRpcRequest` — builds `{"jsonrpc":"2.0","id":1,"method":"...","params":[...]}`.
**Helper:** `SolanaRpcResponse` — parses JSON result/error.

**Required RPC methods:**

```java
BigInteger getBalance(String address);           // eth_getBalance equivalent
long getTransactionCount(String address);        // for display; not used for nonce
byte[] getLatestBlockhash();                     // MUST call before every tx
String sendTransaction(byte[] signedTx);         // returns base58 signature
List<SolanaTransaction> getSignaturesForAddress(String address, int limit);
SolanaTransaction getTransaction(String sig);
long getFeeForMessage(byte[] compiledMessage);   // lamports
long getMinimumBalanceForRentExemption(int dataSize);
```

**Polling interval:** 30 seconds for balance; detect new signatures via
`getSignaturesForAddress` comparing against cached last-known signature.

**Rate limiting:** The public `api.mainnet-beta.solana.com` is rate-limited at 100 req/10s.
For production, a provider endpoint must be user-configured. Add
`getSolanaRpcEndpoint()` / `setSolanaRpcEndpoint()` to `Configuration.java`.

---

## Phase 3 — Section 7: Wallet Account

### `SolanaFamilyWallet.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/solana/SolanaFamilyWallet.java`
**Extends:** `AbstractWallet<SolanaTransaction, SolanaAddress>`
**Template:** `NxtFamilyWallet`

Key differences from NXT:
- **Rent awareness.** On `onConnected()`, call `getMinimumBalanceForRentExemption(0)` and
  cache. Warn in UI if outgoing transfer would leave balance below rent-exempt minimum.
- **recent_blockhash cache.** Refresh every 30 seconds or on demand before send.
- **Single address per account.** Derive at `m/44'/501'/accountIndex'/0'`.

---

## Phase 3 — Section 8: Changes to Existing Files

### `Families.java`
```java
SOLANA("solana"),
```

### `Wallet.java` — `createAndAddAccount()`
```java
} else if (coinType instanceof SolanaFamily) {
    newAccount = new SolanaFamilyWallet(coinType, seed.getSeedBytes(), accountIndex);
}
```

Note: Unlike Bitcoin/NXT which pass `DeterministicKey` hierarchy, Solana receives the raw
64-byte BIP39 seed bytes directly, as SLIP-0010 derives from the seed independently.

### `ServerClients.java` — `getConnection()`
```java
} else if (type instanceof SolanaFamily) {
    SolanaServerClient client = new SolanaServerClient(addresses.get(type), connectivityHelper);
    connections.put(type, client);
    return client;
}
```

### `Constants.java`
```java
// DEFAULT_COINS_SERVERS — Solana RPC endpoint
new SolanaServerAddress(SolanaMain.get(), "https://api.mainnet-beta.solana.com"),
new SolanaServerAddress(SolanaDevnet.get(), "https://api.devnet.solana.com"),

// SUPPORTED_COINS
SolanaMain.get(),   // after EthereumMain

// COINS_ICONS
COINS_ICONS.put(CoinID.SOLANA_MAIN.getCoinType(), R.drawable.solana);

// COINS_BLOCK_EXPLORERS
COINS_BLOCK_EXPLORERS.put(CoinID.SOLANA_MAIN.getCoinType(), "https://solscan.io/tx/%s");
```

### `wallet/build.gradle`
No new dependencies required for the crypto layer. If solanaj is adopted for transaction
serialization, add:
```groovy
implementation 'com.github.skynetcap:solanaj:1.x.x'
```
The zero-dependency approach (SpongyCastle + custom SLIP-0010 + OkHttp 2.x) is preferred.

---

## Phase 3 — Section 9: SPL Token Support (deferred to v1.3)

SPL tokens use Associated Token Accounts (ATAs), Program Derived Addresses (PDAs), and the
SPL Token Program. Each token holding is a separate on-chain account. Implementation requires:
- ATA address derivation (PDA via `findProgramAddress`)
- `getTokenAccountsByOwner` RPC call
- `spl-token:transfer` instruction encoding
- Token `Transfer` event detection via `getSignaturesForAddress` with `accountInclude` filter

Deferred to Phase 3b. The `SolanaTransaction` and `SolanaServerClient` classes must be
designed to accommodate multi-instruction transactions without breaking changes.

---

## Phase 3 — Section 10: Unit Tests

**`SolanaTest.java`** (`core/src/test/`):
1. `SolanaSlip10.deriveKeyFromSeed(seed, path)` matches SLIP-0010 Ed25519 reference test vectors.
2. Known BIP39 seed at `m/44'/501'/0'/0'` produces the correct Solana public key address.
3. `SolanaAddress.fromString(addr).toString()` round-trips.
4. `SolanaAddress.fromString()` rejects 31-byte and 33-byte decoded values.
5. A serialized legacy transfer message matches a known reference byte sequence.
6. Ed25519 signature over a known message verifies correctly.

---

## Phase 3 — Section 11: Success Criteria

- Solana appears in coin selection after Ethereum.
- Address at `m/44'/501'/0'/0'` matches Phantom / Solflare for the same seed phrase.
- Balance fetched from mainnet JSON-RPC and displayed in SOL.
- SOL transfer constructed, signed, broadcast, and confirmed on Devnet.
- `recent_blockhash` is refreshed before each transaction; expired blockhash triggers re-fetch.
- No regressions on any Phase 1 or Phase 2 coins.

---

---

# Phase 4: Zcash Shielded Addresses (Sapling, Orchard, Unified)

**Branch:** `feature/zcash-shielded`
**Target:** NYC OpenWallet v1.3.0
**Prerequisite:** Phase 3 merged. Phase 1 ZEC transparent support must remain intact.

---

## Phase 4 — Section 1: Architecture Overview

Zcash shielded support requires a fundamentally different backend stack from all other coins.
It cannot use the existing Electrum/Stratum or simple HTTP-polling approach.

```
┌─────────────────────────────────────────────────────────────────┐
│  Android Wallet UI (new shielded fragments + existing UI)       │
├─────────────────────────────────────────────────────────────────┤
│  ZcashShieldedWallet  (implements WalletAccount)                │
│  ZcashSynchronizerAdapter  (bridges ECC SDK ↔ app Java model)  │
├─────────────────────────────────────────────────────────────────┤
│  zcash-android-wallet-sdk  (ECC, Maven AAR)                     │
│  ├─ Synchronizer  (Kotlin coroutines — Sapling + Orchard scan)  │
│  └─ librustzcash JNI  (.so files for arm64-v8a, armeabi-v7a)   │
│      ├─ ZIP-32 key derivation                                   │
│      ├─ Sapling Groth16 proof generation (~50 MB params)        │
│      ├─ Orchard Halo2 proof generation  (no external params)    │
│      └─ Trial decryption of compact blocks                      │
├─────────────────────────────────────────────────────────────────┤
│  LightwalletdConnection  (new, gRPC CompactTxStreamer)          │
│  grpc-kotlin + OkHttp transport                                 │
├─────────────────────────────────────────────────────────────────┤
│  lightwalletd server  (Go, operator-hosted)                     │
│  ↓ JSON-RPC                                                     │
│  zebrad  (Zebra full node — Zcash Foundation, Rust)             │
├─────────────────────────────────────────────────────────────────┤
│  [Existing Phase 1] ServerClient (Stratum/ElectrumX)            │
│  [Existing Phase 1] for ZEC t-address operations                │
└─────────────────────────────────────────────────────────────────┘
```

**Zebra note:** Zebra is a full node and does not natively expose a lightwalletd gRPC server.
The standard deployment is `lightwalletd` pointed at Zebra's JSON-RPC port. The `zebra-grpc`
crate is in development but not yet production-ready as of early 2026. The app connects to
`lightwalletd`, not directly to Zebra.

---

## Phase 4 — Section 2: Key Material — ZIP-32

Zcash shielded keys are derived via **ZIP-32** from the same BIP-39 seed as the transparent
account. ZIP-32 uses a completely separate derivation tree from BIP-44/BIP-32.

**Sapling derivation path:** `m / 32' / 133' / account'`
**Orchard derivation path:** `m / 32' / 133' / account' / 0'`

Both derive from the raw seed bytes via HMAC-SHA512 with the constant `"ZcashIP32Sapling"` or
`"ZcashIP32Orchard"`. These operations are performed by `librustzcash` via the ECC SDK JNI —
no Java implementation is needed or desired.

**Key hierarchy per account:**

```
Sapling:
  sk_account → ask, nsk, ovk, dk
  ask + nsk → ivk (incoming viewing key)
  dk + ivk → address(d[j], pk_d[j]) for diversifier index j

Orchard:
  sk_account → ask, nk, rivk, dk
  rivk + nk → ivk
  dk + ivk → address(d[j], pk_d[j]) for diversifier index j
```

The wallet stores the **Unified Full Viewing Key (UFVK)** for balance scanning and the
**encrypted spending key** for transaction signing. The spending key is only decrypted in
memory at signing time.

---

## Phase 4 — Section 3: Address Types

### Sapling Address (`zs1...`)
- Bech32, HRP `"zs"` (mainnet).
- 43-byte payload: 11-byte diversifier `d` + 32-byte `pk_d` (Jubjub point).
- Derives from Sapling ivk and a diversifier index. Multiple addresses can share one account.
- **No `ZcashAddress` subclass needed.** The ECC SDK returns Sapling addresses as strings.
  Wrap as `ZcashShieldedAddress implements AbstractAddress` storing the Bech32 string.

### Orchard Address (only inside Unified Addresses)
- No standalone Orchard address format. Orchard receivers appear only inside Unified Addresses.
- 43-byte receiver: 11-byte diversifier + 32-byte `pk_d` (Pallas curve).

### Unified Address (`u1...`) — ZIP-316
- Bech32m, HRP `"u"` (mainnet), `"utest"` (testnet).
- TLV-encoded receivers, sorted by type code descending, F4Jumble-obfuscated before encoding.
- Receiver type codes: `0x00` P2PKH, `0x01` P2SH, `0x02` Sapling, `0x03` Orchard.
- Must include at least one shielded receiver; may include a transparent receiver.
- The wallet's default receive address for a shielded account is a UA containing at minimum
  an Orchard receiver (for NU5+ compatibility) and a Sapling receiver (for pre-NU5 senders).

### `ZcashUnifiedAddress.java`
```java
public class ZcashUnifiedAddress extends ZcashShieldedAddress {
    public Optional<String> getSaplingReceiver();
    public Optional<String> getOrchardReceiver();
    public Optional<String> getTransparentReceiver();
    public String selectBestReceiverAddress(); // highest-priority known receiver
}
```

ZIP-316 encoding/decoding is performed by the ECC SDK. The Java wrapper parses the decoded
result into the above accessors.

---

## Phase 4 — Section 4: ECC SDK Integration

### Dependency
```groovy
implementation 'cash.z.ecc.android:zcash-android-sdk:2.x.x'
```

The SDK ships as an AAR containing:
- Kotlin/Android library code (Synchronizer, Room database, gRPC client)
- `librustzcash` compiled `.so` files for `arm64-v8a`, `armeabi-v7a`, `x86_64`
- Sapling proving parameters (50 MB) OR a download-on-demand strategy (configurable)

Total size addition: **30–50 MB** (uncompressed). With AAB and Play Store asset delivery,
the `.so` files can be delivered as install-time asset packs, keeping the base APK smaller.

### `ZcashSynchronizerAdapter.java`
**Path:** `wallet/src/main/java/com/openwallet/wallet/service/ZcashSynchronizerAdapter.java`

Bridges the ECC SDK's Kotlin coroutines/Flow model to the existing Java listener model:

```java
public class ZcashSynchronizerAdapter {
    private final Synchronizer synchronizer;
    private final CoroutineScope scope;

    // Collect Kotlin Flows on a background coroutine scope;
    // dispatch results to WalletAccountEventListener via Handler.post()
    public void start(WalletAccountEventListener listener) { ... }
    public void stop() { scope.cancel(); }

    public ZcashUnifiedAddress getCurrentAddress() { ... }
    public BigInteger getShieldedBalance() { ... }
    public void sendShieldedTransaction(ZcashShieldedSendRequest request) { ... }
}
```

Key Synchronizer methods used:
- `synchronizer.saplingBalances` — `StateFlow<WalletBalance>` (verified + unverified zats)
- `synchronizer.orchardBalances` — same
- `synchronizer.transactions` — `Flow<List<TransactionOverview>>`
- `synchronizer.getUnifiedAddress(account)` — returns UA string
- `synchronizer.sendToAddress(...)` — construct and broadcast shielded tx
- `synchronizer.latestHeight` — current sync progress

### `ZcashShieldedWallet.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/zcash/ZcashShieldedWallet.java`
**Implements:** `WalletAccount<ZcashShieldedTransaction, ZcashUnifiedAddress>`

Delegates all crypto and state to `ZcashSynchronizerAdapter`. The existing `WalletAccount`
interface methods map as follows:

| `WalletAccount` method | Delegate to |
|---|---|
| `getBalance()` | `adapter.getShieldedBalance()` (Sapling + Orchard combined) |
| `getAddress()` | `adapter.getCurrentAddress()` |
| `getTransactions()` | `adapter.getTransactionHistory()` |
| `sendCoinsOffline(request)` | `adapter.sendShieldedTransaction(request)` |
| `isConnected()` | `synchronizer.status == SYNCING or SYNCED` |

---

## Phase 4 — Section 5: Sapling Parameters

The Sapling spend proving key (~47 MB) and output proving key (~3.5 MB) must be present before
any shielded send.

**Delivery strategy — recommended:** Download on first shielded send attempt, cache in
app's private directory. Verify SHA-256 against hardcoded expected hashes before use.

```java
public class ZcashParamsManager {
    private static final String SPEND_PARAMS_URL = "https://download.z.cash/downloads/sapling-spend.params";
    private static final String OUTPUT_PARAMS_URL = "https://download.z.cash/downloads/sapling-output.params";
    private static final String SPEND_PARAMS_SHA256 = "8270785a..."; // hardcoded
    private static final String OUTPUT_PARAMS_SHA256 = "657e3d38..."; // hardcoded

    public boolean areParamsPresent(Context context);
    public void downloadParams(Context context, ProgressCallback callback);
    public File getSpendParamsPath(Context context);
    public File getOutputParamsPath(Context context);
}
```

**UI:** `ZcashParamsDownloadFragment` — shown as a blocking step before the first shielded
send. Displays download progress. Cannot be dismissed until complete.

**Orchard:** No external parameter files. The Halo2 circuit is baked into `librustzcash`.

---

## Phase 4 — Section 6: lightwalletd gRPC Connection

### `LightwalletdConnection.java`
**Path:** `core/src/main/java/com/openwallet/core/network/LightwalletdConnection.java`
**Implements:** `BlockchainConnection<ZcashShieldedTransaction>`

Note: This class is a thin adapter. The actual gRPC communication is handled by the ECC SDK
internally. `LightwalletdConnection` manages lifecycle (start/stop/reconnect) and translates
SDK connection status to `ConnectionEventListener` callbacks.

The `Synchronizer` is configured with the `lightwalletd` endpoint:
```kotlin
LightWalletEndpoint("your.lightwalletd.host", 9067, isTls = true)
```

Default public lightwalletd endpoints:
- Mainnet: `mainnet.lightwalletd.com:9067` (ECC-operated)
- Testnet: `testnet.lightwalletd.com:9067`

User-configurable via `Configuration.getZcashLightwalletdEndpoint()` /
`setZcashLightwalletdEndpoint()`.

### gRPC dependencies (added by ECC SDK transitively):
```groovy
// Added by zcash-android-sdk AAR — do not add manually, may cause version conflicts
// grpc-kotlin-stub, grpc-okhttp, protobuf-kotlin-lite
```

Verify no version conflicts with existing OkHttp 2.x and OkHttp 4.x (Phase 2). The gRPC
OkHttp transport uses the `okhttp3` namespace, which coexists with the Stratum client's
`com.squareup.okhttp` namespace.

---

## Phase 4 — Section 7: Transaction Model

### `ZcashShieldedTransaction.java`
**Path:** `core/src/main/java/com/openwallet/wallet/families/zcash/ZcashShieldedTransaction.java`
**Implements:** `AbstractTransaction`

Wraps the ECC SDK's `TransactionOverview`:

| Field | Source |
|---|---|
| `txId` | 32-byte hash (from `TransactionOverview.txId`) |
| `blockHeight` | `TransactionOverview.minedHeight` (-1 if pending) |
| `blockTime` | Unix timestamp |
| `netValue` | Value change in zats (negative for sends, positive for receives) |
| `isShielded` | `true` for Sapling/Orchard; `false` for transparent |
| `memo` | 512-byte UTF-8 memo field (shielded only) |
| `pool` | `SAPLING`, `ORCHARD`, or `TRANSPARENT` |

### `ZcashShieldedSendRequest.java`
Extends `SendRequest`. Adds: `recipientAddress (ZcashUnifiedAddress)`, `memo (String, ≤512 bytes)`,
`privacyPolicy (ALLOW_REVEALED_RECIPIENTS | PREFER_SHIELDED | FULL_PRIVACY)`.

---

## Phase 4 — Section 8: Proof Generation on Mobile

Proof generation is CPU-intensive:
- Sapling spend proof: 3–15 seconds on mid-range hardware (Snapdragon 680).
- Orchard batch proof (2 actions): 4–10 seconds.

**Required UX pattern:**
1. User taps Send → app validates inputs.
2. Before signing, show a `ProgressDialog`: *"Generating shielded transaction… This may take up to 30 seconds."*
3. Run proof generation on a `WorkManager` immediate task with `setForegroundAsync()` (foreground service notification).
4. On completion, broadcast automatically or return to UI for final confirmation.
5. Never run proof generation on the main thread. Never run it inside `Activity.onPause()` path.

---

## Phase 4 — Section 9: Wallet Birthday and Initial Sync

**Wallet birthday:** The block height at wallet creation (or restore). The Synchronizer only
scans blocks from the birthday onward, bounding trial decryption work.

**New wallet:** Set birthday to `currentHeight - 100` (safety margin for recent unconfirmed txs).

**Restored wallet:** Prompt the user for an approximate wallet creation date. Convert to block
height using a hardcoded block-time table (approximately 75 seconds per block on Zcash mainnet).
If unknown, use genesis height (full rescan — warn user this may take hours on first launch).

**Sync progress UI:** `ZcashSyncStatusView` — persistent bar in the ZEC account screen during
initial sync. Shows: `"Syncing block 1,250,000 of 2,100,000 (59%)  Est. 12 min remaining"`.
Computed from `(targetHeight - currentHeight) * 75 seconds`.

**Auto-shielding (ZIP-315):** When the transparent balance exceeds a threshold (default 0.01 ZEC),
prompt the user to shield funds into the Orchard pool. This is a UI suggestion, not automatic.
Implement as a banner: *"You have 0.05 ZEC in transparent address t1... — Tap to shield for privacy."*

---

## Phase 4 — Section 10: Viewing Keys

### Unified Full Viewing Key (UFVK)
The UFVK (Bech32m, HRP `"uview"`) allows balance scanning without spending capability. It
contains Sapling FVK and Orchard FVK receivers (same ZIP-316 structure as UA).

**Watch-only mode:** Configure `Synchronizer` with a UFVK instead of a spending key. This
enables a watch-only Zcash shielded wallet that can view all incoming and outgoing transactions
(including sender via `ovk` in `outCiphertext`) without being able to spend.

**Key storage policy:**
- Spending key: AES-256 encrypted, stored in `SharedPreferences` private to the app.
- UFVK: Stored unencrypted (not a secret). Used for routine sync.
- Synchronizer is constructed with the UFVK for sync; spending key is decrypted only at send time.

---

## Phase 4 — Section 11: Changes to Existing Files

### `Constants.java`
```java
// DEFAULT_COINS_SERVERS — ZEC shielded uses lightwalletd, not Stratum
// No entry in DEFAULT_COINS_SERVERS; LightwalletdConnection is configured separately

// SUPPORTED_COINS — ZcashMain already present; ZcashShieldedMain is a new CoinType
// For UI, shielded and transparent ZEC may be presented as sub-accounts of one ZEC entry

// COINS_ICONS — reuse R.drawable.zcash for shielded account
```

### `CoinID.java`
```java
ZCASH_SHIELDED_MAIN(ZcashShieldedMain.get()),
```

`ZcashShieldedMain` is a new `CoinType` (`id="zcash.shielded.main"`) extending `ZcashFamily`
with `addressPrefix = "u"`. It coexists with `ZcashMain` (transparent). The two share the same
`bip44Index = 133` and the same user-facing "Zcash" label, but appear as separate accounts
internally — one routed to ElectrumX, one to lightwalletd.

### `Wallet.java` — `createAndAddAccount()`
```java
} else if (coinType instanceof ZcashFamily && coinType.getId().contains("shielded")) {
    newAccount = new ZcashShieldedWallet(coinType, seed.getSeedBytes(), accountIndex, context);
}
```

### `wallet/build.gradle`
```groovy
implementation 'cash.z.ecc.android:zcash-android-sdk:2.x.x'
```

**APK size impact:** The ECC SDK adds ~30–50 MB (`.so` files + SDK code). Use Android App
Bundle (AAB) with ABI splits (`splits.abi`) to deliver only the relevant `.so` for each
device architecture. This reduces per-device download size to ~15–20 MB additional.

---

## Phase 4 — Section 12: New Files Summary

**Core module:**
| File | Purpose |
|---|---|
| `coins/ZcashShieldedMain.java` | CoinType for shielded ZEC account |
| `wallet/families/zcash/ZcashShieldedWallet.java` | WalletAccount implementation |
| `wallet/families/zcash/ZcashShieldedAddress.java` | AbstractAddress wrapping UA/Sapling string |
| `wallet/families/zcash/ZcashUnifiedAddress.java` | ZIP-316 UA with receiver accessors |
| `wallet/families/zcash/ZcashShieldedTransaction.java` | AbstractTransaction for shielded txs |
| `wallet/families/zcash/ZcashShieldedSendRequest.java` | SendRequest with memo + privacy policy |
| `wallet/families/zcash/ZcashViewingKey.java` | UFVK wrapper |
| `network/LightwalletdConnection.java` | BlockchainConnection via ECC SDK Synchronizer |

**Wallet module:**
| File | Purpose |
|---|---|
| `service/ZcashSynchronizerAdapter.java` | Bridges ECC SDK coroutines ↔ Java listener model |
| `service/ZcashParamsManager.java` | Sapling params download/verify/cache |
| `ui/ZcashShieldedAccountFragment.java` | Shielded balance + sync status UI |
| `ui/ZcashSendShieldedFragment.java` | Shielded send flow with memo field |
| `ui/ZcashSyncStatusView.java` | Sync progress bar widget |
| `ui/ZcashParamsDownloadFragment.java` | One-time Sapling params download UI |
| `res/layout/fragment_zcash_shielded.xml` | Layout for shielded account screen |

---

## Phase 4 — Section 13: Success Criteria

- Unified address (`u1...`) is generated and displayed for the shielded ZEC account.
- Sapling address (`zs1...`) is derivable and correct for the given seed.
- Synchronizer syncs from wallet birthday; trial decryption finds known received notes.
- Shielded send (Sapling or Orchard) is constructed, proven, and broadcast on testnet.
- Proof generation runs off the main thread; UI shows progress.
- Transparent ZEC account (Phase 1) remains fully functional on the same screen.
- Auto-shielding prompt appears when transparent balance exceeds threshold.
- Watch-only mode (UFVK) shows correct balance without spending capability.
- No regressions on any Phase 1–3 coins.

---

---

# Cross-Phase Summary

## New Gradle Dependencies by Phase

| Phase | Dependency | Size Impact |
|---|---|---|
| Phase 2 (ETH) | `okhttp3:4.12.0` (for EthServerClient) | +400 KB |
| Phase 3 (SOL) | Zero (SpongyCastle + OkHttp 2.x reused) | +0 KB |
| Phase 3 (SOL, optional) | `solanaj:1.x.x` (transaction serialization) | +250 KB |
| Phase 4 (ZEC shielded) | `zcash-android-sdk:2.x.x` (ECC, Rust JNI) | +30–50 MB |

## New Families

| Phase | Family Class | Families Enum Value | Key Curve |
|---|---|---|---|
| Phase 2 | `EthFamily` | `ETHEREUM` | secp256k1 |
| Phase 3 | `SolanaFamily` | `SOLANA` | Ed25519 |
| Phase 4 | `ZcashFamily` (extended) | (existing `BITCOIN` via ZcashFamily) | Jubjub / Pallas |

## Architecture Invariants (must not break across phases)

1. `ServerClients.startAsync()` gracefully skips coins with no configured server (`hasAddress()` guard — Phase 1).
2. All `WalletAccount` implementations remain compatible with `CoinService` / `CoinServiceImpl`.
3. The `Wallet` aggregate class serializes all account types; `WalletProtobufSerializer` must be extended for each new family.
4. The `AbstractAddress` interface's `getId()` returns a `long` — all new address types satisfy this (even if the value is truncated from a larger key).
5. `Sha256Hash` is used as a 32-byte transaction ID container even when the underlying hash algorithm is Keccak-256 (ETH) or a signature hash (Solana). New `getCanonicalId()` method should be added to `AbstractTransaction` returning the human-readable tx ID string for each family.

## Out of Scope (all phases)

- iOS wallet
- Hardware wallet (Ledger, Trezor) integration
- Multi-signature accounts
- Lightning Network
- Ethereum Layer 2 networks (Arbitrum, Base, Optimism) — deferred to Phase 2b
- Solana SPL token support — deferred to Phase 3b
- Zcash auto-shielding (automatic, not prompted) — deferred pending UX review
- FCM push notifications for incoming transactions — deferred to Phase 5
- ENS / SNS / Zcash address book lookup — deferred
- DeFi / DEX integration
