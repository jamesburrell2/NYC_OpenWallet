# Zcash-Family Full UTXO Discovery + Ycash + Zclassic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the wallet efficiently discover ALL addresses holding UTXOs across every derivation path for the Zcash family — full ElectrumX-backed transparent BIP-44 gap-limit discovery for ZEC (fused with the SDK's shielded scanning, plus a sweep path), and add Ycash (YEC) and Zclassic (ZCL) as supported coins on the same machinery.

**Architecture:** ZEC gains a companion transparent account (`ZcashTransparentMain extends ZcashFamily extends BitFamily`) that rides the existing `WalletPocketHD`/`ServerClient`/ElectrumX stack — which already implements gap-limit discovery — while `ZcashSdkWallet` keeps shielded/UA duties via zcash-android-sdk. YEC and ZCL are transparent-only coins on the same `ZcashFamily` path. One new shared component unlocks spending for all three: a ZIP-243 (v4 Sapling-format) transparent transaction signer using BLAKE2b personalized hashing.

**Tech Stack:** Java 8 (core module, JUnit 4), existing stratumj/ElectrumX client, zcash-android-sdk (ZEC shielded only), vendored pure-Java BLAKE2b, Gradle on Windows (`.\gradlew`).

## Global Constraints

- Repo: `D:\NewYorkCoin_2026\nyc-openwallet-android`, branch `feature/nyc-coin-integration` (branch off it or continue on it per session decision)
- Do NOT raise Gradle heap above the configured `-Xmx4096m` (host OOMs)
- Core test baseline: 288 tests / **36 pre-existing failures** (protobuf Descriptors, shapeshift, WalletPocketHDTest, WalletTest, ZcashTest.zecAddressFromKeyStartsWithT1, stratumj, etc.). Acceptance bar for every task: no NEW failures; all newly added tests pass
- SOC-2: never log addresses/keys/seed material; fail closed on invalid input; no secrets in source
- NEVER hold the pocket/wallet lock across network I/O (`StratumClient.call` is a blocking socket write — see commit `a863493`)
- Coin parameters (verify against chain explorers during QA):
  - ZEC: bip44 133, t1=0x1CB8 / t3=0x1CBD (already in `ZcashMain`)
  - YEC: bip44 347, s1=0x1C28 (P2PKH) / s3=0x1C2D (P2SH), uri `ycash`, unit 8
  - ZCL: bip44 147, t1=0x1CB8 / t3=0x1CBD (same as ZEC), uri `zclassic`, unit 8
- Server status (TCP-probed 2026-07-03): ZEC `zec-cce-1.coinomi.net:5028` OPEN; ZCL `zcl-cce-1.coinomi.net:5015` OPEN; YEC — NO public ElectrumX found (`electrum1/2.ycash.xyz` closed; `lite.ycash.xyz:443` is lightwalletd, not usable). YEC is GATED on Task 10 infra.
- Consensus branch IDs are consensus-critical and change with network upgrades. Do NOT trust values from memory — Task 6 Step 1 fetches them from authoritative sources and records them in `ZcashTxSigner` constants with citations.

## Phasing (each phase ships independently)

| Phase | Tasks | Deliverable |
|---|---|---|
| A | 1–3 | ZEC transparent discovery, read-only: full-history balance + tx list fused into the ZEC screen |
| B | 4–7 | ZIP-243 signer → ZEC transparent sweep to the SDK t-address |
| C | 8–9 | ZCL as a supported coin (send + receive) |
| D | 10–11 | YEC infra (VPS) + YEC as a supported coin |
| E | 12 | Device QA + release |

---

### Task 1: Protocol-verify the CCE servers (no code)

**Files:** none — recon only; results recorded in this plan file.

**Interfaces:**
- Consumes: nothing
- Produces: confirmed host/port/SSL parameters used verbatim in Tasks 3 and 8

- [ ] **Step 1: Handshake ZEC and ZCL servers**

From PowerShell, use the same technique as the 2026-07-02 probe (openssl/Test-NetConnection + manual Electrum JSON call). For each of `zec-cce-1.coinomi.net:5028` and `zcl-cce-1.coinomi.net:5015`, and also candidates `zec-cce-2.coinomi.net:5028`, `zcl-cce-2.coinomi.net:5015`:

