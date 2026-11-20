package io.github.dhluong90.bpm.process.postgres;

import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
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

    /** @param dataSource the host's data source */
    public ProcessEngineSchemaMigrator(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** @return the number of migrations applied */
    public int migrate() {
        MigrateResult result = flyway().migrate();
        return result.migrationsExecuted;
    }

    /** @return the current schema version, or null before the first migration */
    public String currentVersion() {
        MigrationInfo current = flyway().info().current();
        return current == null || current.getVersion() == null ? null : current.getVersion().getVersion();
    }

    /**
     * The migration lock is a session advisory lock, not a transactional one. A script that runs outside a
     * transaction ({@code CREATE INDEX CONCURRENTLY}, TR-OPS-01) waits for every open transaction to end, so with a
     * transactional lock it would wait on Flyway's own lock transaction until {@code lock_timeout}.
     */
    static final Map<String, String> LOCKING = Map.of("flyway.postgresql.transactional.lock", "false");

    private Flyway flyway() {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(LOCKING)
                .dataSource(dataSource)
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
