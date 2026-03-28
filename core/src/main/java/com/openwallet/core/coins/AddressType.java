package com.openwallet.core.coins;

/**
 * Address encoding types supported per coin.
 * TAPROOT is receive-display only — no spending path in this release.
 */
public enum AddressType {
    LEGACY,        // P2PKH, Base58Check
    COMPATIBLE,    // P2SH-P2WPKH, Base58Check P2SH
    NATIVE_SEGWIT, // P2WPKH, bech32 witness version 0
    TAPROOT        // P2TR, bech32m witness version 1 (receive only)
}