```powershell
# Raw TCP first; if garbage, retry with TLS (openssl s_client)
$c = New-Object Net.Sockets.TcpClient($host_, $port); $s = $c.GetStream()
$w = New-Object IO.StreamWriter($s); $w.NewLine = "`n"
$w.WriteLine('{"id":1,"method":"server.version","params":["probe","1.4"]}'); $w.Flush()
$r = New-Object IO.StreamReader($s); $r.ReadLine()
```

Expected: a JSON reply naming the server software and protocol (the other coinomi CCE servers answered `ElectrumX 1.16–1.18`, protocol 1.4). Record: SSL or plaintext, protocol version, and — via `blockchain.headers.subscribe` — the current block height.

- [ ] **Step 2: Sanity-check chain height against a public explorer**

ZEC height must match https://blockchair.com/zcash within a few blocks. ZCL height must match a live ZCL explorer (try https://zclassic.tokenview.io or the explorer linked from zclassic.org). If the ZCL server is stuck thousands of blocks behind or the chain has no recent blocks, STOP Phase C and report — ZCL may not be viable, and the plan continues with Phases A/B/D only.

- [ ] **Step 3: Record results**

Edit the "Server status" line in Global Constraints above with confirmed values (SSL flag, protocol, height, date). Commit the plan file update:

```powershell
git add docs/superpowers/plans/2026-07-03-zcash-family-discovery-yec-zcl.md
git commit -m "docs: record ZEC/ZCL ElectrumX probe results`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: ZcashTransparentMain coin type + registry (fixes "different derivation path" confusion)

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/ZcashTransparentMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinID.java` (add enum entry)
- Test: `core/src/test/java/com/openwallet/core/coins/ZcashTransparentMainTest.java` (create)

**Interfaces:**
- Consumes: `ZcashFamily` (abstract, extends BitFamily), `CoinID` enum registration pattern
- Produces: `ZcashTransparentMain.get(): CoinType` with `id = "zcashtransparent.main"`, `bip44Index = 133` (SAME as ZEC — its account 0 external index 0 key IS the SDK's t-address, so discovery is a superset of what the SDK watches); `symbol = "ZEC"`; `name = "Zcash Transparent"`. NOT added to `SUPPORTED_COINS` (users never add it directly — Task 3 auto-manages it alongside the ZEC account).

- [ ] **Step 1: Write the failing test**

```java
package com.openwallet.core.coins;

