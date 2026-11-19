package com.horus.bpm.rules.internal.service;

import java.time.Duration;

/**
 * Validated engine settings. A value outside its range fails construction with a message naming the
 * property, so an invalid host configuration stops start-up (TR-EXT-03).
 *
 * @param cacheMaxEntries {@code horus.bpm.rules.cache.max-entries}, 1 to {@value EngineDefaults#CACHE_MAX_ENTRIES_LIMIT}
 * @param idempotencyRetention {@code horus.bpm.rules.idempotency.retention}, 1 minute to 30 days
 * @param maxInputScale {@code horus.bpm.rules.evaluation.max-input-scale}, 0 to {@value EngineDefaults#INPUT_MAX_SCALE}
 * @param rejectUndeclaredInputs {@code horus.bpm.rules.evaluation.reject-undeclared-inputs}: refuse an input name the
 *     rule set does not declare ({@code INPUT_INVALID}); when false, such inputs are ignored and never logged
 * @param queryMaxPageSize {@code horus.bpm.rules.query.max-page-size}, 1 to {@value EngineDefaults#QUERY_MAX_PAGE_SIZE}:
 *     the largest {@code limit} a list read accepts
 * @param reasonMaxLength {@code horus.bpm.rules.lifecycle.reason-max-length}, 1 to
 *     {@value EngineDefaults#REASON_MAX_LENGTH}: the longest reject reason, in characters
 */
public record EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
        boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength) {

    public EngineSettings {
        if (cacheMaxEntries < 1 || cacheMaxEntries > EngineDefaults.CACHE_MAX_ENTRIES_LIMIT) {
            throw new IllegalArgumentException("horus.bpm.rules.cache.max-entries must be 1 to "
                    + EngineDefaults.CACHE_MAX_ENTRIES_LIMIT + ", was " + cacheMaxEntries);
        }
        if (idempotencyRetention == null
                || idempotencyRetention.compareTo(EngineDefaults.IDEMPOTENCY_RETENTION_MIN) < 0
                || idempotencyRetention.compareTo(EngineDefaults.IDEMPOTENCY_RETENTION_MAX) > 0) {
            throw new IllegalArgumentException("horus.bpm.rules.idempotency.retention must be PT1M to P30D, was "
                    + idempotencyRetention);
        }
        if (maxInputScale < 0 || maxInputScale > EngineDefaults.INPUT_MAX_SCALE) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.max-input-scale must be 0 to "
                    + EngineDefaults.INPUT_MAX_SCALE + " (it may be lowered, never raised), was " + maxInputScale);
        }
        if (queryMaxPageSize < 1 || queryMaxPageSize > EngineDefaults.QUERY_MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("horus.bpm.rules.query.max-page-size must be 1 to "
                    + EngineDefaults.QUERY_MAX_PAGE_SIZE + " (it may be lowered, never raised), was " + queryMaxPageSize);
        }
        if (reasonMaxLength < 1 || reasonMaxLength > EngineDefaults.REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("horus.bpm.rules.lifecycle.reason-max-length must be 1 to "
                    + EngineDefaults.REASON_MAX_LENGTH + " (it may be lowered, never raised), was " + reasonMaxLength);
        }
    }

    /**
     * Creates settings with the default page size and reason length.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                EngineDefaults.QUERY_MAX_PAGE_SIZE, EngineDefaults.REASON_MAX_LENGTH);
    }

    /**
     * Returns the defaults.
     *
     * @return settings with every default of {@link EngineDefaults}
     */
    public static EngineSettings defaults() {
        return new EngineSettings(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION,
                EngineDefaults.INPUT_MAX_SCALE, EngineDefaults.REJECT_UNDECLARED_INPUTS,
                EngineDefaults.QUERY_MAX_PAGE_SIZE, EngineDefaults.REASON_MAX_LENGTH);
    }
}
