package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.postgres.migration.V20__rle_decision_correlation_index;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
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
 * V20 (#379) builds the case-reference index on the partitioned decision log without blocking writes: parent
 * {@code ON ONLY}, then one {@code CONCURRENTLY} index per partition, attached. Runs V20 on a log that already holds
 * several monthly partitions with rows.
 */
@Testcontainers
class DecisionCorrelationIndexMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String PARENT = V20__rle_decision_correlation_index.PARENT_INDEX;

    @Test
    void TR_OPS_02_V20_builds_a_valid_attached_index_on_every_partition() {
        DataSource db = databaseAtV17WithPartitionsAndRows();
        JdbcClient jdbc = JdbcClient.create(db);

        new RuleEngineSchemaMigrator(db).migrate();

        assertThat(partitions(jdbc)).containsExactlyInAnyOrder("rle_decision_default", "rle_decision_2027_01",
                "rle_decision_2027_02", "rle_decision_2027_03");
        assertThat(jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(:i)")
                .param("i", PARENT).query(Boolean.class).single()).isTrue();
        assertThat(attachedValid(jdbc)).containsExactlyInAnyOrderElementsOf(partitions(jdbc));
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isEqualTo(5L);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_schema_history WHERE version = '20' AND success")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void TR_OPS_02_rerun_is_a_no_op() throws SQLException {
        DataSource db = databaseAtV17WithPartitionsAndRows();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        List<String> before = indexOids(jdbc);

        try (Connection connection = db.getConnection()) {
            assertThat(V20__rle_decision_correlation_index.build(connection)).isZero();
        }

        assertThat(indexOids(jdbc)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(attachedValid(jdbc)).containsExactlyInAnyOrderElementsOf(partitions(jdbc));
    }

    @Test
    void TR_OPS_02_an_invalid_leftover_is_replaced() throws SQLException {
        DataSource db = databaseAtV17WithPartitionsAndRows();
        JdbcClient jdbc = JdbcClient.create(db);
        // Given a failed CONCURRENTLY build of one partition's index: present, INVALID, not attached
        jdbc.sql("CREATE INDEX rle_decision_2027_02_correlation_idx ON rle_decision_2027_02"
                + " (tenant_id, correlation_id, created_at DESC, id DESC) WHERE correlation_id IS NOT NULL").update();
        jdbc.sql("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'rle_decision_2027_02_correlation_idx'::regclass")
                .update();
        long leftover = jdbc.sql("SELECT 'rle_decision_2027_02_correlation_idx'::regclass::oid::bigint")
                .query(Long.class).single();

        // When V20 runs
        new RuleEngineSchemaMigrator(db).migrate();

        // Then the leftover is gone, replaced by a valid attached index, and the parent is valid
        assertThat(jdbc.sql("SELECT x.indisvalid FROM pg_index x WHERE x.indexrelid = 'rle_decision_2027_02_correlation_idx'::regclass")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT 'rle_decision_2027_02_correlation_idx'::regclass::oid::bigint")
                .query(Long.class).single()).isNotEqualTo(leftover);
        assertThat(attachedValid(jdbc)).containsExactlyInAnyOrderElementsOf(partitions(jdbc));
        assertThat(jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(:i)")
                .param("i", PARENT).query(Boolean.class).single()).isTrue();
    }

    @Test
    void TR_OPS_02_a_partition_created_after_V20_gets_the_index_automatically() {
        DataSource db = databaseAtV17WithPartitionsAndRows();
        new RuleEngineSchemaMigrator(db).migrate();
        JdbcClient jdbc = JdbcClient.create(db);

        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureAhead(Instant.parse("2027-04-10T00:00:00Z"), java.time.Duration.ZERO);

        assertThat(partitions(jdbc)).contains("rle_decision_2027_04", "rle_decision_2027_05");
        assertThat(attachedValid(jdbc)).containsExactlyInAnyOrderElementsOf(partitions(jdbc));
        assertThat(jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(:i)")
                .param("i", PARENT).query(Boolean.class).single()).isTrue();
    }

    private static DataSource databaseAtV17WithPartitionsAndRows() {
        String name = "rle_v20_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSourceFor(name);
        Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .configuration(RuleEngineSchemaMigrator.LOCKING)
                .dataSource(db)
                .table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .target("17")
                .load()
                .migrate();
        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureAhead(Instant.parse("2027-01-10T00:00:00Z"), java.time.Duration.ofDays(60));
        JdbcClient jdbc = JdbcClient.create(db);
        insert(jdbc, "01JD0000000000000000000001", "2027-01-05T10:00:00Z", "CASE-1");
        insert(jdbc, "01JD0000000000000000000002", "2027-01-20T10:00:00Z", null);
        insert(jdbc, "01JD0000000000000000000003", "2027-02-11T10:00:00Z", "CASE-1");
        insert(jdbc, "01JD0000000000000000000004", "2027-03-02T10:00:00Z", "CASE-2");
        insert(jdbc, "01JD0000000000000000000005", "2026-06-01T10:00:00Z", "CASE-3"); // default partition
        return db;
    }

    private static void insert(JdbcClient jdbc, String id, String createdAt, String correlationId) {
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                    outcome, masked_inputs, matched_rule_ids, eval_clock, duration_micros, log_mode, correlation_id)
                VALUES ('bank-a', CAST(:at AS timestamptz), :id, 'card-fee', 1, 'FIRST', 'OK', '{}', '[]',
                    CAST(:at AS timestamptz), 42, 'TRANSACTIONAL', :corr)
                """).param("at", createdAt).param("id", id).param("corr", correlationId).update();
    }

    private static List<String> partitions(JdbcClient jdbc) {
        return jdbc.sql("SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
                + " WHERE i.inhparent = 'rle_decision'::regclass").query(String.class).list();
    }

    /** Partitions whose index is valid and attached to the parent index. */
    private static List<String> attachedValid(JdbcClient jdbc) {
        return jdbc.sql("""
                SELECT t.relname FROM pg_inherits i
                JOIN pg_index x ON x.indexrelid = i.inhrelid
                JOIN pg_class t ON t.oid = x.indrelid
                WHERE i.inhparent = to_regclass(:parent) AND x.indisvalid
                """).param("parent", PARENT).query(String.class).list();
    }

    private static List<String> indexOids(JdbcClient jdbc) {
        return jdbc.sql("SELECT c.relname || ':' || c.oid FROM pg_class c WHERE c.relname LIKE '%correlation_idx'"
                + " OR c.relname = :parent").param("parent", PARENT).query(String.class).list();
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
