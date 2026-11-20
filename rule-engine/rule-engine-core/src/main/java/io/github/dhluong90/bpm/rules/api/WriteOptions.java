package io.github.dhluong90.bpm.rules.api;

import java.util.List;

/**
 * Optional fields of a lifecycle command.
 *
 * @param idempotencyKey optional key; a replay returns the original result
 * @param expectedLockVersion optional optimistic lock ({@code If-Match}); a stale value is {@code VERSION_CONFLICT}
 * @param comment the comment stored with an approval (optional), the reason of a reject (required) or of a
 *     withdrawal (optional)
 * @param correlationId optional correlation id carried by the event the command writes; not part of the idempotent
 *     request, so a replay with another correlation id still returns the original result
 * @param acknowledgedWarnings on {@code approve} only: the warnings the approver was shown and accepts, as
 *     {@code { code, input }} pairs, for example {@code UNMASKED_PERSONAL_DATA} on {@code nationalId} (RLE-053, PD-104);
 *     empty when none, never null. The approval is accepted only when this set equals the set the engine recomputes
 */
public record WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId,
        List<AcknowledgedWarning> acknowledgedWarnings) {

    private static final WriteOptions NONE = new WriteOptions(null, null, null);

    public WriteOptions {
        acknowledgedWarnings = acknowledgedWarnings == null ? List.of() : List.copyOf(acknowledgedWarnings);
    }

    /**
     * Creates options without acknowledged warnings.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     * @param correlationId see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment, String correlationId) {
        this(idempotencyKey, expectedLockVersion, comment, correlationId, List.of());
    }

    /**
     * Creates options without correlation id or acknowledged warnings.
     *
     * @param idempotencyKey see the record
     * @param expectedLockVersion see the record
     * @param comment see the record
     */
    public WriteOptions(String idempotencyKey, Long expectedLockVersion, String comment) {
        this(idempotencyKey, expectedLockVersion, comment, (String) null);
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
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, id, acknowledgedWarnings);
    }

    /**
     * Returns a copy that acknowledges these warnings.
     *
     * @param warnings the pairs the approver was shown
     * @return the options
     */
    public WriteOptions acknowledging(List<AcknowledgedWarning> warnings) {
        return new WriteOptions(idempotencyKey, expectedLockVersion, comment, correlationId, warnings);
    }
}
