package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V35 (#31, TR-OPS-01): the index of the pending approvals list is built {@code CONCURRENTLY} outside a transaction on
 * a table that already holds definitions, is partial on the pending status, and a re-run after a failed build drops the
 * invalid index and builds it again. Each test migrates a schema of its own in the shared database.
 */
class PendingApprovalsMigrationTest {

    private static final String INDEX = "idx_pre_definition_pending_page";

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

    private static void definitionOfTheLastRelease(JdbcClient jdbc) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at, requested_by, requested_at) VALUES ('bank-a', 'DEF00000000000000000000001', "
                + "'loan-disbursement', 1, 'PENDING_APPROVAL', 'Loan', '{}', repeat('a', 64), 'BPMN_XML', "
                + "'<definitions/>', repeat('b', 64), 4, 'IMPORT', 'pipeline', now(), 'alice', now())").update();
    }

    @Test
    void TR_OPS_01_V35_builds_a_valid_partial_index_on_existing_definitions_concurrently() {
        // Given a database at V34 with a pending definition written by the last release
        DataSource ds = TestEngine.emptySchema("mig_v35_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "34").migrate();
        definitionOfTheLastRelease(jdbc);

        // When V35 runs with the migrator's settings
        int applied = new ProcessEngineSchemaMigrator(ds).migrate();

        // Then the index is valid, led by the tenant, ordered by request time and id, over pending versions only
        assertThat(applied).as("V35 and any migration after it").isGreaterThanOrEqualTo(1);
        assertThat(index(jdbc, INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INDEX).get("def")).contains("(tenant_id, requested_at, id)")
                .contains("PENDING_APPROVAL").doesNotContain("UNIQUE");
        assertThat(jdbc.sql("SELECT count(*) FROM pre_definition WHERE status = 'PENDING_APPROVAL'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void TR_OPS_01_a_rerun_of_V35_after_a_failed_build_drops_the_invalid_index_and_builds_it_again() {
        // Given V30, and an earlier concurrent build of the index that failed and left it INVALID
        DataSource ds = TestEngine.emptySchema("mig_v35_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "34").migrate();
        definitionOfTheLastRelease(jdbc);
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at) VALUES ('bank-a', 'DEF00000000000000000000002', 'loan-disbursement', 2, 'DRAFT', 'Loan', "
                + "'{}', repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 4, 'IMPORT', 'pipeline', now())")
                .update();
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY " + INDEX + " ON pre_definition "
                + "(tenant_id)").update()).hasMessageContaining("could not create unique index");
        assertThat(index(jdbc, INDEX)).containsEntry("valid", false);

        // When V35 runs
        int applied = flyway(ds, "35").migrate().migrationsExecuted;

        // Then the invalid index is gone and the real one is built and valid
        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INDEX).get("def")).doesNotContain("UNIQUE")
                .contains("(tenant_id, requested_at, id)");
        assertThat(index(jdbc, INDEX + "_invalid")).isEmpty();
    }
}
