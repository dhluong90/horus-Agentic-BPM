package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.postgres.migration.V24__rle_version_history_rule_set_events;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * V24 (PD-116, #118) on a database that already holds version history: rule set rows become possible, every version row
 * keeps its shape, every new constraint is validated, the index is built CONCURRENTLY and valid, and a re-run or an
 * interrupted build is repaired.
 */
@Testcontainers
class RuleSetEventsMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String INDEX = V24__rle_version_history_rule_set_events.INDEX;
    private static final String EVIDENCE = "'{\"format\":\"csv\",\"versions\":[1],\"decisionIds\":[],"
            + "\"contentSha256\":\"9f4a\",\"bytes\":10}'";

    @Test
    void TR_OPS_02_V24_keeps_existing_rows_validates_every_constraint_and_builds_a_valid_index() {
        DataSource db = databaseAtV20WithHistory();
        JdbcClient jdbc = JdbcClient.create(db);

        new RuleEngineSchemaMigrator(db).migrate();

        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history WHERE version_id IS NOT NULL AND to_status = 'DRAFT'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'rle_version_history'::regclass AND NOT convalidated
                """).query(String.class).list()).isEmpty();
        assertThat(jdbc.sql("""
                SELECT conname FROM pg_constraint WHERE conrelid = 'rle_version_history'::regclass
                  AND conname IN ('rle_version_history_rule_set_fk', 'rle_version_history_version_event',
                                  'rle_version_history_rule_set_event', 'rle_version_history_action_check')
                ORDER BY conname
                """).query(String.class).list()).hasSize(4);
        assertThat(valid(jdbc)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_schema_history WHERE version = '24' AND success")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void TR_OPS_02_V24_accepts_a_rule_set_row_and_refuses_a_malformed_row_of_either_kind() {
        DataSource db = databaseAtV20WithHistory();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);

        jdbc.sql("INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                + " VALUES ('bank-a', 'H2', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now(), " + EVIDENCE + ")").update();

        List<String> refused = List.of(
                // a version row without its status after or its lock version
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, actor, occurred_at)"
                        + " VALUES ('bank-a', 'H3', 'V1', 'REQUESTED', 'lan', now())",
                // an export recorded on a version
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, to_status, actor, lock_version,"
                        + " occurred_at, evidence) VALUES ('bank-a', 'H4', 'V1', 'EVIDENCE_EXPORTED', 'DRAFT', 'ha', 0,"
                        + " now(), " + EVIDENCE + ")",
                // a rule set row without evidence
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at)"
                        + " VALUES ('bank-a', 'H5', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now())",
                // a rule set row of another tenant's rule set
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                        + " VALUES ('bank-b', 'H6', 'RS1', 'EVIDENCE_EXPORTED', 'ha', now(), " + EVIDENCE + ")",
                // an unknown action
                "INSERT INTO rle_version_history (tenant_id, id, rule_set_id, action, actor, occurred_at, evidence)"
                        + " VALUES ('bank-a', 'H7', 'RS1', 'EVIDENCE_DELETED', 'ha', now(), " + EVIDENCE + ")");
        for (String sql : refused) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).as(sql).hasMessageContaining("violates");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void TR_OPS_02_V24_rerun_is_a_no_op_and_an_invalid_leftover_index_is_replaced() throws SQLException {
        DataSource db = databaseAtV20WithHistory();
        JdbcClient jdbc = JdbcClient.create(db);
        // Given an interrupted CONCURRENTLY build: the index exists and is INVALID
        try (Connection connection = db.getConnection()) {
            connection.setAutoCommit(true);
            connection.createStatement().execute("ALTER TABLE rle_version_history ADD COLUMN rule_set_id VARCHAR(26)");
        }
        jdbc.sql("CREATE INDEX " + INDEX + " ON rle_version_history (tenant_id, rule_set_id, occurred_at DESC, id DESC)"
                + " WHERE rule_set_id IS NOT NULL").update();
        jdbc.sql("UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + INDEX + "'::regclass").update();
        long leftover = oid(jdbc);

        new RuleEngineSchemaMigrator(db).migrate();
        long rebuilt = oid(jdbc);
        try (Connection connection = db.getConnection()) {
            V24__rle_version_history_rule_set_events.apply(connection);
        }

        assertThat(rebuilt).isNotEqualTo(leftover);
        assertThat(valid(jdbc)).isTrue();
        assertThat(oid(jdbc)).as("a re-run keeps the valid index").isEqualTo(rebuilt);
    }

    private static boolean valid(JdbcClient jdbc) {
        return jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(:i)").param("i", INDEX)
                .query(Boolean.class).single();
    }

    private static long oid(JdbcClient jdbc) {
        return jdbc.sql("SELECT to_regclass(:i)::oid::bigint").param("i", INDEX).query(Long.class).single();
    }

    /** A database at V20, as a host on the previous release, with one rule set, one version and its history row. */
    private static DataSource databaseAtV20WithHistory() {
        String name = "rle_v24_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSourceFor(name);
        Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .configuration(RuleEngineSchemaMigrator.LOCKING)
                .dataSource(db)
                .table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("20")
                .load()
                .migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES ('bank-a', 'RS1', 'card-fee', now())")
                .update();
        jdbc.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, created_at)
                VALUES ('bank-a', 'V1', 'RS1', 1, 'DRAFT', 'FIRST', '{}', repeat('a', 64), 'YAML', 'x', repeat('b', 64),
                  now())
                """).update();
        jdbc.sql("""
                INSERT INTO rle_version_history (tenant_id, id, version_id, action, to_status, actor, lock_version,
                  occurred_at) VALUES ('bank-a', 'H1', 'V1', 'CREATED', 'DRAFT', 'lan', 0, now())
                """).update();
        return db;
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
}
