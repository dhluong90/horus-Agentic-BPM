package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V29 and V30 (#68, TR-OPS-01): the proposal state of an incident is added to a table that already holds incidents,
 * and the two list indexes are built {@code CONCURRENTLY} outside a transaction, safe to run again after a failed
 * build. Each test migrates a schema of its own in the shared database.
 */
class IncidentMigrationTest {

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

    /** One instance with an open and a resolved incident, as 0.2.0 writes them. */
    private static void incidentsOfTheLastRelease(JdbcClient jdbc) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at) VALUES ('bank-a', 'DEF00000000000000000000001', 'loan-disbursement', 1, 'DRAFT', 'Loan', "
                + "'{}', repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 4, 'IMPORT', 'pipeline', now())")
                .update();
        jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                + "variables, started_at) VALUES ('bank-a', 'INS00000000000000000000001', 'DEF00000000000000000000001', "
                + "'loan-disbursement', 1, 'RUNNING', '{}', now())").update();
        for (String[] row : new String[][] {{"INC00000000000000000000001", "OPEN"},
                {"INC00000000000000000000002", "RESOLVED"}}) {
            jdbc.sql("INSERT INTO pre_incident (tenant_id, id, instance_id, definition_id, node_id, error_code, "
                    + "cause_summary, status, created_at) VALUES ('bank-a', ?, 'INS00000000000000000000001', "
                    + "'DEF00000000000000000000001', 'check-limit', 'UNHANDLED_BUSINESS_ERROR', 'LIMIT_EXCEEDED', ?, "
                    + "now())").params(row[0], row[1]).update();
        }
    }

    @Test
    void TR_OPS_01_V29_adds_the_proposal_state_to_existing_incidents_and_V30_builds_both_indexes_concurrently() {
        // Given a database at V18 with incidents written by the last release
        DataSource ds = TestEngine.emptySchema("mig_v29_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "18").migrate();
        incidentsOfTheLastRelease(jdbc);

        // When V29 and V30 run with the migrator's settings
        int applied = new ProcessEngineSchemaMigrator(ds).migrate();

        // Then the rows keep their state, have no resolution and no retry in flight, and both indexes are valid
        assertThat(applied).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status || ' ' || coalesce(resolution, '-') || ' ' || retrying FROM pre_incident "
                + "ORDER BY id").query(String.class).list()).containsExactly("OPEN - false", "RESOLVED - false");
        assertThat(index(jdbc, "idx_pre_incident_status")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_incident_unresolved").get("def"))
                .contains("(tenant_id, instance_id, created_at, id)").contains("WHERE (status = ANY");
        // And the new state is accepted only with its proposer, time and reason
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_incident SET status = 'RESOLUTION_PENDING' WHERE id = "
                + "'INC00000000000000000000001'").update()).hasMessageContaining("ck_pre_incident_proposal");
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_incident SET status = 'CLOSED' WHERE id = "
                + "'INC00000000000000000000001'").update()).hasMessageContaining("ck_pre_incident_status");
        assertThat(jdbc.sql("UPDATE pre_incident SET status = 'RESOLUTION_PENDING', proposed_by = 'ops.lan', "
                + "proposed_at = now(), proposal_reason = 'Limit raised', approval_mode = 'four-eyes' WHERE id = "
                + "'INC00000000000000000000001'").update()).isEqualTo(1);
    }

    @Test
    void TR_OPS_01_a_rerun_of_V30_after_a_failed_build_drops_the_invalid_index_and_builds_it_again() {
        // Given V29, and an earlier concurrent build of the list index that failed and left it INVALID
        DataSource ds = TestEngine.emptySchema("mig_v30_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "29").migrate();
        incidentsOfTheLastRelease(jdbc);
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY idx_pre_incident_status ON pre_incident "
                + "(tenant_id)").update()).hasMessageContaining("could not create unique index");
        assertThat(index(jdbc, "idx_pre_incident_status")).containsEntry("valid", false);

        // When V30 runs
        int applied = flyway(ds, "30").migrate().migrationsExecuted;

        // Then the invalid index is gone and the real one is built and valid
        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, "idx_pre_incident_status")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_incident_status").get("def")).doesNotContain("UNIQUE")
                .contains("(tenant_id, status, created_at DESC, id DESC)");
        assertThat(index(jdbc, "idx_pre_incident_status_invalid")).isEmpty();
    }
}
