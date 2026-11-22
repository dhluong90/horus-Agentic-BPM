package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Rolling upgrade on one schema (RLE-197, TR-OPS-02): a replica of this release runs against a schema a replica of the
 * next release has already migrated. The next release is simulated by {@code V9999__next_release_expand.sql}: a
 * nullable column, a defaulted column and a new table. The rule set {@code card-fee} is written before the upgrade; the
 * "old" replica then reads it, starts as usual, and writes a second version afterwards.
 */
@Testcontainers
class RuleEngineMixedVersionIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String NEXT_RELEASE = "classpath:mixed-version/rle-next";

    /** This build's migrations plus the next release's, as the next release's replica leaves the schema. */
    private static Flyway nextRelease(DataSource db) {
        return Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .configuration(RuleEngineSchemaMigrator.LOCKING).dataSource(db).table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION, NEXT_RELEASE)
                .baselineOnMigrate(true).baselineVersion("0").load();
    }

    @Test
    void RLE_197_a_replica_of_this_release_runs_on_a_schema_the_next_release_migrated() {
        DataSource db = database();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
        EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-01-10T02:00:00Z"));
        RuleEngines before = PostgresRuleEngines.create(db,
                new RuleEngineTransactions(new DataSourceTransactionManager(db)), caller, caller, clock,
                EngineSettings.defaults());
        before.cursorKeys().load();
        caller.as("bank-a", "lan");
        before.versionService().importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed("card-fee", null)));

        assertThat(nextRelease(db).migrate().migrationsExecuted).isEqualTo(1);
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        // The restarted replica: nothing to apply, one migration ahead of its build, schema counted as current.
        assertThat(migrator.migrate().migrationsExecuted()).isZero();
        assertThat(migrator.aheadMigrations()).isEqualTo(1);
        assertThat(migrator.status().pendingMigrations()).isZero();
        RuleEngines old = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                caller, caller, clock, EngineSettings.defaults());
        old.cursorKeys().load();
        VersionService versions = old.versionService();
        caller.as("bank-a", "lan");

        byte[] changed = new String(EditorApiPostgresIT.renamed("card-fee", null), java.nio.charset.StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"12000 VND\" }")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        RuleSetVersion second = versions.importVersion("card-fee", ImportRequest.yaml(changed));

        assertThat(second.version()).isGreaterThanOrEqualTo(1);
        assertThat(versions.requestPublish("card-fee", second.version(), WriteOptions.none()).status().name())
                .isEqualTo("PENDING_APPROVAL");
        JdbcClient jdbc = JdbcClient.create(db);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version WHERE next_release_note IS NOT NULL")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_rule_set WHERE next_release_flag").query(Integer.class)
                .single()).isZero();
    }

    @Test
    void RLE_197_a_schema_that_is_not_ahead_reports_none() {
        DataSource db = database();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        migrator.migrate();

        assertThat(migrator.aheadMigrations()).isZero();
        assertThat(migrator.migrate().migrationsExecuted()).isZero();
    }

    private static DataSource database() {
        String name = "mixed_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSource(name);
    }

    private static PGSimpleDataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
