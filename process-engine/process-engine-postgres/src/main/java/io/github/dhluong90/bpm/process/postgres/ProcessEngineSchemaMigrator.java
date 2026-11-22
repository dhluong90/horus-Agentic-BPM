package io.github.dhluong90.bpm.process.postgres;

import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the {@code pre_*} migrations on the host's {@link DataSource} with their own history table
 * {@code pre_schema_history}, so they never collide with the host's Flyway history (ADR-0002).
 *
 * <p>Rolling upgrade (PRE-218, {@code TR-OPS-02}): a replica of an older release may start, or restart, after a replica
 * of the next release has migrated the schema. Migrations that are recorded in the history but not in this build are
 * ignored ({@code ignoreMigrationPatterns("*:future")}) instead of failing validation; an older release then runs
 * against the newer schema, which is safe because a release's migrations only expand (see DESIGN section 7.4).
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

    /**
     * @return how many migrations the history records that this build does not contain: the schema is ahead of this
     *     release, because a replica of a newer release migrated it during a rolling upgrade. Zero for a schema at or
     *     below this build
     */
    public int aheadMigrations() {
        int ahead = 0;
        for (MigrationInfo info : flyway().info().all()) {
            if (info.getState() == MigrationState.FUTURE_SUCCESS || info.getState() == MigrationState.FUTURE_FAILED) {
                ahead++;
            }
        }
        return ahead;
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
                .ignoreMigrationPatterns("*:future")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .baselineDescription("Process Engine baseline")
                .failOnMissingLocations(true)
                .cleanDisabled(true)
                .load();
    }
}
