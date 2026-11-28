package io.github.dhluong90.bpm.process.postgres;

import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the {@code pre_*} migrations on the host's {@link DataSource} with their own history table
 * {@code pre_schema_history}, so they never collide with the host's Flyway history (ADR-0002).
 */
public final class ProcessEngineSchemaMigrator {

    /** Flyway history table of the Process Engine. */
    public static final String HISTORY_TABLE = "pre_schema_history";
    /** Classpath location of the migrations. */
    public static final String MIGRATION_LOCATION = "classpath:io/github/dhluong90/bpm/process/postgres/migration";

    private final DataSource dataSource;
    private final DataSource ownerDataSource;
    private final String appRole;

    /** @param dataSource the host's data source */
    public ProcessEngineSchemaMigrator(DataSource dataSource) {
        this(dataSource, null, null);
    }

    /**
     * Migrates as a schema owner and reads as the application role (#700). {@link #migrate()} runs on
     * {@code ownerDataSource}; {@link #currentVersion()} reads on {@code dataSource}. After each migration run the
     * callback {@code afterMigrate__app_role_grants.sql} gives {@code appRole} the grants it needs and no more.
     *
     * @param dataSource the host's data source, used for reads
     * @param ownerDataSource the schema owner's data source; {@code null} migrates on {@code dataSource}, the
     *     single-role setup for development
     * @param appRole the role the host connects with at run time; {@code null} or blank grants nothing
     */
    public ProcessEngineSchemaMigrator(DataSource dataSource, DataSource ownerDataSource, String appRole) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.ownerDataSource = ownerDataSource;
        this.appRole = appRole == null ? "" : appRole.strip();
    }

    /** @return the number of migrations applied */
    public int migrate() {
        try {
            MigrateResult result = flyway(ownerDataSource == null ? dataSource : ownerDataSource).migrate();
            return result.migrationsExecuted;
        } catch (FlywayValidateException e) {
            throw new IllegalStateException("The pre_* tables were made by a build before the 1.0 schema baseline and "
                    + "cannot be upgraded: drop the schema and start again (a database with real data needs a dump "
                    + "and reload). Nothing was changed. " + e.getMessage().lines().findFirst().orElse(""), e);
        }
    }

    /** @return the current schema version, or null before the first migration */
    public String currentVersion() {
        MigrationInfo current = flyway(dataSource).info().current();
        return current == null || current.getVersion() == null ? null : current.getVersion().getVersion();
    }

    /**
     * The migration lock is a session advisory lock, not a transactional one. A script that runs outside a
     * transaction ({@code CREATE INDEX CONCURRENTLY}, TR-OPS-01) waits for every open transaction to end, so with a
     * transactional lock it would wait on Flyway's own lock transaction until {@code lock_timeout}.
     */
    static final Map<String, String> LOCKING = Map.of("flyway.postgresql.transactional.lock", "false");

    private Flyway flyway(DataSource target) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(LOCKING)
                .dataSource(target)
                .placeholders(Map.of("horusAppRole", appRole))
                .table(HISTORY_TABLE)
                .locations(MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .baselineDescription("Process Engine baseline")
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .load();
    }
}
