package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.api.RuleEngineSchema;
import com.horus.bpm.rules.postgres.DecisionLogPartitions;
import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.SchemaStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports whether the engine's schema matches this build. Contributed as {@code ruleEngine}.
 *
 * <ul>
 *   <li>Down when no engine migration is applied or migrations of this build are pending, with
 *       {@code pendingMigrations}.
 *   <li>Up otherwise, with the schema version and whether next month's decision log partition exists.
 *       A missing partition is a detail, not a failure: until RLE-017 schedules creation ahead, the
 *       partitions are created at start-up only, and decisions of a month without a partition go to
 *       {@code rle_decision_default}.
 * </ul>
 *
 * <p>The state is computed at most once per {@link #CACHE_TTL}, so frequent probes do not scan the
 * classpath or query the database each time.
 */
final class RuleEngineHealthIndicator extends AbstractHealthIndicator {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final RuleEngineSchemaMigrator migrator;
    private final DecisionLogPartitions partitions;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    RuleEngineHealthIndicator(RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, Clock clock) {
        this(migrator, partitions, clock, System::nanoTime);
    }

    RuleEngineHealthIndicator(
            RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, Clock clock, LongSupplier nanoTime) {
        super("Rule Engine health check failed");
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        long now = nanoTime.getAsLong();
        Snapshot snapshot = cache.get();
        if (snapshot == null || now - snapshot.takenAtNanos() >= CACHE_TTL.toNanos()) {
            snapshot = new Snapshot(now, compute());
            cache.set(snapshot);
        }
        builder.status(snapshot.status()).withDetails(snapshot.details());
    }

    private State compute() {
        SchemaStatus schema = migrator.status();
        if (!schema.isCurrent()) {
            return new State(Status.DOWN, Map.of(
                    "historyTable", RuleEngineSchema.HISTORY_TABLE,
                    "schemaVersion", String.valueOf(schema.currentVersion()),
                    "pendingMigrations", schema.pendingMigrations()));
        }
        YearMonth nextMonth = DecisionLogPartitions.monthOf(clock.instant()).plusMonths(1);
        return new State(Status.UP, Map.of(
                "historyTable", RuleEngineSchema.HISTORY_TABLE,
                "schemaVersion", schema.currentVersion(),
                "pendingMigrations", 0,
                "nextMonthPartition", DecisionLogPartitions.partitionName(nextMonth),
                "nextMonthPartitionPresent", partitions.exists(nextMonth)));
    }

    private record State(Status status, Map<String, Object> details) {
    }

    private record Snapshot(long takenAtNanos, State state) {
        Status status() {
            return state.status();
        }

        Map<String, Object> details() {
            return state.details();
        }
    }
}
