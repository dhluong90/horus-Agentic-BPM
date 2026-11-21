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
            new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void TR_EMB_01_clean_database_gets_every_rle_table() {
        DataSource db = freshDatabase();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.initialVersion()).isNull();
        assertThat(result.currentVersion()).isEqualTo("28");
        assertThat(result.migrationsExecuted()).isEqualTo(21);
        assertThat(rootTables(db)).containsExactlyInAnyOrder(
                "rle_schema_history",
                "rle_rule_set",
                "rle_version",
                "rle_version_history",
                "rle_engine_key",
                "rle_masking_key_check",
                "rle_setting_seen",
                "rle_rule",
                "rle_decision",
                "rle_decision_log_drop",
                "rle_decision_outbox",
                "rle_idempotency",
                "rle_version_function");
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

        assertThat(result.currentVersion()).isEqualTo("28");
        assertThat(result.migrationsExecuted()).isEqualTo(21);
        assertThat(jdbc.sql("SELECT version FROM rle_schema_history ORDER BY installed_rank").query(String.class).list())
                .containsExactly("0", "1", "2", "3", "4", "5", "6", "10", "11", "12", "14", "15", "16", "17", "18", "19", "20", "21", "24", "25", "26", "28");
        assertThat(jdbc.sql("SELECT amount_minor FROM loan_application WHERE id = 1").query(Long.class).single())
                .isEqualTo(800_000_000L);
    }

    @Test
    void TR_EMB_06_second_run_applies_nothing_and_keeps_the_history() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        migrator.migrate();

        SchemaMigrationResult second = migrator.migrate();

        assertThat(second.initialVersion()).isEqualTo("28");
        assertThat(second.currentVersion()).isEqualTo("28");
        assertThat(second.migrationsExecuted()).isZero();
        assertThat(migrator.currentVersion()).contains("28");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_schema_history").query(Long.class).single())
                .isEqualTo(21L);
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

        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'rle_version_history'"
                + " AND column_name = 'no_version_in_effect'").query(Long.class).single()).isEqualTo(1L);

        // When the documented rollback step of V11 runs
        jdbc.sql("ALTER TABLE rle_version_history DROP COLUMN no_version_in_effect").update();
        jdbc.sql("ALTER TABLE rle_version DROP CONSTRAINT rle_version_retired_at_check").update();
        jdbc.sql("DROP INDEX idx_rle_version_published").update();
        jdbc.sql("ALTER TABLE rle_version DROP COLUMN retired_at").update();

        // Then the table is back to its shape before V11, with its rows
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'rle_version'"
                + " AND column_name = 'retired_at'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void RLE_038_V18_holds_four_eyes_on_retirement_and_its_rollback_step_runs() throws java.io.IOException {
        // Given a migrated database with tet-fee-waiver v1 PUBLISHED and v2 DRAFT
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES ('bank-a', 'RS1', 'tet-fee-waiver',"
                + " now())").update();
        String insert = "INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy,"
                + " canonical_json, canonical_hash, source_format, source_document, source_hash, created_at)"
                + " VALUES ('bank-a', :id, 'RS1', :num, :status, 'FIRST', '{}', repeat('a', 64), 'YAML', '',"
                + " repeat('b', 64), now())";
        jdbc.sql(insert).param("id", "V1").param("num", 1).param("status", "PUBLISHED").update();
        jdbc.sql(insert).param("id", "V2").param("num", 2).param("status", "DRAFT").update();
        String request = "UPDATE rle_version SET retire_requested_by = :by, retire_requested_at = now(),"
                + " retire_reason = :reason, retire_confirm_no_version = true WHERE tenant_id = 'bank-a' AND id = :id";

        // Then a complete request is accepted on the published version only, and a partial one never
        assertThat(jdbc.sql(request).param("by", "bob").param("reason", "Tet promotion ended").param("id", "V1").update())
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(request).param("by", "bob")
                .param("reason", "Tet promotion ended").param("id", "V2").update())
                .hasMessageContaining("rle_version_retire_request_published");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version SET retire_reason = NULL"
                + " WHERE tenant_id = 'bank-a' AND id = 'V1'").update())
                .hasMessageContaining("rle_version_retire_request_complete");
        // and a retirement that leaves the request on the row is refused
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version SET status = 'RETIRED',"
                + " retired_at = now() WHERE tenant_id = 'bank-a' AND id = 'V1'").update())
                .hasMessageContaining("rle_version_retire_request_published");
        // and a RETIRED history row confirmed by its own requester, or naming a requester outside four-eyes, is refused
        String history = "INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status,"
                + " actor, reason, approval_mode, lock_version, occurred_at, retire_requested_by) VALUES ('bank-a', :id,"
                + " 'V1', 'RETIRED', 'PUBLISHED', 'RETIRED', :actor, 'Tet promotion ended', :mode, 3, now(), 'bob')";
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(history).param("id", "H1")
                .param("actor", "bob").param("mode", "four-eyes").update())
                .hasMessageContaining("rle_version_history_retire_four_eyes");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql(history).param("id", "H2")
                .param("actor", "carol").param("mode", "single").update())
                .hasMessageContaining("rle_version_history_retire_four_eyes");
        assertThat(jdbc.sql(history).param("id", "H3").param("actor", "carol").param("mode", "four-eyes").update())
                .isEqualTo(1);

        // When the documented rollback step of V18 runs, Then the columns are gone and the rows stay
        jdbc.sql(rollbackStatements("V18__rle_version_retire_request.sql")).update();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE column_name = 'retire_requested_by'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Long.class).single()).isEqualTo(2L);
    }

    /** The statements between {@code rollback-begin} and {@code rollback-end} of a migration, without the comment marks. */
    private static String rollbackStatements(String migration) throws java.io.IOException {
        String path = "io/github/dhluong90/bpm/rules/postgres/migration/" + migration;
        try (java.io.InputStream in = RuleEngineSchemaMigratorIT.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            String script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String block = script.substring(script.indexOf("-- rollback-begin\n") + "-- rollback-begin\n".length(),
                    script.indexOf("-- rollback-end"));
            return block.lines().map(line -> line.replaceFirst("^--\\s{0,3}", ""))
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    @Test
    void RLE_008_V10_adds_the_pinned_mark_to_every_partition_and_its_rollback_step_runs() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        insertDecision(jdbc, "01KCS3W1DQ0M8V6T2B9R5N7K4E", "2026-11-20T10:00:00Z");

        // Then the column exists on the parent and the default partition, false for rows that did not pin
        assertThat(jdbc.sql("SELECT pinned FROM rle_decision").query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE column_name = 'pinned'"
                + " AND table_name IN ('rle_decision', 'rle_decision_default')").query(Long.class).single())
                .isEqualTo(2L);

        // When the documented rollback step runs, Then the column is gone and the row stays
        jdbc.sql("ALTER TABLE rle_decision DROP COLUMN pinned").update();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE column_name = 'pinned'"
                + " AND table_name = 'rle_decision'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void schema_status_reports_pending_migrations_until_migrated() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);

        assertThat(migrator.status()).isEqualTo(new SchemaStatus(null, 21));
        assertThat(migrator.status().isCurrent()).isFalse();

        migrator.migrate();

        assertThat(migrator.status()).isEqualTo(new SchemaStatus("28", 0));
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
