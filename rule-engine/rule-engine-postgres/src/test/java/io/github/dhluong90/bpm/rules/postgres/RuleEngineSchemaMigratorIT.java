package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** The versions of the scripts the engine ships, read from its classpath location, never hard-coded. */
    private static final List<String> VERSIONS = shippedVersions();

    private static final String LATEST = VERSIONS.get(VERSIONS.size() - 1);

    private static List<String> shippedVersions() {
        try {
            return java.util.Arrays.stream(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                            .getResources(RuleEngineSchemaMigrator.MIGRATION_LOCATION.replace("classpath:", "classpath*:")
                                    + "/V*__*.sql"))
                    .map(resource -> resource.getFilename())
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                    .toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The history versions of a host that had tables first: the baseline at 0, then every shipped script. */
    private static List<String> withBaseline() {
        List<String> all = new java.util.ArrayList<>(List.of("0"));
        all.addAll(VERSIONS);
        return all;
    }

    @Test
    void TR_EMB_01_clean_database_gets_every_rle_table() {
        DataSource db = freshDatabase();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.initialVersion()).isNull();
        assertThat(result.currentVersion()).isEqualTo(LATEST);
        assertThat(result.migrationsExecuted()).isEqualTo(VERSIONS.size());
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
                "rle_version_function",
                "rle_simulation_report",
                "rle_simulation_report_attachment");
    }

    @Test
    void RLE_229_V4_adds_nullable_columns_and_its_documented_rollback_step_removes_them() {
        DataSource db = migratedDatabase();
        JdbcClient jdbc = JdbcClient.create(db);
        String columns = "SELECT table_name || '.' || column_name FROM information_schema.columns WHERE "
                + "(column_name = 'source_file_name' AND table_name IN ('rle_version', 'rle_version_history')) "
                + "OR (column_name IN ('budget_limit', 'step_budget', 'timeout_ms') AND table_name = 'rle_decision') ORDER BY 1";
        assertThat(jdbc.sql(columns).query(String.class).list()).containsExactly("rle_decision.budget_limit",
                "rle_decision.step_budget", "rle_decision.timeout_ms", "rle_version.source_file_name",
                "rle_version_history.source_file_name");
        // The CHECKs were added NOT VALID and then validated: none is left unvalidated on a populated table
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE conname LIKE '%source_file_name_check' "
                + "AND convalidated").query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_constraint WHERE NOT convalidated").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE is_nullable = 'NO' AND "
                + "column_name IN ('source_file_name', 'budget_limit', 'step_budget', 'timeout_ms')").query(Long.class).single()).isZero();

        // The rollback step written at the top of V4, run by hand
        jdbc.sql("ALTER TABLE rle_version_history DROP COLUMN source_file_name").update();
        jdbc.sql("ALTER TABLE rle_version DROP COLUMN source_file_name").update();
        jdbc.sql("ALTER TABLE rle_decision DROP COLUMN timeout_ms").update();
        jdbc.sql("ALTER TABLE rle_decision DROP COLUMN step_budget").update();
        jdbc.sql("ALTER TABLE rle_decision DROP COLUMN budget_limit").update();

        assertThat(jdbc.sql(columns).query(String.class).list()).isEmpty();
    }

    @Test
    void a_database_made_by_a_build_before_the_baseline_is_refused_with_a_request_to_reset() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        // Given a history written by an earlier build: the one recorded migration has another checksum
        JdbcClient.create(db).sql("UPDATE rle_schema_history SET checksum = 1 WHERE version = '1'").update();

        // When the engine starts, Then it stops, says to reset, and changes nothing
        assertThatThrownBy(() -> new RuleEngineSchemaMigrator(db).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drop the schema and start again");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_schema_history").query(Long.class).single()).isEqualTo((long) VERSIONS.size());
    }

    @Test
    void TR_OPS_02_version_history_accepts_a_rule_set_row_and_refuses_a_malformed_row_of_either_kind() {
        DataSource db = migratedDatabase();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES ('bank-a', 'RS1', 'card-fee', now())")
                .update();
        jdbc.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, created_at)
                VALUES ('bank-a', 'V1', 'RS1', 1, 'DRAFT', 'FIRST', '{}', repeat('a', 64), 'YAML', 'x', repeat('b', 64),
                  now())
                """).update();
        String evidence = "'{\"format\":\"csv\",\"versions\":[1],\"decisionIds\":[],"
                + "\"contentSha256\":\"9f4a\",\"bytes\":10}'";
        jdbc.sql("INSERT INTO rle_version_history (tenant_id, id, version_id, action, to_status, actor, lock_version,"
                + " occurred_at) VALUES ('bank-a', 'H1', 'V1', 'CREATED', 'DRAFT', 'lan', 0, now())").update();

        // A rule set row: an evidence export of a rule set, with evidence and no version
        jdbc.sql("INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                + " VALUES ('bank-a', 'H2', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now(), " + evidence + ")").update();

        List<String> refused = List.of(
                // a version row without its status after or its lock version
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, actor, occurred_at)"
                        + " VALUES ('bank-a', 'H3', 'V1', 'REQUESTED', 'lan', now())",
                // an export recorded on a version
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, to_status, actor, lock_version,"
                        + " occurred_at, evidence) VALUES ('bank-a', 'H4', 'V1', 'EVIDENCE_EXPORTED', 'DRAFT', 'ha', 0,"
                        + " now(), " + evidence + ")",
                // a rule set row without evidence
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at)"
                        + " VALUES ('bank-a', 'H5', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now())",
                // a rule set row of another tenant's rule set
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                        + " VALUES ('bank-b', 'H6', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now(), " + evidence + ")",
                // an unknown action
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                        + " VALUES ('bank-a', 'H7', 'RS1', 'EVIDENCE_DELETED', 'ha', now(), " + evidence + ")");
        for (String sql : refused) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).hasMessageContaining("violates");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history").query(Long.class).single()).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT conname FROM pg_constraint WHERE conrelid = 'rle_version_history'::regclass"
                + " AND NOT convalidated").query(String.class).list()).isEmpty();
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

        assertThat(result.currentVersion()).isEqualTo(LATEST);
        assertThat(result.migrationsExecuted()).isEqualTo(VERSIONS.size());
        assertThat(jdbc.sql("SELECT version FROM rle_schema_history ORDER BY installed_rank").query(String.class).list())
                .containsExactlyElementsOf(withBaseline());
        assertThat(jdbc.sql("SELECT amount_minor FROM loan_application WHERE id = 1").query(Long.class).single())
                .isEqualTo(800_000_000L);
    }

    @Test
    void TR_EMB_06_second_run_applies_nothing_and_keeps_the_history() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        migrator.migrate();

        SchemaMigrationResult second = migrator.migrate();

        assertThat(second.initialVersion()).isEqualTo(LATEST);
        assertThat(second.currentVersion()).isEqualTo(LATEST);
        assertThat(second.migrationsExecuted()).isZero();
        assertThat(migrator.currentVersion()).contains(LATEST);
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_schema_history").query(Long.class).single())
                .isEqualTo((long) VERSIONS.size());
    }

    @Test
    void schema_status_reports_pending_migrations_until_migrated() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);

        assertThat(migrator.status()).isEqualTo(new SchemaStatus(null, VERSIONS.size()));
        assertThat(migrator.status().isCurrent()).isFalse();

        migrator.migrate();

        assertThat(migrator.status()).isEqualTo(new SchemaStatus(LATEST, 0));
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
