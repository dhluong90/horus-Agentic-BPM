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
 * then create the decision log partitions of the current and next month from the {@link Clock}. When
 * migration is switched off and the schema is not current, partitions are skipped with a warning and
 * the {@code ruleEngine} health contributor reports down. The {@code rle_*} tables are ready when the
 * application context is refreshed.
 */
public final class RuleEngineSchemaInitializer implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(RuleEngineSchemaInitializer.class);

    private final RuleEngineSchemaMigrator migrator;
    private final DecisionLogPartitions partitions;
    private final boolean migrate;
    private final Clock clock;

    RuleEngineSchemaInitializer(
            RuleEngineSchemaMigrator migrator, DecisionLogPartitions partitions, boolean migrate, Clock clock) {
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.migrate = migrate;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
                return;
            }
        }
        List<String> created = partitions.ensureCurrentAndNextMonth(clock.instant());
        if (!created.isEmpty()) {
            LOG.info("Rule Engine created decision log partitions " + created);
        }
    }
}
