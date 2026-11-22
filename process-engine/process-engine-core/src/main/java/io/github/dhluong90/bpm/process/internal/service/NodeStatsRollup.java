package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.store.NodeStatsStore;
import io.github.dhluong90.bpm.process.internal.store.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;

/**
 * The hourly node statistics rollup (PE-F-49, PRE-029). It reads {@code pre_history} in slices of at most
 * {@code stats.rollup-max-slice} between a watermark and {@code now - stats.rollup-lag}, adds each slice to
 * {@code pre_node_stats_hourly} and moves the watermark, all in one transaction per slice.
 *
 * <ul>
 *   <li><b>Idempotent:</b> a slice commits its additions and the new watermark together, so a slice that rolls back
 *       leaves both unchanged and is counted once by the next run. A run with nothing new does nothing.</li>
 *   <li><b>One writer:</b> the slice transaction locks the watermark row and skips it when another replica holds it, so
 *       two replicas never add the same slice.</li>
 *   <li><b>Bounded:</b> a slice reads at most {@code rollup-max-slice} of history and a run stops after
 *       {@code rollup-max-run}; the next run continues from the watermark.</li>
 *   <li><b>Outside the step:</b> nothing here runs in a step transaction, and no step writes a statistics table.</li>
 *   <li><b>Deterministic:</b> {@code now} is the injected clock's.</li>
 * </ul>
 */
public final class NodeStatsRollup {

    /**
     * What one run did.
     *
     * @param slices slices added and committed
     * @param caughtUp whether the watermark reached {@code now - lag}
     * @param heldByOther whether another replica held the watermark, so this run stopped
     * @param purged hourly rows older than {@code stats.rollup-retention} deleted by this run
     */
    public record RunResult(int slices, boolean caughtUp, boolean heldByOther, long purged) {

        /**
         * @param slices slices added and committed
         * @param caughtUp whether the watermark reached {@code now - lag}
         * @param heldByOther whether another replica held the watermark
         */
        public RunResult(int slices, boolean caughtUp, boolean heldByOther) {
            this(slices, caughtUp, heldByOther, 0);
        }
    }

    /** Rows one purge transaction deletes. */
    public static final int PURGE_BATCH = 5_000;
    /** Purge transactions one run takes at most, so a first purge after an upgrade spreads over several runs. */
    public static final int PURGE_MAX_BATCHES = 20;

    private enum SliceOutcome { ADDED, CAUGHT_UP, HELD }

    private final Clock clock;
    private final TransactionRunner transactions;
    private final NodeStatsStore store;
    private final NodeStatsSettings settings;
    private final String replicaId;

    /**
     * @param clock engine clock
     * @param transactions transaction runner on the host's transaction manager
     * @param store statistics storage
     * @param settings rollup settings
     * @param replicaId the name this replica is recorded under
     */
    public NodeStatsRollup(Clock clock, TransactionRunner transactions, NodeStatsStore store,
            NodeStatsSettings settings, String replicaId) {
        this.clock = Objects.requireNonNull(clock);
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.settings = Objects.requireNonNull(settings);
        this.replicaId = Objects.requireNonNull(replicaId);
    }

    /**
     * Takes slices until the watermark reaches {@code now - lag}, another replica holds it, or the run has lasted
     * {@code rollup-max-run}. A slice that fails rolls back alone and its exception propagates, with the watermark
     * where it was.
     *
     * @return what the run did
     */
    public RunResult run() {
        RunResult rolled = rollUp();
        if (rolled.heldByOther()) {
            return rolled;
        }
        return new RunResult(rolled.slices(), rolled.caughtUp(), false, purge());
    }

    /**
     * Deletes hourly rows older than {@code stats.rollup-retention}: whole days, counted back from now in UTC, in
     * transactions of {@value #PURGE_BATCH} rows, at most {@value #PURGE_MAX_BATCHES} per run.
     */
    private long purge() {
        Instant cutoff = clock.instant().atOffset(java.time.ZoneOffset.UTC).minus(settings.rollupRetention())
                .truncatedTo(ChronoUnit.DAYS).toInstant();
        long total = 0;
        for (int i = 0; i < PURGE_MAX_BATCHES; i++) {
            int deleted = transactions.inTransaction(() -> store.deleteBefore(cutoff, PURGE_BATCH));
            total += deleted;
            if (deleted < PURGE_BATCH) {
                break;
            }
        }
        return total;
    }

    private RunResult rollUp() {
        Instant started = clock.instant();
        transactions.inTransaction(() -> {
            store.ensureWatermark(NodeStatsStore.JOB_NAME, initialWatermark(started), replicaId, started);
            return null;
        });
        int slices = 0;
        while (true) {
            Instant now = clock.instant();
            if (slices > 0 && Duration.between(started, now).compareTo(settings.rollupMaxRun()) >= 0) {
                return new RunResult(slices, false, false);
            }
            SliceOutcome outcome = transactions.inTransaction(() -> slice(now));
            switch (outcome) {
                case ADDED -> slices++;
                case CAUGHT_UP -> {
                    return new RunResult(slices, true, false);
                }
                case HELD -> {
                    return new RunResult(slices, false, true);
                }
            }
        }
    }

    private SliceOutcome slice(Instant now) {
        Instant from = store.lockWatermark(NodeStatsStore.JOB_NAME).orElse(null);
        if (from == null) {
            return SliceOutcome.HELD;
        }
        Instant limit = now.minus(settings.rollupLag()).truncatedTo(ChronoUnit.SECONDS);
        Instant to = from.plus(settings.rollupMaxSlice());
        if (to.isAfter(limit)) {
            to = limit;
        }
        if (!to.isAfter(from)) {
            return SliceOutcome.CAUGHT_UP;
        }
        Collection<NodeStatsStore.NodeHour> hours = store.aggregate(from, to);
        if (!hours.isEmpty()) {
            store.add(hours, now);
        }
        store.advance(NodeStatsStore.JOB_NAME, to, replicaId, now);
        return SliceOutcome.ADDED;
    }

    /** On a database with no watermark: the start of the minute {@code lag + initial-backfill} before {@code now}. */
    private Instant initialWatermark(Instant now) {
        return now.minus(settings.rollupLag()).minus(settings.rollupInitialBackfill()).truncatedTo(ChronoUnit.MINUTES);
    }
}
