package com.openwallet.wallet.util;

import java.util.Arrays;

import javax.annotation.Nullable;

/**
 * Process-lifetime, memory-only cache of the decrypted BIP-39 seed for the Zcash
 * SDK backend on ENCRYPTED wallets.
 *
 * The zcash-android-sdk needs the raw seed to run its synchronizer, but an
 * encrypted wallet cannot hand it out without the user's password. This cache is
 * populated whenever the user enters their spending password (e.g. adding the
 * Zcash coin) and read only by CoinServiceImpl's backend injection.
 *
 * SECURITY (SOC-2):
 * - Never written to disk, logs, or any Intent/Bundle — heap memory only.
 * - Cleared automatically on process death; {@link #clear()} zeroes it eagerly.
 * - Defensive copies on both set and get so no caller can mutate or retain the
 *   internal array reference.
 *
 * Limitation: after an app restart the cache is empty, so ZEC sync on an
 * encrypted wallet stays paused until the next password entry re-populates it.
 */
public final class ZecSeedCache {
    @Nullable private static volatile byte[] seed;

    private ZecSeedCache() {}

    /** Stores a copy of the seed. Ignores null or empty input (fail closed). */
    public static void capture(@Nullable byte[] seedBytes) {
        if (seedBytes == null || seedBytes.length == 0) return;
        seed = Arrays.copyOf(seedBytes, seedBytes.length);
    }

    /** Returns a copy of the cached seed, or null if none captured this session. */
    @Nullable
    public static byte[] get() {
        byte[] s = seed;
        return s == null ? null : Arrays.copyOf(s, s.length);
    }

    /** Zeroes and drops the cached seed. */
    public static void clear() {
        byte[] s = seed;
        seed = null;
        if (s != null) Arrays.fill(s, (byte) 0);
    }
}
