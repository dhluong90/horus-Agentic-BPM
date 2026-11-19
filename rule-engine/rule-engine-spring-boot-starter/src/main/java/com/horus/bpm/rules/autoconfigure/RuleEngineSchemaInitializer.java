package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.SchemaMigrationResult;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Applies the engine's migrations once every singleton exists.
 *
 * <p>Running after singleton creation puts the engine's migrations after the host's own Flyway run.
 * On a clean database the host's Flyway therefore never meets a non-empty schema, and the engine
 * baselines below its first migration (TR-EMB-06). The {@code rle_*} tables are ready when the
 * application context is refreshed.
 */
public final class RuleEngineSchemaInitializer implements SmartInitializingSingleton {

    private static final Log LOG = LogFactory.getLog(RuleEngineSchemaInitializer.class);

    private final RuleEngineSchemaMigrator migrator;
    private volatile SchemaMigrationResult result;

    RuleEngineSchemaInitializer(RuleEngineSchemaMigrator migrator) {
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        SchemaMigrationResult applied = migrator.migrate();
        LOG.info("Rule Engine schema at version " + applied.currentVersion() + " ("
                + applied.migrationsExecuted() + " migration(s) applied)");
        this.result = applied;
    }

    /**
     * Returns the result of the start-up migration.
     *
     * @return the result, or empty before the migration has run
     */
    public Optional<SchemaMigrationResult> result() {
        return Optional.ofNullable(result);
    }
}
