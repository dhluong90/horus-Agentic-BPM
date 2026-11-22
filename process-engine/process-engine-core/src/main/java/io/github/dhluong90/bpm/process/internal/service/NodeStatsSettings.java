package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
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
 * @param rollupMinLag {@code stats.rollup-min-lag}: the write-lag window, the longest a step transaction may stay open
 *     before its history row commits; no slice is rolled up within it, and {@code rollupLag} may not be shorter
 * @param rollupRetention {@code stats.rollup-retention}: how long hourly rows are kept; older whole days are deleted
 *     by the rollup run
 */
public record NodeStatsSettings(boolean rollupEnabled, Duration rollupInterval, Duration rollupLag,
        Duration rollupMaxSlice, Duration rollupMaxRun, Duration rollupInitialBackfill, Duration rollupMinLag,
        Period rollupRetention) {

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
    /** Default {@code stats.rollup-min-lag}; a lower value is accepted on the {@code dev} profile only. */
    public static final Duration DEFAULT_MIN_LAG = Duration.ofMinutes(5);
    /** Default {@code stats.rollup-retention}. */
    public static final Period DEFAULT_RETENTION = Period.ofMonths(13);
    /** Shortest {@code stats.rollup-retention}: the longest statistics window a client may read. */
    public static final Period MIN_RETENTION = Period.ofDays(31);
    /** Longest slice a configuration may ask for. */
    public static final Duration MAX_SLICE_LIMIT = Duration.ofMinutes(10);

    /**
     * The settings of {@code PRE-029}, with the default write-lag window and retention.
     *
     * @param rollupEnabled whether this replica runs the rollup
     * @param rollupInterval time between runs
     * @param rollupLag how far behind now the rollup stays
     * @param rollupMaxSlice longest slice
     * @param rollupMaxRun longest run
     * @param rollupInitialBackfill first-run backfill
     */
    public NodeStatsSettings(boolean rollupEnabled, Duration rollupInterval, Duration rollupLag,
            Duration rollupMaxSlice, Duration rollupMaxRun, Duration rollupInitialBackfill) {
        this(rollupEnabled, rollupInterval, rollupLag, rollupMaxSlice, rollupMaxRun, rollupInitialBackfill,
                DEFAULT_MIN_LAG, DEFAULT_RETENTION);
    }

    public NodeStatsSettings {
        Objects.requireNonNull(rollupMinLag, "rollupMinLag");
        Objects.requireNonNull(rollupRetention, "rollupRetention");
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
        if (rollupMinLag.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("stats.rollup-min-lag must be at least 1 second");
        }
        if (rollupLag.compareTo(rollupMinLag) < 0) {
            throw new IllegalArgumentException("stats.rollup-lag (" + rollupLag + ") must be at least stats.rollup-min-lag ("
                    + rollupMinLag + "): a slice rolled up before the write-lag window closes would miss rows that "
                    + "commit late");
        }
        LocalDate origin = LocalDate.of(2001, 1, 1);
        if (rollupRetention.isNegative() || !origin.plus(rollupRetention).isAfter(origin.plus(MIN_RETENTION))
                && !origin.plus(rollupRetention).equals(origin.plus(MIN_RETENTION))) {
            throw new IllegalArgumentException("stats.rollup-retention must be at least " + MIN_RETENTION
                    + ", the longest statistics window");
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
                DEFAULT_INITIAL_BACKFILL, DEFAULT_MIN_LAG, DEFAULT_RETENTION);
    }
}
