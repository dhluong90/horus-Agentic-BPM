package io.github.dhluong90.bpm.rules.api;

/**
 * Optional fields of a lifecycle command.
 *
 * @param idempotencyKey optional key; a replay returns the original result
 * @param expectedLockVersion optional optimistic lock ({@code If-Match}); a stale value is {@code VERSION_CONFLICT}
 * @param comment the comment stored with an approval (optional), the reason of a reject (required) or of a
 *     withdrawal (optional)
 * @param correlationId optional correlation id carried by the event the command writes; not part of the idempotent
 *     request, so a replay with another correlation id still returns the original result
 */
public record WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId) {

    private static final WriteOptions NONE = new WriteOptions(null, null, null, null);

    /**
     * Creates options without correlation id.
     *
     * @param idempotencyKey optional key
     * @param expectedLockVersion optional optimistic lock
     * @param comment optional comment or reason
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment) {
        this(idempotencyKey, expectedLockVersion, comment, null);
    }

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

    /**
     * Returns a copy with a correlation id for the event the command writes.
     *
     * @param id the correlation id, 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
     * @return the options
     */
    public WriteOptions withCorrelationId(String id) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, id);
    }
}
