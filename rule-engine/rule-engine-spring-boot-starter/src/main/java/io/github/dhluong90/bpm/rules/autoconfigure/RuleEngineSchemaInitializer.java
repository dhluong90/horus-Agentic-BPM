package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator;
import io.github.dhluong90.bpm.rules.postgres.SchemaMigrationResult;
import io.github.dhluong90.bpm.rules.postgres.SchemaStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Prepares the engine's schema once every singleton exists.
 *
 * <p>Running after singleton creation puts the engine's migrations after the host's own Flyway run.
 * On a clean database the host's Flyway therefore never meets a non-empty schema, and the engine
 * baselines below its first migration (TR-EMB-06).
 *
 * <p>Steps: apply the engine's migrations (unless {@code horus.bpm.rules.migrate-on-start=false}),
 * then create the decision log partitions from the {@link Clock} up to {@code decision-log.partitions-ahead} and
 * start their daily maintenance ({@link RuleEnginePartitionMaintenance}). When
 * migration is switched off and the schema is not current, partitions are skipped with a warning and
 * the {@code ruleEngine} health contributor reports down. The {@code rle_*} tables are ready when the
 * application context is refreshed.
 *
 * <p>Last step: load the cursor key ({@code rle_engine_key}, RLE-039) in one short transaction outside any
 * request, then re-read it every {@code EngineDefaults.CURSOR_KEY_REFRESH} on a daemon thread of its own, in a
 * read-only transaction. A request never reads or writes the key. A database user without SELECT and INSERT on
 * {@code rle_engine_key} fails start-up with a message naming the table.
 */
public final class RuleEngineSchemaInitializer implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(RuleEngineSchemaInitializer.class);

    private final RuleEngineSchemaMigrator migrator;
    private final DecisionLogPartitions partitions;
    private final boolean migrate;
    private final Clock clock;
    private final RuleEngineCursorKeyInitializer cursorKeys;
    private final RuleEnginePartitionMaintenance maintenance;

    RuleEngineSchemaInitializer(
            RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, boolean migrate, Clock clock) {
        this(migrator, partitions, migrate, clock, null);
    }

    RuleEngineSchemaInitializer(RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, boolean migrate,
            Clock clock, RuleEngineCursorKeyInitializer cursorKeys) {
        this(migrator, partitions, migrate, clock, cursorKeys, null);
    }

    RuleEngineSchemaInitializer(RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, boolean migrate,
            Clock clock, RuleEngineCursorKeyInitializer cursorKeys, RuleEnginePartitionMaintenance maintenance) {
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.migrate = migrate;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.cursorKeys = cursorKeys;
        this.maintenance = maintenance;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (migrate) {
            SchemaMigrationResult applied = migrator.migrate();
            LOG.info("Rule Engine schema at version " + applied.currentVersion() + " ("
                    + applied.migrationsExecuted() + " migration(s) applied)");
        } else {
            SchemaStatus status = migrator.status();
            if (!status.isCurrent()) {
                LOG.warn("Rule Engine schema is not current (version " + status.currentVersion() + ", "
                        + status.pendingMigrations() + " pending) and horus.bpm.rules.migrate-on-start=false; "
                        + "decision log partitions were not created");
                if (cursorKeys != null) {
                    cursorKeys.notReady("schema at version " + status.currentVersion() + ", "
                            + status.pendingMigrations() + " migration(s) pending, migrate-on-start=false");
                }
                return;
            }
        }
        if (maintenance != null) {
            maintenance.runOnce();
            maintenance.start();
        } else {
            List<String> created = partitions.ensureCurrentAndNextMonth(clock.instant());
            if (!created.isEmpty()) {
                LOG.info("Rule Engine created decision log partitions " + created);
            }
        }
        if (cursorKeys != null) {
            cursorKeys.start();
        }
    }
}
