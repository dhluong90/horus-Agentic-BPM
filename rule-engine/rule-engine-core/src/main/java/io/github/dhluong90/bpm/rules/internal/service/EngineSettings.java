package io.github.dhluong90.bpm.rules.internal.service;

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
 * @param maxOutputBytes {@code horus.bpm.rules.evaluation.max-output-bytes}, 1 to {@value EngineDefaults#OUTPUT_MAX_BYTES}
 * @param maxStringLength {@code horus.bpm.rules.evaluation.max-string-length}, 1 to {@value EngineDefaults#STRING_MAX_LENGTH}
 * @param maxRows {@code horus.bpm.rules.evaluation.max-rows}, 1 to {@value EngineDefaults#RULES_MAX}
 * @param systemActor {@code horus.bpm.rules.lifecycle.system-actor}: the actor recorded when a version is superseded, in the reserved
 *     {@code system:} namespace (default {@code system:supersession}),
 *     1 to {@value EngineDefaults#SYSTEM_ACTOR_MAX_LENGTH} characters, not blank
 * @param retireApproval {@code horus.bpm.lifecycle.retire-approval} (PD-080): {@code single}, recorded on every
 *     retirement; {@code four-eyes} is refused until it ships in M3 (RLE-038)
 * @param retireLastVersion {@code horus.bpm.lifecycle.retire-last-version} (PD-101, shared with the Process Engine):
 *     {@code confirm} (default) lets a retirement leave no version in effect only when the request confirms it with
 *     {@code confirmNoVersionInEffect}; {@code refuse} never lets it; {@code four-eyes} is refused until it ships
 */
public record EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
        boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
        int maxStringLength, int maxRows, String systemActor, String retireApproval, String retireLastVersion) {

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
        requireLowerOnly("horus.bpm.rules.query.max-page-size", queryMaxPageSize, EngineDefaults.QUERY_MAX_PAGE_SIZE);
        requireLowerOnly("horus.bpm.rules.lifecycle.reason-max-length", reasonMaxLength, EngineDefaults.REASON_MAX_LENGTH);
        requireLowerOnly("horus.bpm.rules.evaluation.max-output-bytes", maxOutputBytes, EngineDefaults.OUTPUT_MAX_BYTES);
        requireLowerOnly("horus.bpm.rules.evaluation.max-string-length", maxStringLength, EngineDefaults.STRING_MAX_LENGTH);
        requireLowerOnly("horus.bpm.rules.evaluation.max-rows", maxRows, EngineDefaults.RULES_MAX);
        if (systemActor == null || !systemActor.startsWith(EngineDefaults.RESERVED_ACTOR_PREFIX)
                || systemActor.length() == EngineDefaults.RESERVED_ACTOR_PREFIX.length()
                || systemActor.length() > EngineDefaults.SYSTEM_ACTOR_MAX_LENGTH) {
            throw new IllegalArgumentException("horus.bpm.rules.lifecycle.system-actor must start with "
                    + EngineDefaults.RESERVED_ACTOR_PREFIX + ", have a name after it and be at most "
                    + EngineDefaults.SYSTEM_ACTOR_MAX_LENGTH + " characters, was '" + systemActor + "'");
        }
        if ("four-eyes".equals(retireApproval)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-approval: four-eyes arrives in M3"
                    + " (RLE-038); this release supports single");
        }
        if ("four-eyes".equals(retireLastVersion)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-last-version: four-eyes is not built in this"
                    + " release; use confirm or refuse (PD-101)");
        }
        if (!EngineDefaults.RETIRE_LAST_CONFIRM.equals(retireLastVersion)
                && !EngineDefaults.RETIRE_LAST_REFUSE.equals(retireLastVersion)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-last-version must be confirm or refuse, was '"
                    + retireLastVersion + "'");
        }
        if (!EngineDefaults.RETIRE_APPROVAL.equals(retireApproval)) {
            throw new IllegalArgumentException("horus.bpm.lifecycle.retire-approval must be single (four-eyes"
                    + " arrives in M3), was '" + retireApproval + "'");
        }
    }

    /**
     * Creates settings with the default lifecycle actor and retirement approval.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     * @param maxOutputBytes see the record
     * @param maxStringLength see the record
     * @param maxRows see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength, int maxOutputBytes,
            int maxStringLength, int maxRows) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, EngineDefaults.SYSTEM_ACTOR,
                EngineDefaults.RETIRE_APPROVAL, EngineDefaults.RETIRE_LAST_CONFIRM);
    }

    private static void requireLowerOnly(String property, int value, int ceiling) {
        if (value < 1 || value > ceiling) {
            throw new IllegalArgumentException(property + " must be 1 to " + ceiling
                    + " (it may be lowered, never raised), was " + value);
        }
    }

    /**
     * Creates settings with the default page size, reason length and evaluation size limits.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                EngineDefaults.QUERY_MAX_PAGE_SIZE, EngineDefaults.REASON_MAX_LENGTH, EngineDefaults.OUTPUT_MAX_BYTES,
                EngineDefaults.STRING_MAX_LENGTH, EngineDefaults.RULES_MAX);
    }

    /**
     * Creates settings with the default evaluation size limits.
     *
     * @param cacheMaxEntries see the record
     * @param idempotencyRetention see the record
     * @param maxInputScale see the record
     * @param rejectUndeclaredInputs see the record
     * @param queryMaxPageSize see the record
     * @param reasonMaxLength see the record
     */
    public EngineSettings(int cacheMaxEntries, Duration idempotencyRetention, int maxInputScale,
            boolean rejectUndeclaredInputs, int queryMaxPageSize, int reasonMaxLength) {
        this(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs, queryMaxPageSize,
                reasonMaxLength, EngineDefaults.OUTPUT_MAX_BYTES, EngineDefaults.STRING_MAX_LENGTH,
                EngineDefaults.RULES_MAX);
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

    /**
     * Returns a copy with {@code reject-undeclared-inputs} set.
     *
     * @param reject the new value
     * @return the settings
     */
    public EngineSettings withRejectUndeclaredInputs(boolean reject) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, reject, queryMaxPageSize,
                reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, systemActor, retireApproval, retireLastVersion);
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
                queryMaxPageSize, reasonMaxLength, outputBytes, stringLength, rows, systemActor, retireApproval, retireLastVersion);
    }

    /**
     * Returns a copy with the lifecycle settings set.
     *
     * @param actor {@code lifecycle.system-actor}
     * @param approval {@code lifecycle.retire-approval}
     * @return the settings
     */
    public EngineSettings withLifecycle(String actor, String approval) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, actor, approval,
                retireLastVersion);
    }

    /**
     * Returns a copy with {@code horus.bpm.lifecycle.retire-last-version} set.
     *
     * @param mode {@code confirm} or {@code refuse}
     * @return the settings
     */
    public EngineSettings withRetireLastVersion(String mode) {
        return new EngineSettings(cacheMaxEntries, idempotencyRetention, maxInputScale, rejectUndeclaredInputs,
                queryMaxPageSize, reasonMaxLength, maxOutputBytes, maxStringLength, maxRows, systemActor,
                retireApproval, mode);
    }
}
