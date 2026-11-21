package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.postgres.migration.V27__pre_history_time_brin;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V26 and V27 (#110): the statistics tables, and the time index of {@code pre_history} built CONCURRENTLY on every
 * partition outside a transaction, attached to a parent index that new partitions inherit, and safe to run again
 * after a failed build (TR-OPS-01). Each test migrates a schema of its own.
 */
class RollupMigrationTest {

    private static final String PARENT = V27__pre_history_time_brin.PARENT_INDEX;

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE).locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    private static Map<String, Object> index(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT i.indisvalid AS valid, i.indexrelid::bigint AS oid, pg_get_indexdef(i.indexrelid) AS def "
                        + "FROM pg_index i WHERE i.indexrelid = to_regclass(?)").param(name).query().listOfRows().stream()
                .findFirst().orElse(Map.of());
    }

    /** The partitions whose valid index is attached to the parent time index, by table name. */
    private static List<String> attachedPartitions(JdbcClient jdbc) {
        return jdbc.sql("SELECT t.relname FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid "
                        + "JOIN pg_class t ON t.oid = x.indrelid WHERE i.inhparent = to_regclass(?) AND x.indisvalid "
                        + "ORDER BY t.relname").param(PARENT).query(String.class).list();
    }

    private static void monthly(JdbcClient jdbc, String name, String from, String to) {
        jdbc.sql("CREATE TABLE " + name + " PARTITION OF pre_history FOR VALUES FROM ('" + from + "') TO ('" + to + "')")
                .update();
    }

    private static void history(JdbcClient jdbc, String id, String occurredAt) {
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                        + "details) VALUES ('bank-a', ?::timestamptz, ?, 'I0000000000000000000000001', "
                        + "'D0000000000000000000000001', 'NODE_COMPLETED', 'approve', '{}'::jsonb)")
                .params(occurredAt, id).update();
    }

    @Test
    void TR_OPS_01_v27_indexes_every_existing_partition_and_new_partitions_inherit_it() {
        // Given a database at V26 whose host created monthly partitions for October and November 2026, with history in
        // both and in the default partition
        DataSource ds = TestEngine.emptySchema("mig_v27_partitions");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "26").migrate();
        monthly(jdbc, "pre_history_2026_10", "2026-10-01 00:00:00+00", "2026-11-01 00:00:00+00");
        monthly(jdbc, "pre_history_2026_11", "2026-11-01 00:00:00+00", "2026-12-01 00:00:00+00");
        history(jdbc, "H0000000000000000000000001", "2026-10-15 09:30:00+00");
        history(jdbc, "H0000000000000000000000002", "2026-11-20 10:00:30+00");
        history(jdbc, "H0000000000000000000000003", "2028-01-05 08:00:00+00");

        // When V27 runs
        int applied = flyway(ds, "27").migrate().migrationsExecuted;

        // Then each of the three partitions has its own valid BRIN, attached, and the parent index is valid
        assertThat(applied).isEqualTo(1);
        assertThat(attachedPartitions(jdbc))
                .containsExactly("pre_history_2026_10", "pre_history_2026_11", "pre_history_default");
        for (String partition : List.of("pre_history_2026_10", "pre_history_2026_11", "pre_history_default")) {
            assertThat(index(jdbc, partition + "_time_brin")).containsEntry("valid", true);
            assertThat((String) index(jdbc, partition + "_time_brin").get("def")).contains("USING brin (occurred_at)");
        }
        assertThat(index(jdbc, PARENT)).containsEntry("valid", true);
        assertThat(jdbc.sql("SELECT success FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE
                + " WHERE version IN ('26', '27') ORDER BY installed_rank").query(Boolean.class).list())
                .containsExactly(true, true);

        // and a partition the host creates afterwards, by PARTITION OF or by ATTACH PARTITION, gets the index
        monthly(jdbc, "pre_history_2026_12", "2026-12-01 00:00:00+00", "2027-01-01 00:00:00+00");
        jdbc.sql("CREATE TABLE pre_history_2027_01 (LIKE pre_history INCLUDING DEFAULTS INCLUDING CONSTRAINTS)").update();
        jdbc.sql("ALTER TABLE pre_history ATTACH PARTITION pre_history_2027_01 "
                + "FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00')").update();
        assertThat(attachedPartitions(jdbc)).containsExactly("pre_history_2026_10", "pre_history_2026_11",
                "pre_history_2026_12", "pre_history_2027_01", "pre_history_default");
        assertThat(index(jdbc, PARENT)).containsEntry("valid", true);

        // and a rollup slice in November reads only November's partition, by an index condition on its time range
        String plan = new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status -> {
            JdbcClient tx = JdbcClient.create(ds);
            tx.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", tx.sql("EXPLAIN " + ProcessListQueries.ROLLUP_SLICE_SQL)
                    .params(OffsetDateTime.parse("2026-11-20T10:00:00Z"), OffsetDateTime.parse("2026-11-20T10:01:00Z"))
                    .query(String.class).list());
        });
        String outer = plan.split("SubPlan")[0];
        assertThat(outer).as(plan).doesNotContain("Seq Scan").contains("pre_history_2026_11 h")
                .doesNotContain("pre_history_2026_10").doesNotContain("pre_history_default");
        assertThat(outer.lines().filter(l -> l.contains("Index Cond:"))).as(plan)
                .anySatisfy(l -> assertThat(l).contains("occurred_at >=").contains("occurred_at <"));
    }

    @Test
    void TR_OPS_01_a_rerun_after_a_failed_build_replaces_the_invalid_index_and_attaches_the_rest() throws SQLException {
        // Given a database at V26 with a November partition, and a first V27 run that created the parent index,
        // attached the default partition's index, and was interrupted while building November's, leaving it INVALID
        DataSource ds = TestEngine.emptySchema("mig_v27_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "26").migrate();
        monthly(jdbc, "pre_history_2026_11", "2026-11-01 00:00:00+00", "2026-12-01 00:00:00+00");
        history(jdbc, "H0000000000000000000000001", "2026-11-20 10:00:30+00");
        jdbc.sql("CREATE INDEX " + PARENT + " ON ONLY pre_history USING brin (occurred_at)").update();
        jdbc.sql("CREATE INDEX pre_history_default_time_brin ON pre_history_default USING brin (occurred_at)").update();
        jdbc.sql("ALTER INDEX " + PARENT + " ATTACH PARTITION pre_history_default_time_brin").update();
        jdbc.sql("CREATE INDEX pre_history_2026_11_time_brin ON pre_history_2026_11 USING brin (occurred_at)").update();
        // The test user is a superuser: it marks the index the way an interrupted CONCURRENTLY build leaves it.
        jdbc.sql("UPDATE pg_index SET indisvalid = false WHERE indexrelid = 'pre_history_2026_11_time_brin'::regclass")
                .update();
        Object invalidOid = index(jdbc, "pre_history_2026_11_time_brin").get("oid");
        assertThat(index(jdbc, "pre_history_2026_11_time_brin")).containsEntry("valid", false);
        assertThat(index(jdbc, PARENT)).containsEntry("valid", false);

        // When V27 runs
        int applied = flyway(ds, "27").migrate().migrationsExecuted;

        // Then the invalid index is replaced by a new valid one, both partitions are attached, and the parent is valid
        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, "pre_history_2026_11_time_brin")).containsEntry("valid", true)
                .doesNotContainEntry("oid", invalidOid);
        assertThat(attachedPartitions(jdbc)).containsExactly("pre_history_2026_11", "pre_history_default");
        assertThat(index(jdbc, PARENT)).containsEntry("valid", true);

        // and a second build finds nothing to do
        try (Connection connection = ds.getConnection()) {
            assertThat(V27__pre_history_time_brin.build(connection)).isZero();
        }
    }

    @Test
    void TR_OPS_01_a_long_partition_name_gets_a_short_distinct_index_name() {
        String november = "pre_history_" + "x".repeat(40) + "_2026_11";
        String december = "pre_history_" + "x".repeat(40) + "_2026_12";

        assertThat(V27__pre_history_time_brin.indexName("pre_history_2026_11")).isEqualTo("pre_history_2026_11_time_brin");
        assertThat(V27__pre_history_time_brin.indexName(november)).hasSizeLessThanOrEqualTo(63).endsWith("_time_brin")
                .isNotEqualTo(V27__pre_history_time_brin.indexName(december));
    }
}