import com.openwallet.core.wallet.AbstractAddress;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ZcashTransparentMainTest {
    private final CoinType type = ZcashTransparentMain.get();

    @Test
    public void parametersMatchZcashTransparentLayer() {
        assertEquals("zcashtransparent.main", type.getId());
        assertEquals(133, (int) type.getBip44Index());
        assertEquals(0x1CB8, (int) type.getAddressHeader());
        assertEquals(0x1CBD, (int) type.getP2SHHeader());
        assertEquals("ZEC", type.getSymbol());
    }

    @Test
    public void parsesRealTransparentAddress() throws Exception {
        AbstractAddress a = type.newAddress("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi");
        assertEquals("t1duiEGg7b39nfQee3XaTY4f5McqfyJKhBi", a.toString());
    }

    @Test
    public void registeredInCoinId() {
        assertTrue(CoinID.typeFromId("zcashtransparent.main") instanceof ZcashTransparentMain);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew :core:test --tests "com.openwallet.core.coins.ZcashTransparentMainTest"`
Expected: FAIL — compile error, class doesn't exist.

- [ ] **Step 3: Implement the coin type**

```java
package com.openwallet.core.coins;

import com.openwallet.core.coins.families.ZcashFamily;

/**
 * Zcash's TRANSPARENT layer as a Bitcoin-family coin, used as a hidden companion
 * account to the SDK-backed ZEC account. Riding ZcashFamily (extends BitFamily)
 * gives it the full ElectrumX stack — including WalletPocketHD's BIP-44 gap-limit
 * address discovery — so ALL t-addresses with history are found, not just the
 * single t-address the zcash-android-sdk watches.
 *
 * bip44Index deliberately equals ZcashMain's (133): account 0's first external
 * key here derives the exact same t-address the SDK shows, making discovery a
 * strict superset of the SDK's transparent view.
 */
public class ZcashTransparentMain extends ZcashFamily {
    private ZcashTransparentMain() {
        id = "zcashtransparent.main";

        addressHeader = 7352;         // 0x1CB8 -> t1 (P2PKH); 2-byte, split by ZcashAddress
        p2shHeader = 7357;            // 0x1CBD -> t3 (P2SH)
        acceptableAddressCodes = new int[] { addressHeader, p2shHeader };
        spendableCoinbaseDepth = 100;
        dumpedPrivateKeyHeader = 128;

        addressPrefix = "";
        name = "Zcash Transparent";
        symbol = "ZEC";
        uriScheme = "zcash";
        bip44Index = 133;
        unitExponent = 8;
        feeValue = value(1000);
        minNonDust = value(1000);
        softDustLimit = value(1000);
        softDustPolicy = SoftDustPolicy.BASE_FEE_FOR_EACH_SOFT_DUST_TXO;
        signedMessageHeader = toBytes("Zcash Signed Message:\n");
    }

    private static ZcashTransparentMain instance = new ZcashTransparentMain();
    public static synchronized CoinType get() {
        return instance;
    }
}
```

Register in `CoinID.java`: add enum constant following the existing pattern (find `ZCASH_MAIN` and add `ZCASH_TRANSPARENT_MAIN(ZcashTransparentMain.get())` beside it, with the import). CAUTION: `CoinID` may key by bip44Index or id — read `typeFromId`/constructor first; if the enum indexes by bip44Index and collides with ZCASH_MAIN's 133, register only in the id-keyed map path and note the collision handling in a comment (both types intentionally share 133).

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew :core:test --tests "com.openwallet.core.coins.ZcashTransparentMainTest"`
Expected: PASS (3 tests). Then full `.\gradlew :core:test` — bar: ≤36 failures, all pre-existing.

- [ ] **Step 5: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/coins/ZcashTransparentMain.java core/src/main/java/com/openwallet/core/coins/CoinID.java core/src/test/java/com/openwallet/core/coins/ZcashTransparentMainTest.java
git commit -m "feat(zec): ZcashTransparentMain coin type for full t-address discovery`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Auto-manage the companion account + server entry + fused ZEC display

**Files:**
- Modify: `wallet/src/main/java/com/openwallet/wallet/Constants.java` (DEFAULT_COINS_SERVERS entry for `ZcashTransparentMain`)
- Modify: `wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java` (auto-create companion in `injectZcashBackends`)
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/WalletActivity.java` (hide companion from the nav drawer's own row; it appears fused)
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java` (ZEC screen shows SDK balance + companion transparent balance, merged tx list)

**Interfaces:**
- Consumes: `Wallet.createAccounts(List<CoinType>, boolean, KeyParameter)`, `Wallet.getAccounts(CoinType)`, `ZcashTransparentMain.get()` (Task 2)
- Produces: invariant — a wallet with a `zcash.main` account always gains a `zcashtransparent.main` account on the next unlocked service pass; UI convention — accounts of type `ZcashTransparentMain` never render their own drawer row.

- [ ] **Step 1: Server entry**

In `Constants.DEFAULT_COINS_SERVERS` add (values from Task 1; shown with the probed defaults):

```java
            new CoinAddress(ZcashTransparentMain.get(),
                    new ServerAddress("zec-cce-1.coinomi.net", 5028, /*ssl per Task 1*/ false)),
```

Do NOT add `ZcashTransparentMain` to `SUPPORTED_COINS` (users never add it directly). DO give it an icon and explorer entry so any incidental rendering is sane:

```java
        COINS_ICONS.put(CoinID.ZCASH_TRANSPARENT_MAIN.getCoinType(), R.drawable.zcash);
        COINS_BLOCK_EXPLORERS.put(CoinID.ZCASH_TRANSPARENT_MAIN.getCoinType(), "https://blockchair.com/zcash/transaction/%s");
```

- [ ] **Step 2: Auto-create the companion account when ZEC syncs**

In `CoinServiceImpl.injectZcashBackends(Wallet wallet)`, after the seed-availability check succeeds (we know the wallet is unlocked or cached — the same gate the SDK backend uses), add before the account loop:

```java
        // Full transparent discovery: ensure the companion transparent account exists
        // for every ZEC account. Created lazily here because account creation needs
        // the unlocked hierarchy, which this method already guarantees.
        if (!wallet.getAccounts(ZcashMain.get()).isEmpty()
                && wallet.getAccounts(ZcashTransparentMain.get()).isEmpty()) {
            try {
                wallet.createAccounts(
                        java.util.Collections.<CoinType>singletonList(ZcashTransparentMain.get()),
                        true, null);
                wallet.saveLater();
                log.info("Created companion Zcash transparent discovery account");
            } catch (Exception e) {
                log.error("Could not create ZEC transparent companion: {}",
                        e.getClass().getSimpleName());
            }
        }
```

CAUTION — encrypted wallets: `createAccounts(..., null)` throws when the master key is encrypted. Wrap as shown (fail closed, retry next pass) and ALSO attempt creation in `AddCoinTask` (where the real `KeyParameter key` exists) right after a successful ZEC add:

```java
            if (type instanceof ZcashSdkFamily) {
                ZecSeedCache.capture(wallet.getSeedBytes(key));
                if (wallet.getAccounts(ZcashTransparentMain.get()).isEmpty()) {
                    wallet.createAccounts(java.util.Collections.<CoinType>singletonList(
                            ZcashTransparentMain.get()), true, key);
                }
            }
```

And in `WalletActivity.cacheZecSeedAsync`'s success path (the unlock prompt), same pattern with the derived key, before `connectAllCoinService()`.

- [ ] **Step 3: Hide the companion row in the nav drawer**

In `WalletActivity` where drawer items are built from `getAllAccounts()` (~line 225), skip it:

```java
        for (WalletAccount account : getAllAccounts()) {
            if (account.getCoinType() instanceof ZcashTransparentMain) continue; // fused into ZEC row
            NavDrawerItem.addItem(...)
        }
```

(Use the concrete class check, not `ZcashFamily`, so future YEC/ZCL rows still show.)

- [ ] **Step 4: Fuse balances on the ZEC screen**

In `BalanceFragment` (read it first — locate where the account balance is bound), when the displayed account's type is `ZcashMain`, ALSO fetch `walletApplication.getWallet().getAccounts(ZcashTransparentMain.get())` and if present:
- total shown = SDK balance + companion `getBalance()`
- transaction list = SDK txs + companion txs, sorted by timestamp descending (both lists already exist; merge in the adapter data source)
- add a one-line breakdown under the balance: `Shielded+UA: X · Transparent (discovered): Y` using a new string resource `zec_balance_breakdown` (`<string name="zec_balance_breakdown">Shielded: %1$s · Transparent: %2$s</string>`)

This is UI wiring with no JVM-testable seam — verification is compile + Task 12 device QA.

- [ ] **Step 5: Compile + core tests**

Run: `.\gradlew :core:test :wallet:assembleDebug`
Expected: BUILD SUCCESSFUL; ≤36 pre-existing failures.

- [ ] **Step 6: Commit**

```powershell
git add wallet/src/main/java/com/openwallet/wallet/Constants.java wallet/src/main/java/com/openwallet/wallet/service/CoinServiceImpl.java wallet/src/main/java/com/openwallet/wallet/ui/WalletActivity.java wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java wallet/src/main/java/com/openwallet/wallet/tasks/AddCoinTask.java wallet/src/main/res/values/strings.xml
git commit -m "feat(zec): auto-managed transparent discovery account fused into ZEC screen`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

**Phase A complete:** ZEC now shows the full transparent balance/history across all derivation-path addresses (gap-limit discovery via WalletPocketHD), alongside the SDK's shielded view.

---

### Task 4: Vendor a pure-Java BLAKE2b digest

**Files:**
- Create: `core/src/main/java/com/openwallet/core/crypto/Blake2b.java`
- Test: `core/src/test/java/com/openwallet/core/crypto/Blake2bTest.java`

**Interfaces:**
- Consumes: nothing (self-contained)
- Produces: `public final class Blake2b` with `Blake2b(int digestLengthBytes, byte[] personalization)` constructor, `void update(byte[] data, int off, int len)`, `byte[] digest()`. Personalization is the 16-byte ZIP-243 personal string (e.g. `"ZcashSigHash" + branchId LE`).

Why vendored: spongycastle 1.51 (pinned) predates Blake2bDigest. Port the Bouncy Castle `Blake2bDigest` class (MIT-licensed; single self-contained file) into the project namespace, adding the `personalization` constructor parameter (BC's version already supports `Blake2bDigest(byte[] key, int digestLength, byte[] salt, byte[] personalization)` — port that overload). Keep the BC license header comment.

- [ ] **Step 1: Write the failing test with official BLAKE2b test vectors**

```java
package com.openwallet.core.crypto;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class Blake2bTest {
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    @Test
    public void unkeyedEmptyInput512() {
        // RFC 7693 appendix / official BLAKE2b vector: BLAKE2b-512("")
        Blake2b d = new Blake2b(64, null);
        assertEquals(
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419" +
            "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce",
            hex(d.digest()));
    }

    @Test
    public void unkeyedAbc512() {
        // BLAKE2b-512("abc")
        Blake2b d = new Blake2b(64, null);
        d.update("abc".getBytes(), 0, 3);
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
            "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            hex(d.digest()));
    }

    @Test
    public void personalizedDigest32() {
        // Personalization changes the output; verify determinism + length.
        byte[] person = "ZcashSigHash0000".getBytes(); // 16 bytes
        Blake2b a = new Blake2b(32, person);
        Blake2b b = new Blake2b(32, person);
        a.update(new byte[]{1,2,3}, 0, 3);
        b.update(new byte[]{1,2,3}, 0, 3);
        assertEquals(hex(a.digest()), hex(b.digest()));
        assertEquals(64, hex(a.digest()).length());
    }
}
```

(The ZIP-243 vectors in Task 6 give end-to-end personalized coverage against consensus data; this task proves the core digest.)

- [ ] **Step 2: Run test to verify it fails** — compile error, class missing.

- [ ] **Step 3: Port the implementation**

Source: Bouncy Castle `org.bouncycastle.crypto.digests.Blake2bDigest` (fetch from the bouncycastle GitHub at tag `r1rv60`, file `core/src/main/java/org/bouncycastle/crypto/digests/Blake2bDigest.java`). Rename package to `com.openwallet.core.crypto`, strip the `Digest` interface (no BC deps), expose the constructor/update/digest described in Interfaces. Do not modify the algorithm internals.

- [ ] **Step 4: Run test to verify pass** — `.\gradlew :core:test --tests "com.openwallet.core.crypto.Blake2bTest"` → PASS.

- [ ] **Step 5: Commit**

```powershell
git add core/src/main/java/com/openwallet/core/crypto/ core/src/test/java/com/openwallet/core/crypto/
git commit -m "feat(crypto): vendor pure-Java BLAKE2b with personalization (for ZIP-243)`n`nCo-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Zcash v4 transparent transaction serialization

**Files:**
- Create: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashV4Transaction.java`
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/ZcashV4TransactionTest.java`

**Interfaces:**
- Consumes: bitcoinj `TransactionInput`/`TransactionOutput` byte conventions (reuse script builders), `Blake2b` (Task 4)
- Produces:

```java
public class ZcashV4Transaction {
    public static class TxIn {
        public final byte[] prevTxId;   // 32 bytes, little-endian as on the wire
        public final int prevIndex;
        public byte[] scriptSig;        // filled after signing
        public final long valueZat;     // needed for ZIP-243 amount field
        public final byte[] scriptPubKey; // of the UTXO being spent
        public TxIn(byte[] prevTxId, int prevIndex, long valueZat, byte[] scriptPubKey) {...}
    }
    public static class TxOut {
        public final long valueZat;
        public final byte[] scriptPubKey;
        public TxOut(long valueZat, byte[] scriptPubKey) {...}
    }
    public ZcashV4Transaction(int consensusBranchId, long expiryHeight);
    public void addInput(TxIn in);
    public void addOutput(TxOut out);
    public byte[] serialize();          // full v4 tx: header 0x80000004, nVersionGroupId 0x892F2085,
                                        // vin, vout, nLockTime=0, nExpiryHeight, valueBalance=0,
                                        // empty shielded spends/outputs/joinsplits
    public byte[] txId();               // double-SHA256 of serialize(), reversed for display
}
```

v4 wire layout (transparent-only) — implement exactly:

```
4  bytes  header       = 0x80000004 (LE; fOverwintered | version 4)
4  bytes  nVersionGroupId = 0x892F2085 (LE, Sapling)
varint    tx_in count, then per input: outpoint(32+4) + scriptSig(varint+bytes) + sequence(0xFFFFFFFE)
varint    tx_out count, then per output: value(8 LE) + scriptPubKey(varint+bytes)
4  bytes  nLockTime    = 0
4  bytes  nExpiryHeight (LE) — set to currentHeight + 40; 0 disables expiry
8  bytes  valueBalance = 0
varint    nShieldedSpend  = 0
varint    nShieldedOutput = 0
varint    nJoinSplit      = 0
(no binding sig when all shielded counts are zero)
```

- [ ] **Step 1: Write the failing test** — construct a 1-in/1-out tx with fixed bytes, assert `serialize()` hex equals a hand-computed expected string (compute the expected hex IN THE TEST from the layout above with a helper, then lock it as a constant after first green run), and assert header/versionGroupId little-endian bytes at offsets 0–7 are `04 00 00 80 85 20 2F 89`.

- [ ] **Step 2–4: fail → implement → pass** (standard cycle; run `.\gradlew :core:test --tests "...ZcashV4TransactionTest"`).

- [ ] **Step 5: Commit** — `feat(zec): v4 Sapling-format transparent transaction serialization`.

---

### Task 6: ZIP-243 sighash + signer, verified against official test vectors

**Files:**
- Create: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashTxSigner.java`
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/Zip243SighashTest.java`
- Test resource: `core/src/test/resources/zip_0243_vectors.json` (fetched in Step 1)

**Interfaces:**
- Consumes: `Blake2b` (Task 4), `ZcashV4Transaction` (Task 5), bitcoinj `ECKey.sign(Sha256Hash)` (works on any 32-byte digest)
- Produces:

```java
public class ZcashTxSigner {
    // Branch IDs recorded in Step 1 with citations; NEVER invent these.
    public static final int BRANCH_ID_ZEC_CURRENT = /* Step 1 */;
    public static final int BRANCH_ID_YEC_CURRENT = /* Step 1 */;
    public static final int BRANCH_ID_ZCL_SAPLING = 0x76B809BB; // verify in Step 1

    /** ZIP-243 SIGHASH_ALL digest for input inputIndex of tx. */
    public static byte[] sighash(ZcashV4Transaction tx, int inputIndex, int consensusBranchId);

    /** Signs every input (P2PKH) with the matching key; fills scriptSigs; returns raw tx bytes. */
    public static byte[] signAll(ZcashV4Transaction tx, java.util.List<org.bitcoinj.core.ECKey> keys,
                                 int consensusBranchId);
}
```

ZIP-243 SIGHASH_ALL layout (BLAKE2b-256, personalization = `"ZcashSigHash"` + 4-byte LE branchId):

```
header | nVersionGroupId
hashPrevouts  = Blake2b-256("ZcashPrevoutHash",  concat(outpoints))
hashSequence  = Blake2b-256("ZcashSequencHash",  concat(sequences))
hashOutputs   = Blake2b-256("ZcashOutputsHash",  concat(serialized outputs))
hashJoinSplits = 32 zero bytes (none)
hashShieldedSpends = 32 zero bytes
hashShieldedOutputs = 32 zero bytes
nLockTime | nExpiryHeight | valueBalance(0) | sigHashType(1, 4 bytes LE)
then for the input being signed: outpoint | scriptCode(P2PKH of the UTXO) | value(8 LE) | sequence
```

- [ ] **Step 1: Fetch authoritative data (WebFetch/browser, record citations as code comments)**
  - ZIP-243 test vectors: `https://raw.githubusercontent.com/zcash/zcash-test-vectors/master/test-vectors/json/zip_0243.json` → save to `core/src/test/resources/zip_0243_vectors.json`
  - Current ZEC consensus branch ID: https://zips.z.cash/zip-0253 (or the latest NU zip; cross-check `https://api.blockchair.com/zcash/stats` upgrade info)
  - YEC branch ID: Ycash forked at ZEC height 570,000; its network upgrades are listed at https://github.com/ycashfoundation/ycash (src/consensus/upgrades.cpp) — read the LATEST branch id
  - ZCL: confirm Sapling branch `0x76B809BB` is still current via a recent ZCL block explorer raw tx

- [ ] **Step 2: Write the failing vector test** — parse `zip_0243_vectors.json`; for each vector containing a transparent input (fields: raw tx, branchId, input index, scriptCode, amount, expected sighash), rebuild via `ZcashV4Transaction` OR (simpler, vectors carry raw bytes) drive `sighash()`'s component hashing from the vector's raw fields and assert the expected digest. At least 4 vectors must be exercised; skip-with-failure if the resource file is missing (never silently pass).

- [ ] **Step 3: Run to verify fail.**

- [ ] **Step 4: Implement `sighash` + `signAll`** per the layout above. `signAll` builds standard P2PKH scriptSigs: `push(DER sig + 0x01 hashType) push(pubkey)`.

- [ ] **Step 5: Run vectors to green**, then full `.\gradlew :core:test` (bar: ≤36 pre-existing failures).

- [ ] **Step 6: Commit** — `feat(zec): ZIP-243 sighash + transparent signer with official test vectors`.

---

### Task 7: ZEC transparent sweep (discovered UTXOs → SDK t-address)

**Files:**
- Create: `core/src/main/java/com/openwallet/core/wallet/families/zcash/ZcashSweeper.java`
- Modify: `wallet/src/main/java/com/openwallet/wallet/ui/BalanceFragment.java` (a "Sweep transparent funds" action visible when companion balance > 0)
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/ZcashSweeperTest.java`

**Interfaces:**
- Consumes: `WalletPocketHD.getUnspentOutputs()` + `findKeyFromPubHash` (companion account, Task 3), `ZcashTxSigner.signAll` (Task 6), `ZcashSdkWallet.getTransparentAddress()` (SDK target), `ServerClient` broadcast path (`broadcastTxSync` accepts raw bytes — read `BitWalletBase.broadcastTxSync` first and reuse its transport; if it insists on a bitcoinj `Transaction`, add a raw-bytes overload to the connection interface: `boolean broadcastRawTx(byte[] tx)` implemented in `ServerClient` via `blockchain.transaction.broadcast` with hex)
- Produces:

```java
public class ZcashSweeper {
    /** Builds + signs a v4 tx spending ALL companion UTXOs to destinationTAddress.
     *  Fee: 1000 zat per 1000 bytes, min 10000 zat (transparent txs are cheap; ZIP-317
     *  applies to actions — transparent-only v4 uses legacy fee rules; verify current
     *  relay policy in Task 1's server via 'blockchain.relayfee'). Returns raw bytes. */
    public static byte[] buildSweep(WalletPocketHD companion, String destinationTAddress,
                                    int consensusBranchId, long expiryHeight,
                                    @Nullable KeyParameter aesKey) throws WalletAccountException;
}
```

- [ ] **Step 1: Failing test** with a fixture `WalletPocketHD` holding 2 fake UTXOs (follow `SweepWalletTest`'s fixture style — if that class's fixtures are among the 36 broken ones, build the pocket via the `abandon...about` mnemonic used in `ZcashSdkWalletSerializationTest` and inject UTXOs via the `addUnspentOutput` package-private hook). Assert: output count 1, value = sum − fee, all inputs signed (scriptSig non-empty), `txId()` is 64 hex chars.

- [ ] **Step 2–4: fail → implement → pass.**

- [ ] **Step 5: UI hook** — in `BalanceFragment`, when companion balance > 0 show a button (string `zec_sweep_transparent`: `Sweep transparent funds to your Zcash account`); on tap, run sweep on a background thread (NEVER the UI thread; NEVER holding a lock across broadcast — see Global Constraints), password-prompt if `wallet.isEncrypted()` (reuse the Task-C unlock pattern from `WalletActivity.cacheZecSeedAsync`), then toast the txid on success.

- [ ] **Step 6: Compile + tests + commit** — `feat(zec): sweep discovered transparent UTXOs to the SDK account`.

**Phase B complete:** every t-UTXO on any derivation index is discoverable AND spendable.

---

### Task 8: Zclassic (ZCL) coin definition

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/ZclassicMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinID.java`
- Modify: `wallet/src/main/java/com/openwallet/wallet/Constants.java` (servers, SUPPORTED_COINS, icon, explorer)
- Create: icon `wallet/src/main/res/drawable/zclassic.xml` (vector: ZCL brand blue-grey circle `#4D4D4E`, white "Z" — copy `zcash.xml` structure, swap colors; refine in QA)
- Test: `core/src/test/java/com/openwallet/core/coins/ZclassicMainTest.java`

**Interfaces:**
- Consumes: `ZcashFamily`, Task 1 server confirmation (GATE: skip this task if Task 1 declared ZCL non-viable)
- Produces: `ZclassicMain.get()` — id `zclassic.main`, bip44 147, t1/t3 prefixes 0x1CB8/0x1CBD, symbol `ZCL`, uri `zclassic`; user-addable coin.

- [ ] **Step 1: Failing test** (mirror `ZcashTransparentMainTest`: parameters, address parse of a known ZCL t1-address from a live explorer, CoinID registration).

- [ ] **Step 2–4: fail → implement → pass.** Implementation is `ZcashTransparentMain` with: `id="zclassic.main"; name="Zclassic"; symbol="ZCL"; uriScheme="zclassic"; bip44Index=147; feeValue=value(10000); minNonDust=value(1000)`.

- [ ] **Step 5: Registry** — `CoinID` entry `ZCLASSIC_MAIN`; `Constants`: server `new CoinAddress(ZclassicMain.get(), new ServerAddress("zcl-cce-1.coinomi.net", 5015, /*per Task 1*/ false))`, add to `SUPPORTED_COINS` after ZEC, icon + explorer (`COINS_BLOCK_EXPLORERS`: use the explorer confirmed live in Task 1, `%s` for txid).

- [ ] **Step 6: Compile + tests + commit** — `feat: add Zclassic (ZCL) via Zcash-family ElectrumX stack`.

---

### Task 9: ZCL sends use the ZIP-243 signer

**Files:**
- Modify: `core/src/main/java/com/openwallet/core/wallet/WalletPocketHD.java` OR the send path in `BitWalletBase.completeAndSignTx` (read first; pick the narrowest seam where a `ZcashFamily` coin's tx is signed)
- Test: `core/src/test/java/com/openwallet/core/wallet/families/zcash/ZcashFamilySendTest.java`

**Interfaces:**
- Consumes: `ZcashTxSigner` (Task 6), existing `SendRequest` flow
- Produces: sends from any `ZcashFamily`-typed pocket (ZCL, YEC, ZEC-companion) produce v4/ZIP-243 transactions instead of bitcoin-format ones. Branch id resolved per coin: add to `ZcashFamily` an abstract `public int getConsensusBranchId();` implemented by each coin from `ZcashTxSigner` constants.

- [ ] **Step 1: Failing test** — build a `ZclassicMain` pocket with a faked UTXO (Task 7 fixture pattern), run the send-request → complete → sign path, assert the produced raw tx starts with `04 00 00 80 85 20 2F 89` (v4 header) and NOT a bitcoin version byte.

- [ ] **Step 2–4: fail → implement → pass.** Implementation approach: in the signing seam, branch on `type instanceof ZcashFamily` → translate the bitcoinj `Transaction`'s inputs/outputs into `ZcashV4Transaction` (values + scripts carry over; scriptCode = UTXO's scriptPubKey), sign via `ZcashTxSigner.signAll` with keys fetched by pubkey-hash from the pocket, and stash the raw bytes on the `SendRequest` for broadcast via the raw-bytes path added in Task 7. Do NOT alter behavior for plain `BitFamily` coins — guard every change behind the `instanceof ZcashFamily` check and prove it with an existing-coin regression assertion in the test (a `BitcoinMain` send still produces a legacy tx).

- [ ] **Step 5: Full `.\gradlew :core:test`** (bar: ≤36) **+ commit** — `feat(zcl,zec): ZcashFamily sends produce ZIP-243 v4 transactions`.

**Phase C complete:** ZCL is a fully usable coin (add, receive with full discovery, send).

---

### Task 10: YEC infrastructure on the VPS (ops, no app code)

**Files:** none in this repo — VPS work (74.208.146.8, SSH alias `balr-vps`), documented in `D:\NewYorkCoin_2026\To_Do.md`.

**Interfaces:**
- Consumes: existing VPS (runs nycd + electrumx for NYC; ~6 GB already committed to nycd — CHECK free RAM/disk first: `free -h`, `df -h`)
- Produces: `electrum-yec.paywith.nyc:50012` (SSL) speaking Electrum protocol 1.4 for the Ycash chain.

- [ ] **Step 1: Capacity check** — ycashd full node needs ~15 GB disk / ~2 GB RAM; ElectrumX-yec ~5 GB disk. If the VPS can't fit, STOP and report options (bigger VPS, second box, or defer YEC).
- [ ] **Step 2: Build/run ycashd** (github.com/ycashfoundation/ycash releases; systemd unit `ycashd.service` mirroring `newyorkcoin.service`, `txindex=1`).
- [ ] **Step 3: ElectrumX instance** — second systemd unit `electrumx-yec.service` with `COIN=Ycash` (ElectrumX supports Ycash via its coin registry; if the installed ElectrumX version lacks the Ycash class, use the `electrumx` fork referenced by the Ycash community — record which). SSL cert via the existing letsencrypt setup, nginx stream or direct port 50012.
- [ ] **Step 4: Verify** — `server.version` handshake from the dev machine (Task 1 Step 1 technique) + height matches a Ycash explorer.
- [ ] **Step 5: Document** in `To_Do.md` (service names, ports, cert renewal) and update this plan's Global Constraints server line.

---

### Task 11: Ycash (YEC) coin definition + sends

**Files:**
- Create: `core/src/main/java/com/openwallet/core/coins/YcashMain.java`
- Modify: `core/src/main/java/com/openwallet/core/coins/CoinID.java`, `wallet/src/main/java/com/openwallet/wallet/Constants.java`
- Create: icon `wallet/src/main/res/drawable/ycash.xml` (Ycash brand: yellow `#F5B300` circle, dark "Y")
- Test: `core/src/test/java/com/openwallet/core/coins/YcashMainTest.java`

**Interfaces:**
- Consumes: `ZcashFamily`, Task 9's signing seam (works automatically once `getConsensusBranchId()` returns `BRANCH_ID_YEC_CURRENT`), Task 10 server (GATE: do not add to `SUPPORTED_COINS` until Task 10's server is live)
- Produces: `YcashMain.get()` — id `ycash.main`, bip44 347, prefixes 0x1C28 (s1) / 0x1C2D (s3), symbol `YEC`, uri `ycash`.

- [ ] **Step 1: Failing test** — parameters; parse a real s1-address (grab one from a Ycash explorer, e.g. the Ycash faucet's donation address, and note it in the test comment); CoinID registration; `getConsensusBranchId() == ZcashTxSigner.BRANCH_ID_YEC_CURRENT`.
- [ ] **Step 2–4: fail → implement → pass.** Body mirrors Task 8 with the YEC values above (`feeValue = value(1000)`).
- [ ] **Step 5: Registry** — server `new CoinAddress(YcashMain.get(), new ServerAddress("electrum-yec.paywith.nyc", 50012, true))`, SUPPORTED_COINS, icon, explorer (Task 10's verified explorer).
- [ ] **Step 6: Compile + tests + commit** — `feat: add Ycash (YEC) on self-hosted ElectrumX`.

---

### Task 12: Device QA + release (manual, Pixel Fold `37131FDHS000J5`)

- [ ] **Step 1: ZEC discovery** — user's real seed: transparent balance/history must now include the historical t-addresses (compare against the reference established during v1.8.5 QA). Breakdown line shows shielded vs transparent split. No ANRs while both scanners run (watch `adb logcat` ActivityManager for 10 min).
- [ ] **Step 2: ZEC sweep** — sweep discovered funds to the SDK t-address; confirm on blockchair.com/zcash; then shield in-app if desired.
- [ ] **Step 3: ZCL** — add coin, receive a small amount (acquire via exchange or community faucet), send it back out; verify both txids on the ZCL explorer. If no ZCL can be acquired, downgrade to: address matches the same seed's ZCL address in a reference wallet + tx broadcast of a zero-fee-safe dust send is DEFERRED with a release-notes caveat.
- [ ] **Step 4: YEC** — same as ZCL against the self-hosted server.
- [ ] **Step 5: Regression smoke** — NYC/BTC/LTC/DOGE still connect; ZEC shielded flows from v1.8.5 QA unaffected.
- [ ] **Step 6: Version bump** (`wallet/build.gradle`: versionCode 64, versionName "v1.9.0"), `.\gradlew :core:test` (bar ≤36) + `:wallet:assembleRelease`, commit `release: v1.9.0 — full Zcash-family UTXO discovery, YEC, ZCL`, push, Play Store checklist per `To_Do.md` §4.

---

## Known risks / decisions locked in

- **Branch IDs are fetched, not remembered** (Task 6 Step 1). A wrong branch id produces txs the network silently rejects — the vector tests plus a real dust-send in QA are the safety net.
- **ZCL chain viability** is checked FIRST (Task 1 Step 2); Phase C aborts cleanly if the chain/servers are dead.
- **YEC is gated on self-hosted infra** (Task 10) — no public ElectrumX exists (probed 2026-07-03).
- **coinomi CCE servers are legacy infrastructure** that may vanish; the VPS pattern from Task 10 is the template for self-hosting ZEC/ZCL ElectrumX later if needed.
- **Shielded YEC/ZCL is out of scope** — no mobile SDK exists for either fork; transparent-only matches what Coinomi historically offered.
- **Multiple ZEC account indices (account' > 0)**: WalletPocketHD discovers within one account; probing additional account indices is deferred — noted for a future release (the SDK equally only opens account 0).
