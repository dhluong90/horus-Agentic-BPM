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
 */
public record EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
        boolean rejectUndeclaredInputs) {

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
    }

    /**
     * Returns the defaults.
     *
     * @return settings with every default of {@link EngineDefaults}
     */
    public static EngineSettings defaults() {
        return new EngineSettings(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION,
                EngineDefaults.INPUT_MAX_SCALE, EngineDefaults.REJECT_UNDECLARED_INPUTS);
    }
}
