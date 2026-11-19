package com.horus.bpm.rules.internal.json;

import java.security.SecureRandom;
import java.time.Instant;

/** Generates ULIDs: 48 bits of milliseconds and 80 random bits in Crockford base 32 (26 characters). */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    /**
     * Creates a ULID for an instant.
     *
     * @param at the instant whose milliseconds lead the id
     * @return the 26-character id
     */
    public static String next(Instant at) {
        long time = at.toEpochMilli();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
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
}
