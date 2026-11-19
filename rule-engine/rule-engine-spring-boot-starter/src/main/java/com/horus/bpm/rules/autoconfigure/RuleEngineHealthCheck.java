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

/**
 * The Rule Engine health check, the same on every Spring Boot line. It depends on no Spring Boot health
 * API; a thin adapter per line ({@link RuleEngineHealthIndicator} on Spring Boot 4,
 * {@link RuleEngineSpringBoot35HealthIndicator} on Spring Boot 3.5) translates the result and contributes
 * it as {@code ruleEngine}.
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
 * <p>The result is computed at most once per {@link #CACHE_TTL}, so frequent probes do not scan the
 * classpath or query the database each time.
 */
final class RuleEngineHealthCheck {

    static final Duration CACHE_TTL = Duration.ofSeconds(60);

    /** Message of the health failure when the check itself throws. */
    static final String FAILURE_MESSAGE = "Rule Engine health check failed";

    /** Outcome of one check: up or down, and the details reported with it. */
    record Result(boolean up, Map<String, Object> details) {
        Result {
            details = Map.copyOf(details);
        }
    }

    private final RuleEngineSchemaMigrator migrator;
    private final DecisionLogPartitions partitions;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    RuleEngineHealthCheck(RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, Clock clock) {
        this(migrator, partitions, clock, System::nanoTime);
    }

    RuleEngineHealthCheck(
            RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, Clock clock, LongSupplier nanoTime) {
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    Result check() {
        long now = nanoTime.getAsLong();
        Snapshot snapshot = cache.get();
        if (snapshot == null || now - snapshot.takenAtNanos() >= CACHE_TTL.toNanos()) {
            snapshot = new Snapshot(now, compute());
            cache.set(snapshot);
        }
        return snapshot.result();
    }

    private Result compute() {
        SchemaStatus schema = migrator.status();
        if (!schema.isCurrent()) {
            return new Result(false, Map.of(
                    "historyTable", RuleEngineSchema.HISTORY_TABLE,
                    "schemaVersion", String.valueOf(schema.currentVersion()),
                    "pendingMigrations", schema.pendingMigrations()));
        }
        YearMonth nextMonth = DecisionLogPartitions.monthOf(clock.instant()).plusMonths(1);
        return new Result(true, Map.of(
                "historyTable", RuleEngineSchema.HISTORY_TABLE,
                "schemaVersion", schema.currentVersion(),
                "pendingMigrations", 0,
                "nextMonthPartition", DecisionLogPartitions.partitionName(nextMonth),
                "nextMonthPartitionPresent", partitions.exists(nextMonth)));
    }

    private record Snapshot(long takenAtNanos, Result result) {
    }
}
