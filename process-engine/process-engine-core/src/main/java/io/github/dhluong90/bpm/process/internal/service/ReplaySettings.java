package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of offline agent replay (PRE-092, PRE-094), bound from {@code horus.bpm.processes.replay.*} by the starter.
 *
 * @param pageSize {@code replay.page-size}: recorded answers replayed per worker transaction, 1 to
 *     {@value #MAX_PAGE_SIZE}
 * @param maxCases {@code replay.max-cases}: most recorded answers one run may cover, 1 to {@value #MAX_CASES}; may be
 *     lowered, never raised
 * @param retention {@code replay.retention}: how long a run and its report are kept, 1 hour to 366 days
 */
public record ReplaySettings(int pageSize, int maxCases, Duration retention) {

    /** Default {@code replay.page-size}. */
    public static final int DEFAULT_PAGE_SIZE = 500;
    /** Largest {@code replay.page-size}. */
    public static final int MAX_PAGE_SIZE = 1_000;
    /** Default and largest {@code replay.max-cases}. */
    public static final int MAX_CASES = 50_000;
    /** Default {@code replay.retention}. */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    public ReplaySettings {
        Objects.requireNonNull(retention, "retention must not be null");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("replay.page-size must be 1 to " + MAX_PAGE_SIZE);
        }
        if (maxCases < 1 || maxCases > MAX_CASES) {
            throw new IllegalArgumentException("replay.max-cases must be 1 to " + MAX_CASES + "; it may be lowered, "
                    + "never raised");
        }
        if (retention.compareTo(Duration.ofHours(1)) < 0 || retention.compareTo(Duration.ofDays(366)) > 0) {
            throw new IllegalArgumentException("replay.retention must be 1 hour to 366 days");
        }
    }

    /** @return the engine defaults: pages of 500, at most 50,000 answers, kept 30 days */
    public static ReplaySettings defaults() {
        return new ReplaySettings(DEFAULT_PAGE_SIZE, MAX_CASES, DEFAULT_RETENTION);
    }
}
