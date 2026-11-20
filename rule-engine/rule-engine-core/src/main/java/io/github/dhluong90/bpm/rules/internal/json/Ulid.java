package io.github.dhluong90.bpm.rules.internal.json;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Generates ULIDs: 48 bits of milliseconds and 80 random bits in Crockford base 32 (26 characters). Ids are
 * monotonic per JVM: within one millisecond the random part is incremented, so ids sort in creation order.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastTime = Long.MIN_VALUE;
    private static byte[] lastRandom = new byte[10];

    private Ulid() {
    }

    private static byte[] increment(byte[] previous) {
        byte[] next = previous.clone();
        for (int i = next.length - 1; i >= 0; i--) {
            if (++next[i] != 0) {
                return next;
            }
        }
        throw new IllegalStateException("ULID random part exhausted within one millisecond");
    }

    /**
     * Creates a ULID for an instant.
     *
     * @param at the instant whose milliseconds lead the id
     * @return the 26-character id
     */
    public static synchronized String next(Instant at) {
        long time = Math.max(at.toEpochMilli(), lastTime);
        byte[] random;
        if (time == lastTime) {
            random = increment(lastRandom);
        } else {
            random = new byte[10];
            RANDOM.nextBytes(random);
        }
        lastTime = time;
        lastRandom = random;
        char[] out = new char[26];
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits as 16 characters of 5 bits.
        long high = 0;
        for (int i = 0; i < 5; i++) {
            high = (high << 8) | (random[i] & 0xFF);
        }
        long low = 0;
        for (int i = 5; i < 10; i++) {
            low = (low << 8) | (random[i] & 0xFF);
        }
        for (int i = 17; i >= 10; i--) {
            out[i] = ALPHABET[(int) (high & 31)];
            high >>>= 5;
        }
        for (int i = 25; i >= 18; i--) {
            out[i] = ALPHABET[(int) (low & 31)];
            low >>>= 5;
        }
        return new String(out);
    }

    /**
     * Tells whether a text is a ULID as this class writes them: 26 characters of the Crockford base 32 alphabet in
     * upper case, the first one at most {@code 7} so the 48-bit time fits.
     *
     * @param text the text, possibly null
     * @return true for a well-formed ULID
     */
    public static boolean isValid(String text) {
        if (text == null || text.length() != 26 || text.charAt(0) > '7') {
            return false;
        }
        for (int i = 0; i < 26; i++) {
            if (java.util.Arrays.binarySearch(ALPHABET, text.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the millisecond a well-formed ULID starts with. For an id of this class it is at or after the instant it
     * was created for, and equal to it unless the clock went back within the process.
     *
     * @param ulid a ULID that {@link #isValid(String)} accepts
     * @return the instant of its first 48 bits
     */
    public static Instant timeOf(String ulid) {
        long time = 0;
        for (int i = 0; i < 10; i++) {
            time = (time << 5) | java.util.Arrays.binarySearch(ALPHABET, ulid.charAt(i));
        }
        return Instant.ofEpochMilli(time);
    }
}
