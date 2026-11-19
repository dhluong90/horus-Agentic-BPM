package io.github.dhluong90.bpm.rules.internal.service;

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

    /** Longest accepted {@code X-Correlation-Id}; a longer or malformed one is replaced by a generated id. */
    public static final int CORRELATION_ID_MAX_LENGTH = 128;

    private EngineDefaults() {
    }
}
