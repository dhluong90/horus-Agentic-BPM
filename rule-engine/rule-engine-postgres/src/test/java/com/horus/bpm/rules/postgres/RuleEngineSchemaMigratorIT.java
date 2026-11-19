package com.horus.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the engine's migrations against a real PostgreSQL, one fresh database per test. */
@Testcontainers
class RuleEngineSchemaMigratorIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void tr_emb_01_clean_database_gets_every_rle_table() {
        DataSource db = freshDatabase();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.initialVersion()).isNull();
        assertThat(result.currentVersion()).isEqualTo("1");
        assertThat(result.migrationsExecuted()).isEqualTo(1);
        assertThat(rootTables(db)).containsExactlyInAnyOrder(
                "rle_schema_history",
                "rle_rule_set",
                "rle_version",
                "rle_rule",
                "rle_decision",
                "rle_decision_outbox",
                "rle_idempotency");
    }

    @Test
    void tr_ops_04_decision_log_has_current_and_next_month_partitions() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);

        List<String> expected = jdbc.sql("""
                SELECT 'rle_decision_' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC'), 'YYYY_MM')
                UNION ALL
                SELECT 'rle_decision_' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC') + INTERVAL '1 month', 'YYYY_MM')
                """).query(String.class).list();

        assertThat(partitionsOf(db, "rle_decision"))
                .containsAll(expected)
                .contains("rle_decision_default")
                .hasSize(3);
    }

    @Test
    void tr_ops_04_decision_of_this_month_lands_in_its_monthly_partition_not_the_default() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);

        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                    outcome, masked_inputs, outputs, matched_rule_ids, eval_clock, duration_micros, log_mode)
                VALUES ('bank-a', now(), '01JD0000000000000000000001', 'card-fee', 1, 'FIRST',
                    'OK', '{"tier":"GOLD"}', '{"fee":"0 VND"}', '["R1"]', now(), 42, 'TRANSACTIONAL')
                """).update();

        String partition = jdbc.sql("SELECT tableoid::regclass::text FROM rle_decision WHERE id = '01JD0000000000000000000001'")
                .query(String.class).single();
        String currentMonth = jdbc.sql(
                "SELECT 'rle_decision_' || to_char(date_trunc('month', now() AT TIME ZONE 'UTC'), 'YYYY_MM')")
                .query(String.class).single();
        assertThat(partition).isEqualTo(currentMonth);
    }

    @Test
    void tr_emb_06_engine_migrates_a_schema_that_already_has_host_tables_and_host_history() {
        DataSource db = freshDatabase();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("CREATE TABLE loan_application (id BIGINT PRIMARY KEY, amount_minor BIGINT NOT NULL, currency CHAR(3) NOT NULL)")
                .update();
        jdbc.sql("""
                CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version TEXT, description TEXT)
                """).update();
        jdbc.sql("INSERT INTO flyway_schema_history VALUES (1, '1', 'create loan application')").update();

        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        assertThat(result.currentVersion()).isEqualTo("1");
        assertThat(result.migrationsExecuted()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT version || ':' || description FROM flyway_schema_history")
                .query(String.class).list())
                .containsExactly("1:create loan application");
        assertThat(jdbc.sql("SELECT version FROM rle_schema_history ORDER BY installed_rank")
                .query(String.class).list())
                .containsExactly("0", "1");
    }

    @Test
    void tr_emb_06_second_run_applies_nothing_and_keeps_the_history() {
        DataSource db = freshDatabase();
        RuleEngineSchemaMigrator migrator = new RuleEngineSchemaMigrator(db);
        migrator.migrate();

        SchemaMigrationResult second = migrator.migrate();

        assertThat(second.initialVersion()).isEqualTo("1");
        assertThat(second.currentVersion()).isEqualTo("1");
        assertThat(second.migrationsExecuted()).isZero();
        assertThat(migrator.currentVersion()).contains("1");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_schema_history").query(Long.class).single())
                .isEqualTo(1L);
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

    private static List<String> partitionsOf(DataSource db, String parent) {
        return JdbcClient.create(db).sql("""
                SELECT child.relname FROM pg_inherits i
                JOIN pg_class child ON child.oid = i.inhrelid
                JOIN pg_class parent ON parent.oid = i.inhparent
                WHERE parent.relname = :parent
                """).param("parent", parent).query(String.class).list();
    }
}
