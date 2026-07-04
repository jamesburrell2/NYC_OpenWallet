# ZipherX-backed Zclassic (ZCL) Integration Plan

**Goal:** Replace the ElectrumX-based ZCL approach with the ZipherX Rust wallet core (serverless Direct-P2P, shielded + transparent) embedded via UniFFI — the same backend-delegate pattern OpenWallet uses for ZEC (`ZcashSdkBackendImpl`). Solves the dead-ElectrumX-server problem permanently and adds shielded ZCL (previously out of scope).

**Decision (2026-07-04, user):** "Go the ZipherX route."

## Feasibility — PROVEN (2026-07-04)
- ZipherX `zipherx_multi` is **MIT-licensed**, Rust workspace, uses **UniFFI 0.28** → Kotlin bindings.
- Full wallet API in `crates/zipherx-ffi/src/zipherx.udl`: `restore_wallet`, `derive_transparent_address`, `start_sync(callback)`, `get_sync_progress`, `get_balance`, `get_transaction_history`, `send_transparent_with_progress`, `send_with_progress` (shielded), `add_custom_peer`, WIF import/export, Tor.
- **Kotlin bindings generated host-native** (no NDK): `cargo run -p uniffi-bindgen -- generate crates/zipherx-ffi/src/zipherx.udl --language kotlin` → `uniffi/zipherx/zipherx.kt` (148 KB, package `uniffi.zipherx`, JNA-based, loads `uniffi_zipherx`).
- **Prebuilt `.so` extracted from official v1.1.0 APK**: `arm64-v8a` (device) + `x86_64` (emulator). NO `armeabi-v7a` (32-bit ARM devices won't get ZCL).
- Staged in scratchpad: `zipherx-staging/{jniLibs,kotlin}`.

## Provenance / supply chain (SOC-2)
Pin to ZipherX v1.1.0. SHA-256:
- APK `ZipherX-1.1.0-release.apk`: `1dbfdf7d48125d8f15baacb8e9c24347b2e1a7eee43efe1ee3b674c500b763af`
- `arm64-v8a/libuniffi_zipherx.so`: `4ff062c0944dfff35191bcb824db58ff05028dcb637d2ed13adb4e69b7ed127b`
- `x86_64/libuniffi_zipherx.so`: `a914d524fa324f043a484284e497b51b2e9a47d84577c69ffb0afcab7dd40397`
- **Decision needed:** vendoring ~30 MB of third-party native binaries into the repo/app. Options: (a) commit `.so` into `wallet/src/main/jniLibs` (simple, bloats repo), (b) build from source in CI (rustup android targets + cargo-ndk + NDK 26.1.10909125 — matches ZipherX's own release.yml), (c) Git LFS. Recommend (b) for reproducibility once CI is set up; (a) for a fast device test now.

## Tasks

- [ ] **T1 — Vendor FFI artifacts into the wallet module.** Copy `libuniffi_zipherx.so` into `wallet/src/main/jniLibs/{arm64-v8a,x86_64}/`; add `zipherx.kt` under `wallet/src/main/java/uniffi/zipherx/`. Add JNA dep (`net.java.dev.jna:jna:5.x@aar`) to `wallet/build.gradle`. Set `ndk { abiFilters 'arm64-v8a','x86_64' }` (or ensure no other ABIs ship without the lib). Verify `:wallet:assembleDebug` packages the `.so`.
- [ ] **T2 — `ZclassicBackendDelegate` interface** (core, mirror `ZcashBackendDelegate`): setUpdateListener, startSync/stopSync, isConnected/isLoading, getSyncProgressPercent, getBalanceZatoshi, getReceiveAddress/getTransparentAddress, getTransactions, sendTo, getLastError.
- [ ] **T3 — `ZclassicBackendImpl.kt`** (wallet, mirror `ZcashSdkBackendImpl`): wrap `uniffi.zipherx` — `initializeRuntime`, `initializeWallet(config)`, `restoreWallet(words)`, `startSync(SyncProgressCallback)`, `getBalance`, `getTransactionHistory`, `sendTransparentWithProgress`. Same crash-guard hardening as ZEC (CoroutineExceptionHandler + per-collector try/catch). SOC-2: no seed/key logging.
- [ ] **T4 — `ZclassicSdkWallet` + `ZclassicSdkFamily`** (core, mirror `ZcashSdkWallet`/`ZcashSdkFamily`): AbstractWallet subclass driven by the delegate. Rework `ZclassicMain` to extend `ZclassicSdkFamily` (NOT `ZcashFamily`/ElectrumX). Keep bip44 147, t1/t3 prefixes, symbol ZCL.
- [ ] **T5 — Service + account wiring** (`CoinServiceImpl`): inject the ZCL backend on connect (mirror `injectZcashBackends`); seed capture; account creation. `WalletActivity`/unlock paths as needed.
- [ ] **T6 — UI**: receive (t + shielded addresses), balance (reuses N1 sync %), send (transparent + shielded). Add ZCL to `Constants.SUPPORTED_COINS` + icon + explorer.
- [ ] **T7 — Device QA** on Pixel Fold (arm64): add ZCL, sync via P2P (peer count > 0), receive, send a dust tx, verify on a ZCL explorer.

## Mapped FFI API (uniffi.zipherx, from generated zipherx.kt — verified 2026-07-04)

Lifecycle: `setPlatformStorage(cb)` → `initializeRuntime()` → `initializeWallet(WalletConfigFfi)` → `restoreWallet(words: List<String>)` → `startSync(SyncProgressCallback)`. Then `getBalance()`, `getTransactionHistory(limit,offset)`, `sendTransparentWithProgress(...)`. `stopSync()`, `getSyncProgress(): Double`, `getConnectedPeerCount()`, `addCustomPeer(host,port)`.

- **`WalletConfigFfi`**(`dbPath`, `headerStorePath`, `deltaStoreDir`, `spendParamsPath`, `outputParamsPath`, `accountIndex: UInt`, `dbEncryptionKey: List<UByte>?`, `boostCacheDir: String?`). Paths live under the app's files dir.
- **`PlatformStorageCallback`** (APP MUST IMPLEMENT): `loadKey(key): List<UByte>?`, `storeKey(key,value): Boolean`, `deleteKey(key): Boolean`, `hasKey(key): Boolean`. Back with EncryptedSharedPreferences/files. SOC-2: this stores key material — encrypt at rest, never log.
- **`SyncProgressCallback`**: `onProgress(phase, current, target)`, `onComplete(height)`, `onError(message)`, `onMempoolTx(txid, amount)`.
- **`BalanceInfo`**(total: ULong, spendable, noteCount, spendableNoteCount). **`TransactionDisplayFfi`**(txid, txType, amount, fee, address?, memo?, confirmations, height, timestamp).
- Kotlin unsigned types (UByte/ULong) at the boundary — convert to Long/ByteArray for the Java wallet layer.

### Key design decisions before T3
1. **Sapling params**: shielded send needs `sapling-spend.params` (~48 MB) + `sapling-output.params` (~3.6 MB). **First cut = TRANSPARENT-ONLY** (skip params; only `sendTransparentWithProgress`) to avoid a 50 MB download/bundle. Add shielded later. Confirm `initializeWallet` tolerates missing param files when only doing transparent ops (device test).
2. **Storage backend**: implement `PlatformStorageCallback` over `androidx.security:security-crypto` EncryptedSharedPreferences (already a dep).
3. **Seed source**: reuse the wallet's existing BIP39 seed (same seed as other coins) via `restoreWallet(words)` — ZCL account rides the one wallet seed, like ZEC.

## Notes / risks
- Supersedes the ElectrumX `ZclassicMain` (`87d38ff`) and the ZIP-243 signer *for ZCL* — the signer stays for YEC.
- ZipherX is ZCL-only; YEC still needs its own path (Tasks 10–11).
- JNA + native libs add app size (~14 MB/ABI) and a native crash surface — keep the crash-guard discipline.
- 32-bit ARM devices unsupported by ZipherX v1.1.0.
