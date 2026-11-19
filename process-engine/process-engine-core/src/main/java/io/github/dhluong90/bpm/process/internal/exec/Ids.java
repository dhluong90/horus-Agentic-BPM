package io.github.dhluong90.bpm.process.internal.exec;

/**
 * Deterministic child ids: the first 20 characters of the instance ULID (48 bits of time, 50 random bits) and the
 * instance sequence in 6 Crockford base-32 characters, 26 characters in all. The same state and command always
 * give the same ids, so {@link ExecutionKernel#apply} stays a pure function.
 */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ids() {
    }

    /**
     * @param instanceId the instance ULID (26 characters)
     * @param sequence the sequence value, at most 2^30 - 1
     * @return the child id
     */
    public static String child(String instanceId, long sequence) {
        if (instanceId.length() < 20) {
            throw new IllegalArgumentException("instance id must be a ULID");
        }
        if (sequence < 0 || sequence >= (1L << 30)) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
        char[] out = new char[6];
        long v = sequence;
        for (int i = 5; i >= 0; i--) {
            out[i] = ALPHABET[(int) (v & 31)];
            v >>>= 5;
        }
        return instanceId.substring(0, 20) + new String(out);
    }
}
