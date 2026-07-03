package com.openwallet.core.crypto;

/**
 * Pure-Java BLAKE2b (RFC 7693) with BLAKE2 personalization, self-contained (no
 * external crypto deps). Needed because the pinned spongycastle 1.51 predates
 * {@code Blake2bDigest}.
 *
 * <p>Clean-room implementation from RFC 7693 (the algorithm is public-domain
 * reference) rather than a port of Bouncy Castle's {@code Blake2bDigest}; it
 * exposes exactly the surface ZIP-243 needs — an unkeyed digest of a chosen
 * output length with a 16-byte personalization string. Salt and keying are not
 * supported (not required by ZIP-243) and are intentionally omitted.
 *
 * <p>Verified against the RFC 7693 / official BLAKE2b-512 vectors for "" and
 * "abc" (see {@code Blake2bTest}); ZIP-243 test vectors (Task 6) exercise the
 * personalized path end-to-end.
 *
 * Not thread-safe. One instance produces one digest; {@link #digest()} may be
 * called repeatedly and returns the same finalized value.
 */
public final class Blake2b {

    private static final int BLOCK_SIZE = 128;

    private static final long[] IV = {
        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L,
        0x510e527fade682d1L, 0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
    };

    private static final byte[][] SIGMA = {
        { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,15},
        {14,10, 4, 8, 9,15,13, 6, 1,12, 0, 2,11, 7, 5, 3},
        {11, 8,12, 0, 5, 2,15,13,10,14, 3, 6, 7, 1, 9, 4},
        { 7, 9, 3, 1,13,12,11,14, 2, 6, 5,10, 4, 0,15, 8},
        { 9, 0, 5, 7, 2, 4,10,15,14, 1,11,12, 6, 8, 3,13},
        { 2,12, 6,10, 0,11, 8, 3, 4,13, 7, 5,15,14, 1, 9},
        {12, 5, 1,15,14,13, 4,10, 0, 7, 6, 3, 9, 2, 8,11},
        {13,11, 7,14,12, 1, 3, 9, 5, 0,15, 4, 8, 6, 2,10},
        { 6,15,14, 9,11, 3, 0, 8,12, 2,13, 7, 1, 4,10, 5},
        {10, 2, 8, 4, 7, 6, 1, 5,15,11, 9,14, 3,12,13, 0}
    };

    private final int digestLength;
    private final long[] h = new long[8];
    private final byte[] buffer = new byte[BLOCK_SIZE];
    private int bufferPos = 0;
    private long counter = 0;       // total bytes absorbed (t0); t1 assumed 0 (< 2^64 bytes)
    private byte[] finalized = null;

    /**
     * @param digestLengthBytes output length in bytes, 1..64
     * @param personalization   16-byte BLAKE2 personal string, or {@code null} for none
     */
    public Blake2b(int digestLengthBytes, byte[] personalization) {
        if (digestLengthBytes < 1 || digestLengthBytes > 64) {
            throw new IllegalArgumentException("digest length must be 1..64 bytes");
        }
        if (personalization != null && personalization.length != 16) {
            throw new IllegalArgumentException("personalization must be exactly 16 bytes");
        }
        this.digestLength = digestLengthBytes;

        System.arraycopy(IV, 0, h, 0, 8);
        // Parameter block word 0: digestLength | keyLength(0) | fanout(1) | depth(1)
        h[0] ^= (digestLengthBytes & 0xFFL) | (1L << 16) | (1L << 24);
        if (personalization != null) {
            // Parameter block bytes 48..63 = personalization → words 6 and 7
            h[6] ^= leLong(personalization, 0);
            h[7] ^= leLong(personalization, 8);
        }
    }

    public void update(byte[] data, int off, int len) {
        if (finalized != null) throw new IllegalStateException("digest already finalized");
        for (int i = 0; i < len; i++) {
            if (bufferPos == BLOCK_SIZE) {          // full block, and more data follows
                counter += BLOCK_SIZE;
                compress(false);
                bufferPos = 0;
                java.util.Arrays.fill(buffer, (byte) 0);
            }
            buffer[bufferPos++] = data[off + i];
        }
    }

    /** Finalizes (idempotent) and returns the digest. */
    public byte[] digest() {
        if (finalized == null) {
            counter += bufferPos;
            // remaining buffer tail is already zero (initial state / zero-filled on reset)
            compress(true);
            byte[] out = new byte[digestLength];
            for (int i = 0; i < digestLength; i++) {
                out[i] = (byte) (h[i >>> 3] >>> (8 * (i & 7)));
            }
            finalized = out;
        }
        return finalized.clone();
    }

    private void compress(boolean last) {
        long[] m = new long[16];
        for (int i = 0; i < 16; i++) m[i] = leLong(buffer, i * 8);

        long[] v = new long[16];
        System.arraycopy(h, 0, v, 0, 8);
        System.arraycopy(IV, 0, v, 8, 8);
        v[12] ^= counter;           // t0
        // v[13] ^= t1 (0)
        if (last) v[14] ^= 0xFFFFFFFFFFFFFFFFL;

        for (int round = 0; round < 12; round++) {
            byte[] s = SIGMA[round % 10];
            g(v, 0, 4,  8, 12, m[s[0]],  m[s[1]]);
            g(v, 1, 5,  9, 13, m[s[2]],  m[s[3]]);
            g(v, 2, 6, 10, 14, m[s[4]],  m[s[5]]);
            g(v, 3, 7, 11, 15, m[s[6]],  m[s[7]]);
            g(v, 0, 5, 10, 15, m[s[8]],  m[s[9]]);
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]]);
            g(v, 2, 7,  8, 13, m[s[12]], m[s[13]]);
            g(v, 3, 4,  9, 14, m[s[14]], m[s[15]]);
        }

        for (int i = 0; i < 8; i++) h[i] ^= v[i] ^ v[i + 8];
    }

    private static void g(long[] v, int a, int b, int c, int d, long x, long y) {
        v[a] = v[a] + v[b] + x;
        v[d] = Long.rotateRight(v[d] ^ v[a], 32);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 24);
        v[a] = v[a] + v[b] + y;
        v[d] = Long.rotateRight(v[d] ^ v[a], 16);
        v[c] = v[c] + v[d];
        v[b] = Long.rotateRight(v[b] ^ v[c], 63);
    }

    private static long leLong(byte[] b, int off) {
        long r = 0;
        for (int i = 0; i < 8; i++) r |= (b[off + i] & 0xFFL) << (8 * i);
        return r;
    }
}
