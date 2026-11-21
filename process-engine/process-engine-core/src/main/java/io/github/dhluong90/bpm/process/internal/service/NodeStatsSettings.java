package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the hourly node statistics rollup, bound from {@code horus.bpm.processes.stats.*} by the starter.
 *
 * @param rollupEnabled {@code stats.rollup-enabled}: whether this replica runs the rollup; at least one replica must
 * @param rollupInterval {@code stats.rollup-interval}: time between runs
 * @param rollupLag {@code stats.rollup-lag}: how far behind now the rollup stays, so slow transactions have committed
 * @param rollupMaxSlice {@code stats.rollup-max-slice}: longest stretch of history one slice transaction reads
 * @param rollupMaxRun {@code stats.rollup-max-run}: longest a run keeps taking slices before it stops and the next
 *     run continues from the watermark
 * @param rollupInitialBackfill {@code stats.rollup-initial-backfill}: how far before the first run the rollup starts
 *     on a database that has no watermark yet
 */
public record NodeStatsSettings(boolean rollupEnabled, Duration rollupInterval, Duration rollupLag,
        Duration rollupMaxSlice, Duration rollupMaxRun, Duration rollupInitialBackfill) {

    /** Default {@code stats.rollup-interval}. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);
    /** Default {@code stats.rollup-lag}. */
    public static final Duration DEFAULT_LAG = Duration.ofMinutes(5);
    /** Default {@code stats.rollup-max-slice}. */
    public static final Duration DEFAULT_MAX_SLICE = Duration.ofMinutes(1);
    /** Default {@code stats.rollup-max-run}. */
    public static final Duration DEFAULT_MAX_RUN = Duration.ofSeconds(60);
    /** Default {@code stats.rollup-initial-backfill}. */
    public static final Duration DEFAULT_INITIAL_BACKFILL = Duration.ofHours(1);
    /** Longest slice a configuration may ask for. */
    public static final Duration MAX_SLICE_LIMIT = Duration.ofMinutes(10);

    public NodeStatsSettings {
        Objects.requireNonNull(rollupInterval, "rollupInterval");
        Objects.requireNonNull(rollupLag, "rollupLag");
        Objects.requireNonNull(rollupMaxSlice, "rollupMaxSlice");
        Objects.requireNonNull(rollupMaxRun, "rollupMaxRun");
        Objects.requireNonNull(rollupInitialBackfill, "rollupInitialBackfill");
        if (rollupInterval.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("stats.rollup-interval must be at least 1 second");
        }
        if (rollupLag.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("stats.rollup-lag must be at least 1 second");
        }
        if (rollupMaxSlice.compareTo(Duration.ofSeconds(1)) < 0 || rollupMaxSlice.compareTo(MAX_SLICE_LIMIT) > 0) {
            throw new IllegalArgumentException("stats.rollup-max-slice must be 1 second to " + MAX_SLICE_LIMIT);
        }
        if (rollupMaxRun.compareTo(Duration.ofSeconds(1)) < 0 || rollupMaxRun.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("stats.rollup-max-run must be 1 second to 10 minutes");
        }
        if (rollupInitialBackfill.isNegative() || rollupInitialBackfill.compareTo(Duration.ofDays(31)) > 0) {
            throw new IllegalArgumentException("stats.rollup-initial-backfill must be 0 to 31 days");
        }
    }

    /** @return the engine defaults, rollup on */
    public static NodeStatsSettings defaults() {
        return new NodeStatsSettings(true, DEFAULT_INTERVAL, DEFAULT_LAG, DEFAULT_MAX_SLICE, DEFAULT_MAX_RUN,
                DEFAULT_INITIAL_BACKFILL);
    }
}
