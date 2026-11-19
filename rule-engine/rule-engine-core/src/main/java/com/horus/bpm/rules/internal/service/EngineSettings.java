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
 * @param maxOutputBytes {@code horus.bpm.rules.evaluation.max-output-bytes}, 1 to {@value EngineDefaults#OUTPUT_MAX_BYTES}
 * @param maxStringLength {@code horus.bpm.rules.evaluation.max-string-length}, 1 to {@value EngineDefaults#STRING_MAX_LENGTH}
 * @param maxRows {@code horus.bpm.rules.evaluation.max-rows}, 1 to {@value EngineDefaults#RULES_MAX}
 */
public record EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
        boolean rejectUndeclaredInputs, int maxOutputBytes, int maxStringLength, int maxRows) {

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
        requireLowerOnly("horus.bpm.rules.evaluation.max-output-bytes", maxOutputBytes, EngineDefaults.OUTPUT_MAX_BYTES);
        requireLowerOnly("horus.bpm.rules.evaluation.max-string-length", maxStringLength, EngineDefaults.STRING_MAX_LENGTH);
        requireLowerOnly("horus.bpm.rules.evaluation.max-rows", maxRows, EngineDefaults.RULES_MAX);
    }

    private static void requireLowerOnly(String property, int value, int ceiling) {
        if (value < 1 || value > ceiling) {
            throw new IllegalArgumentException(property + " must be 1 to " + ceiling
                    + " (it may be lowered, never raised), was " + value);
        }
    }

    /**
     * Returns the defaults.
     *
     * @return settings with every default of {@link EngineDefaults}
     */
    public static EngineSettings defaults() {
        return new EngineSettings(EngineDefaults.CACHE_MAX_ENTRIES, EngineDefaults.IDEMPOTENCY_RETENTION,
                EngineDefaults.INPUT_MAX_SCALE, EngineDefaults.REJECT_UNDECLARED_INPUTS, EngineDefaults.OUTPUT_MAX_BYTES,
                EngineDefaults.STRING_MAX_LENGTH, EngineDefaults.RULES_MAX);
    }

    /**
     * Returns a copy with {@code reject-undeclared-inputs} set.
     *
     * @param reject the new value
     * @return the settings
     */
    public EngineSettings withRejectUndeclaredInputs(boolean reject) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, reject, maxOutputBytes,
                maxStringLength, maxRows);
    }

    /**
     * Returns a copy with the evaluation size limits set.
     *
     * @param outputBytes {@code max-output-bytes}
     * @param stringLength {@code max-string-length}
     * @param rows {@code max-rows}
     * @return the settings
     */
    public EngineSettings withSizeLimits(int outputBytes, int stringLength, int rows) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                outputBytes, stringLength, rows);
    }
}
