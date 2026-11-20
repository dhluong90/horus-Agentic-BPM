package io.github.dhluong90.bpm.rules.api;

/**
 * Optional fields of a lifecycle command.
 *
 * @param idempotencyKey optional key; a replay returns the original result
 * @param expectedLockVersion optional optimistic lock ({@code If-Match}); a stale value is {@code VERSION_CONFLICT}
 * @param comment the comment stored with an approval (optional), the reason of a reject (required) or of a
 *     withdrawal (optional)
 */
public record WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment) {

    private static final WriteOptions NONE = new WriteOptions(null, null, null);

    /**
     * Returns options with no field set.
     *
     * @return empty options
     */
    public static WriteOptions none() {
        return NONE;
    }

    /**
     * Returns options with only an idempotency key.
     *
     * @param key the key
     * @return the options
     */
    public static WriteOptions idempotencyKey(String key) {
        return new WriteOptions(key, null, null);
    }
}
