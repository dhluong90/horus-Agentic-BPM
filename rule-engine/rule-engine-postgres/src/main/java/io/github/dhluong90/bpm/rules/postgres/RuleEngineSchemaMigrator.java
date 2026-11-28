package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.RuleEngineSchema;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.exception.FlywayValidateException;
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
 * baseline below {@code V1} and still applies every engine migration. The engine schema is one {@code V1__baseline}
 * until the 1.0 release; a database made by an earlier build fails validation and is refused with a request to
 * reset it.
 */
public final class RuleEngineSchemaMigrator {

    /** Classpath location of the engine's migrations; distinct from the host's {@code db/migration}. */
    public static final String MIGRATION_LOCATION = "classpath:io/github/dhluong90/bpm/rules/postgres/migration";

    private final DataSource dataSource;
    private final DataSource ownerDataSource;
    private final String appRole;

    /**
     * Creates a migrator bound to the host's data source.
     *
     * @param dataSource the host's data source; the migrator borrows connections from it and never
     *     creates a pool
     */
    public RuleEngineSchemaMigrator(DataSource dataSource) {
        this(dataSource, null, null);
    }

    /**
     * Creates a migrator that migrates as a schema owner and reads as the application role (#700).
     *
     * <p>{@link #migrate()} runs on {@code ownerDataSource}, which must be a role that owns the {@code rle_*} tables and
     * may create them. {@link #status()} and {@link #currentVersion()} read on {@code dataSource}, the role the host
     * serves traffic with. After each migration run the callback {@code afterMigrate__app_role_grants.sql} gives
     * {@code appRole} the grants it needs and no more.
     *
     * @param dataSource the host's data source, used for reads
     * @param ownerDataSource the data source of the schema owner, used for migrations; {@code null} migrates on
     *     {@code dataSource}, the single-role setup for development
     * @param appRole the role the host connects with at run time, granted by the callback; {@code null} or blank
     *     grants nothing
     */
    public RuleEngineSchemaMigrator(DataSource dataSource, DataSource ownerDataSource, String appRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.ownerDataSource = ownerDataSource;
        this.appRole = appRole == null ? "" : appRole.strip();
    }

    /**
     * Applies pending engine migrations. Safe to call on every start and from several replicas:
     * Flyway serialises concurrent runs with a database lock.
     *
     * @return what was applied and the resulting schema version
     */
    public SchemaMigrationResult migrate() {
        Flyway flyway = flyway(ownerDataSource == null ? dataSource : ownerDataSource);
        MigrateResult result;
        try {
            result = flyway.migrate();
        } catch (FlywayValidateException e) {
            throw new IllegalStateException("The rle_* tables were made by a build before the 1.0 schema baseline and "
                    + "cannot be upgraded: drop the schema and start again (a database with real data needs a dump "
                    + "and reload). Nothing was changed. " + e.getMessage().lines().findFirst().orElse(""), e);
        }
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
        return currentVersionOf(flyway(dataSource));
    }

    /**
     * Compares the engine schema with the migrations in this build: one history read and one
     * classpath scan. Callers that report it repeatedly, such as health checks, cache the result.
     *
     * @return the applied version and the number of pending migrations
     */
    public SchemaStatus status() {
        MigrationInfoService info = flyway(dataSource).info();
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

    /**
     * The migration lock is a session advisory lock, not a transactional one. A migration that runs outside a
     * transaction ({@code CREATE INDEX CONCURRENTLY}, TR-OPS-02) waits for every open transaction to end, so with
     * a transactional lock it would wait on Flyway's own lock transaction until {@code lock_timeout}. Concurrent
     * migrators are still serialised by the session lock.
     */
    static final Map<String, String> LOCKING = Map.of("flyway.postgresql.transactional.lock", "false");

    private Flyway flyway(DataSource target) {
        return Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .configuration(LOCKING)
                .dataSource(target)
                .placeholders(Map.of("horusAppRole", appRole))
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
