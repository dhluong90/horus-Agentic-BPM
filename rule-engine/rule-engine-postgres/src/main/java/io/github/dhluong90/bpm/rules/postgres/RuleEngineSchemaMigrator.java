package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.RuleEngineSchema;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the Rule Engine's {@code rle_*} migrations to the host database.
 *
 * <p>Runs Flyway on the host's {@link DataSource} with its own history table
 * ({@value RuleEngineSchema#HISTORY_TABLE}) and its own classpath location, so the host's
 * {@code flyway_schema_history} and {@code db/migration} are never read or written (TR-EMB-06).
 * The engine opens no connection pool of its own (TR-EMB-04).
 *
 * <p>The baseline version is {@code 0}: on a schema that already holds host tables, Flyway records a
 * baseline below {@code V1} and still applies every engine migration.
 */
public final class RuleEngineSchemaMigrator {

    /** Classpath location of the engine's migrations; distinct from the host's {@code db/migration}. */
    public static final String MIGRATION_LOCATION = "classpath:io/github/dhluong90/bpm/rules/postgres/migration";

    private final DataSource dataSource;

    /**
     * Creates a migrator bound to the host's data source.
     *
     * @param dataSource the host's data source; the migrator borrows connections from it and never
     *     creates a pool
     */
    public RuleEngineSchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Applies pending engine migrations. Safe to call on every start and from several replicas:
     * Flyway serialises concurrent runs with a database lock.
     *
     * @return what was applied and the resulting schema version
     */
    public SchemaMigrationResult migrate() {
        Flyway flyway = flyway();
        MigrateResult result = flyway.migrate();
        return new SchemaMigrationResult(
                result.initialSchemaVersion,
                currentVersionOf(flyway).orElse(null),
                result.migrationsExecuted);
    }

    /**
     * Reads the current engine schema version from {@value RuleEngineSchema#HISTORY_TABLE}.
     *
     * @return the latest applied version, or empty when no engine migration has run
     */
    public Optional<String> currentVersion() {
        return currentVersionOf(flyway());
    }

    /**
     * Compares the engine schema with the migrations in this build: one history read and one
     * classpath scan. Callers that report it repeatedly, such as health checks, cache the result.
     *
     * @return the applied version and the number of pending migrations
     */
    public SchemaStatus status() {
        MigrationInfoService info = flyway().info();
        MigrationInfo current = info.current();
        String version = (current == null || current.getVersion() == null) ? null : current.getVersion().getVersion();
        return new SchemaStatus(version, info.pending().length);
    }

    private static Optional<String> currentVersionOf(Flyway flyway) {
        MigrationInfo current = flyway.info().current();
        if (current == null || current.getVersion() == null) {
            return Optional.empty();
        }
        return Optional.of(current.getVersion().getVersion());
    }

    private Flyway flyway() {
        return Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .dataSource(dataSource)
                .table(RuleEngineSchema.HISTORY_TABLE)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .baselineDescription("Rule Engine baseline")
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .load();
    }
}
