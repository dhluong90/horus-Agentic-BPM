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

    private EngineDefaults() {
    }
}
