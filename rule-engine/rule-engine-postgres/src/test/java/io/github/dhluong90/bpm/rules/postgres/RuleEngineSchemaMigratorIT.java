package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The engine's migrations and the start-up partition step against a real PostgreSQL, one fresh
 * database per test. Partition months come from fixed instants, never from the wall clock.
 */
@Testcontainers
class RuleEngineSchemaMigratorIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void TR_EMB_01_clean_database_gets_every_rle_table() {
        DataSource db = freshDatabase();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.initialVersion()).isNull();
        assertThat(result.currentVersion()).isEqualTo("11");
        assertThat(result.migrationsExecuted()).isEqualTo(6);
        assertThat(rootTables(db)).containsExactlyInAnyOrder(
                "rle_schema_history",
                "rle_rule_set",
                "rle_version",
                "rle_version_history",
                "rle_engine_key",
                "rle_rule",
                "rle_decision",
                "rle_decision_outbox",
                "rle_idempotency");
    }

    @Test
    void TR_OPS_04_migration_creates_the_partitioned_decision_log_with_only_the_default_partition() {
        DataSource db = freshDatabase();

        new RuleEngineSchemaMigrator(db).migrate();

        assertThat(JdbcClient.create(db).sql("SELECT relkind::text FROM pg_class WHERE relname = 'rle_decision'")
                .query(String.class).single()).isEqualTo("p");
        assertThat(partitionsOf(db)).containsExactly("rle_decision_default");
    }

    @Test
    void TR_OPS_04_startup_step_creates_current_and_next_month_from_the_clock() {
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);

        List<String> created = partitions.ensureCurrentAndNextMonth(Instant.parse("2027-01-26T00:00:00Z"));

        assertThat(created).containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(partitionsOf(db)).containsExactlyInAnyOrder(
                "rle_decision_default", "rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(partitions.exists(YearMonth.of(2027, 2))).isTrue();
        assertThat(partitions.exists(YearMonth.of(2027, 3))).isFalse();
    }

    @Test
    void TR_OPS_04_startup_step_is_idempotent_and_only_adds_the_missing_month() {
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);
        partitions.ensureCurrentAndNextMonth(Instant.parse("2027-01-26T00:00:00Z"));

        assertThat(partitions.ensureCurrentAndNextMonth(Instant.parse("2027-01-31T23:59:59Z"))).isEmpty();
        assertThat(partitions.ensureCurrentAndNextMonth(Instant.parse("2027-02-01T00:00:00Z")))
                .containsExactly("rle_decision_2027_03");
    }

    @Test
    void TR_OPS_04_month_boundaries_are_utc() {
        DataSource db = migratedDatabase();

        // 23:30 UTC on 31 December is already January in Asia/Ho_Chi_Minh; the partition month is UTC.
        List<String> created = partitions(db).ensureCurrentAndNextMonth(Instant.parse("2026-12-31T23:30:00Z"));

        assertThat(created).containsExactly("rle_decision_2026_12", "rle_decision_2027_01");
        assertThat(partitionOfRow(db, "2026-12-31T23:59:59.999Z")).isEqualTo("rle_decision_2026_12");
        assertThat(partitionOfRow(db, "2027-01-01T00:00:00Z")).isEqualTo("rle_decision_2027_01");
    }

    @Test
    void TR_OPS_04_rows_already_in_the_default_partition_move_into_the_new_month() {
        DataSource db = migratedDatabase();
        JdbcClient jdbc = JdbcClient.create(db);
        insertDecision(jdbc, "01JD0000000000000000000M15", "2027-03-15T00:00:00Z");
        assertThat(partitionOf(jdbc, "01JD0000000000000000000M15")).isEqualTo("rle_decision_default");

        partitions(db).ensureCurrentAndNextMonth(Instant.parse("2027-03-01T08:00:00Z"));

        assertThat(partitionOf(jdbc, "01JD0000000000000000000M15")).isEqualTo("rle_decision_2027_03");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_default").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void TR_EMB_06_engine_installs_on_a_non_empty_schema_with_foreign_tables() {
        DataSource db = freshDatabase();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("CREATE TABLE loan_application (id BIGINT PRIMARY KEY, amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL)")
                .update();
        jdbc.sql("INSERT INTO loan_application VALUES (1, 800000000, 'VND')").update();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.currentVersion()).isEqualTo("11");
        assertThat(result.migrationsExecuted()).isEqualTo(6);
        assertThat(jdbc.sql("SELECT version FROM rle_schema_history ORDER BY installed_rank").query(String.class).list())
                .containsExactly("0", "1", "2", "3", "4", "5", "11");
        assertThat(jdbc.sql("SELECT amount_minor FROM loan_application WHERE id = 1").query(Long.class).single())
                .isEqualTo(800_000_000L);
    }

    @Test
    void TR_EMB_06_second_run_applies_nothing_and_keeps_the_history() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        migrator.migrate();

        SchemaMigrationResult second = migrator.migrate();

        assertThat(second.initialVersion()).isEqualTo("11");
        assertThat(second.currentVersion()).isEqualTo("11");
        assertThat(second.migrationsExecuted()).isZero();
        assertThat(migrator.currentVersion()).contains("11");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_schema_history").query(Long.class).single())
                .isEqualTo(6L);
    }

    @Test
    void RLE_026_V11_adds_retired_at_its_check_and_its_index_and_its_rollback_step_runs() {
        // Given a migrated database with one published and one retired version
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES ('bank-a', 'RS1', 'card-fee', now())")
                .update();
        String insert = "INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy,"
                + " canonical_json, canonical_hash, source_format, source_document, source_hash, created_at, retired_at)"
                + " VALUES ('bank-a', :id, 'RS1', :num, :status, 'FIRST', '{}', repeat('a', 64), 'YAML', '', repeat('b', 64),"
                + " now(), CAST(:retiredAt AS TIMESTAMPTZ))";
        jdbc.sql(insert).param("id", "V1").param("num", 1).param("status", "RETIRED")
                .param("retiredAt", "2027-02-17T08:00:00Z").update();
        jdbc.sql(insert).param("id", "V2").param("num", 2).param("status", "PUBLISHED").param("retiredAt", null).update();

        // Then status and retired_at cannot disagree, and the sweep's partial index exists
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert).param("id", "V3").param("num", 3)
                .param("status", "PUBLISHED").param("retiredAt", "2027-02-17T08:00:00Z").update())
                .hasMessageContaining("rle_version_retired_at_check");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(insert).param("id", "V4").param("num", 4)
                .param("status", "RETIRED").param("retiredAt", null).update())
                .hasMessageContaining("rle_version_retired_at_check");
        assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_rle_version_published'")
                .query(String.class).single()).contains("WHERE (status = 'PUBLISHED'::text)");

        // When the documented rollback step of V11 runs
        jdbc.sql("ALTER TABLE rle_version DROP CONSTRAINT rle_version_retired_at_check").update();
        jdbc.sql("DROP INDEX idx_rle_version_published").update();
        jdbc.sql("ALTER TABLE rle_version DROP COLUMN retired_at").update();

        // Then the table is back to its V5 shape, with its rows
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'rle_version'"
                + " AND column_name = 'retired_at'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void schema_status_reports_pending_migrations_until_migrated() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);

        assertThat(migrator.status()).isEqualTo(new SchemaStatus(null, 6));
        assertThat(migrator.status().isCurrent()).isFalse();

        migrator.migrate();

        assertThat(migrator.status()).isEqualTo(new SchemaStatus("11", 0));
        assertThat(migrator.status().isCurrent()).isTrue();
    }

    private static DecisionLogPartitions partitions(DataSource db) {
        return new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)));
    }

    private static DataSource migratedDatabase() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        return db;
    }

    private static String partitionOfRow(DataSource db, String createdAt) {
        JdbcClient jdbc = JdbcClient.create(db);
        String id = "01JD" + String.format("%022d", DATABASES.incrementAndGet());
        insertDecision(jdbc, id, createdAt);
        return partitionOf(jdbc, id);
    }

    private static void insertDecision(JdbcClient jdbc, String id, String createdAt) {
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                    outcome, masked_inputs, outputs, matched_rule_ids, eval_clock, duration_micros, log_mode)
                VALUES ('bank-a', CAST(:createdAt AS timestamptz), :id, 'card-fee', 1, 'FIRST',
                    'OK', '{"tier":"GOLD"}', '{"fee":"0 VND"}', '["R1"]', CAST(:createdAt AS timestamptz), 42,
                    'TRANSACTIONAL')
                """).param("createdAt", createdAt).param("id", id).update();
    }

    private static String partitionOf(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT tableoid::regclass::text FROM rle_decision WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private static DataSource freshDatabase() {
        String name = "rle_it_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSourceFor(name);
    }

    private static DataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private static List<String> rootTables(DataSource db) {
        return JdbcClient.create(db).sql("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                """).query(String.class).list();
    }

    private static List<String> partitionsOf(DataSource db) {
        return JdbcClient.create(db).sql("""
                SELECT child.relname FROM pg_inherits i
                JOIN pg_class child ON child.oid = i.inhrelid
                WHERE i.inhparent = 'rle_decision'::regclass
                """).query(String.class).list();
    }
}
