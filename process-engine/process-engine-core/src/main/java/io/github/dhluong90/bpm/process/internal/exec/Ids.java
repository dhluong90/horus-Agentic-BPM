package io.github.dhluong90.bpm.process.internal.exec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic child ids (tokens, tasks, attempts): the first 130 bits of SHA-256 over the whole instance id and the
 * instance sequence, in 26 Crockford base-32 characters. The same state and command always give the same ids, so
 * {@link ExecutionKernel#apply} stays a pure function, and two instances never share a child id, even when their
 * ULIDs were created in the same millisecond and share a prefix.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ids() {
    }

    /**
     * @param instanceId the instance id
     * @param sequence the sequence value, not negative
     * @return the child id, 26 characters
     */
    public static String child(String instanceId, long sequence) {
        if (instanceId == null || instanceId.isEmpty()) {
            throw new IllegalArgumentException("instance id required");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((instanceId + "/" + sequence).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
        char[] out = new char[26];
        int bit = 0;
        for (int i = 0; i < 26; i++) {
            int value = 0;
            for (int b = 0; b < 5; b++, bit++) {
                value = (value << 1) | ((digest[bit / 8] >> (7 - bit % 8)) & 1);
            }
            out[i] = ALPHABET[value];
        }
        return new String(out);
    }
}
