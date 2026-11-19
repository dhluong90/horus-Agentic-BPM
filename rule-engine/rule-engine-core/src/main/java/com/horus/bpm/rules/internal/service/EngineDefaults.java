package com.horus.bpm.rules.internal.service;

import java.time.Duration;

/**
 * The one place where the Rule Engine's numeric and duration defaults are declared (ADR-0024,
 * TR-EXT-04). Code reads them through {@link EngineSettings}; the starter binds host properties over them.
 */
public final class EngineDefaults {

    /** Default of {@code horus.bpm.rules.cache.max-entries}: compiled versions kept per replica. */
    public static final int CACHE_MAX_ENTRIES = 1000;

    /** Largest accepted {@code cache.max-entries}. */
    public static final int CACHE_MAX_ENTRIES_LIMIT = 100_000;

    /** Default of {@code horus.bpm.rules.idempotency.retention}: how long a key is remembered (ADR-0006). */
    public static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    /** Shortest accepted {@code idempotency.retention}: a retry within a minute must still replay. */
    public static final Duration IDEMPOTENCY_RETENTION_MIN = Duration.ofMinutes(1);

    /** Longest accepted {@code idempotency.retention}. */
    public static final Duration IDEMPOTENCY_RETENTION_MAX = Duration.ofDays(30);

    /**
     * Significant digits of every number input and of number arithmetic: {@code MathContext.DECIMAL128}.
     * Part of the evaluation semantics (DESIGN section 3.2), so not a setting.
     */
    public static final int DECIMAL_PRECISION = 34;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-input-scale}: a number input's scale must lie
     * within plus or minus this value. A host may lower it, never raise it, because an unbounded scale lets one
     * request burn CPU in rounding and fail the decision log write.
     */
    public static final int INPUT_MAX_SCALE = 34;

    /**
     * Default of {@code horus.bpm.rules.evaluation.reject-undeclared-inputs}: a misspelt input name ({@code txcount}
     * for {@code txCount}) is refused instead of silently matching a default row.
     */
    public static final boolean REJECT_UNDECLARED_INPUTS = true;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-output-bytes}: the estimated JSON size of one
     * decision's outputs, checked while collected values accumulate. Lower only: outputs are held in the heap
     * that every tenant of the host shares.
     */
    public static final int OUTPUT_MAX_BYTES = 1_048_576;

    /**
     * Default and ceiling of {@code horus.bpm.rules.evaluation.max-string-length}: the longest string an input may
     * carry or string {@code +} may build, in UTF-16 code units. Lower only.
     */
    public static final int STRING_MAX_LENGTH = 65_536;

    /** Default and ceiling of {@code horus.bpm.rules.evaluation.max-rows}: the most rules a version may contain. Lower only. */
    public static final int RULES_MAX = 5_000;

    /**
     * Default and ceiling of {@code horus.bpm.rules.query.max-page-size}: items per page of a list read (ADR-0015).
     * A host may lower it, never raise it.
     */
    public static final int QUERY_MAX_PAGE_SIZE = 200;

    /** Items per page of a list read when the request names no {@code limit}. */
    public static final int QUERY_DEFAULT_PAGE_SIZE = 50;

    /**
     * Default and ceiling of {@code horus.bpm.rules.lifecycle.reason-max-length}: longest reason of a reject (and,
     * later, a retire), in characters. A host may lower it, never raise it.
     */
    public static final int REASON_MAX_LENGTH = 2000;

    /**
     * Largest body of a lifecycle command ({@code :approve} comment, {@code :reject} reason), in bytes: room for a
     * reason of {@link #REASON_MAX_LENGTH} characters even when every character is JSON-escaped. A safety bound of
     * the REST layer, not a setting; a larger body is {@code 413 PAYLOAD_TOO_LARGE}, read no further than one byte
     * past the bound.
     */
    public static final int COMMAND_BODY_MAX_BYTES = 16_384;

    /**
     * Largest {@code :evaluate} body, in bytes, the same bound as an imported document. A safety bound of the REST
     * layer, not a setting; a larger body is {@code 413 PAYLOAD_TOO_LARGE}.
     */
    public static final int EVALUATE_BODY_MAX_BYTES = 1_048_576;

    /**
     * How long a replica keeps the cursor key before reading it again from {@code rle_engine_key}. Deleting the row
     * rotates the key; every replica uses the new key within this time, and one that meets a cursor it cannot
     * verify re-reads at once. A bound of the engine, not a setting.
     */
    public static final java.time.Duration CURSOR_KEY_REFRESH = java.time.Duration.ofMinutes(5);

    /** Longest accepted {@code X-Correlation-Id}; a longer or malformed one is replaced by a generated id. */
    public static final int CORRELATION_ID_MAX_LENGTH = 128;

    private EngineDefaults() {
    }
}
