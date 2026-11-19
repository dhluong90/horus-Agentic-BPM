package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.api.RuleEngineSchema;
import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.SchemaMigrationResult;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the engine's schema is in place. Contributed as {@code ruleEngine}.
 *
 * <p>Up when the engine schema has a version; down when no engine migration has been applied, for
 * example when {@code horus.bpm.rules.schema.migrate=false} and the deployment step has not run.
 */
final class RuleEngineHealthIndicator extends AbstractHealthIndicator {

    private final RuleEngineSchemaMigrator migrator;
    private final ObjectProvider<RuleEngineSchemaInitializer> initializer;

    RuleEngineHealthIndicator(RuleEngineSchemaMigrator migrator, ObjectProvider<RuleEngineSchemaInitializer> initializer) {
        super("Rule Engine health check failed");
        this.migrator = Objects.requireNonNull(migrator, "migrator must not be null");
        this.initializer = Objects.requireNonNull(initializer, "initializer must not be null");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Optional<String> version = Optional.ofNullable(initializer.getIfAvailable())
                .flatMap(RuleEngineSchemaInitializer::result)
                .map(SchemaMigrationResult::currentVersion)
                .or(migrator::currentVersion);
        builder.withDetail("historyTable", RuleEngineSchema.HISTORY_TABLE);
        version.ifPresentOrElse(
                v -> builder.up().withDetail("schemaVersion", v),
                () -> builder.down().withDetail("reason", "no Rule Engine migration applied"));
    }
}
