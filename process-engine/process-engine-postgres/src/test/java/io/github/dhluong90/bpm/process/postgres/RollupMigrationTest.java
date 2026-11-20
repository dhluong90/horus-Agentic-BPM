package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V26 to V28 (#110, #86): the statistics tables, and the two indexes on hot tables, built CONCURRENTLY outside a
 * transaction and safe to run again after a failed build (TR-OPS-01). Each test migrates a schema of its own.
 */
class RollupMigrationTest {

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE).locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    private static Map<String, Object> index(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT i.indisvalid AS valid, pg_get_indexdef(i.indexrelid) AS def FROM pg_index i "
                        + "WHERE i.indexrelid = to_regclass(?)").param(name).query().listOfRows().stream().findFirst()
                .orElse(Map.of());
    }

    @Test
    void TR_OPS_01_v26_to_v28_apply_on_a_database_with_data_and_leave_valid_indexes() {
        // Given a database at V17 with a RUNNING instance's history already written
        DataSource ds = TestEngine.emptySchema("mig_v26_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "17").migrate();
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                + "details) VALUES ('bank-a', now(), 'H0000000000000000000000001', 'I0000000000000000000000001', "
                + "'D0000000000000000000000001', 'NODE_COMPLETED', 'approve', '{}'::jsonb)").update();

        // When V26, V27 and V28 run
        int applied = flyway(ds, "28").migrate().migrationsExecuted;

        // Then three migrations ran, the tables exist, and both concurrent indexes are valid
        assertThat(applied).isEqualTo(3);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_engine_watermark").query(Long.class).single()).isZero();
        assertThat(index(jdbc, "idx_pre_instance_running")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_instance_running").get("def")).contains("(tenant_id)")
                .contains("status = 'RUNNING'");
        assertThat(index(jdbc, "idx_pre_history_time_brin_default")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_history_time_brin_default").get("def")).contains("USING brin (occurred_at)");
        // and the parent index is valid and has the default partition's index attached
        assertThat(index(jdbc, "idx_pre_history_time_brin")).containsEntry("valid", true);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_inherits WHERE inhparent = to_regclass('idx_pre_history_time_brin')")
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT success FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE
                + " WHERE version IN ('26', '27', '28') ORDER BY installed_rank").query(Boolean.class).list())
                .containsExactly(true, true, true);
    }

    @Test
    void TR_OPS_01_a_rerun_after_a_failed_build_replaces_the_invalid_index() {
        // Given V27 only, and an interrupted build of the running-instance index that left it INVALID
        DataSource ds = TestEngine.emptySchema("mig_v28_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "27").migrate();
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                + "created_by, created_at, approved_by, approved_at) VALUES ('bank-a', 'D0000000000000000000000001', "
                + "'loan', 1, 'ACTIVE', 'Loan', '{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<d/>', repeat('b', 64), 1, "
                + "'IMPORT', now(), 'lan', now(), 'minh', now())").update();
        for (String id : List.of("I0000000000000000000000001", "I0000000000000000000000002")) {
            jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                    + "variables, started_at) VALUES ('bank-a', ?, 'D0000000000000000000000001', 'loan', 1, 'RUNNING', "
                    + "'{}'::jsonb, now())").param(id).update();
        }
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY idx_pre_instance_running ON pre_instance "
                + "(tenant_id) WHERE status = 'RUNNING'").update()).hasMessageContaining("could not create unique index");
        assertThat(index(jdbc, "idx_pre_instance_running")).containsEntry("valid", false);

        // When V28 runs
        int applied = flyway(ds, "28").migrate().migrationsExecuted;

        // Then the invalid index is gone and the real, non-unique one is built and valid
        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, "idx_pre_instance_running")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_instance_running").get("def")).doesNotContain("UNIQUE");
        assertThat(index(jdbc, "idx_pre_instance_running_invalid")).isEmpty();
    }
}
