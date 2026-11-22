package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V32 adds the escalation of a task (PRE-178) and the calendar change list index (PRE-181) to a database that already
 * holds tasks, without touching them and without blocking {@code pre_task} (TR-OPS-01): the columns are metadata-only,
 * the indexes are built {@code CONCURRENTLY} outside a transaction. Each test migrates a schema of its own.
 */
class TaskEscalationMigrationTest {

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
    void PRE_178_V32_keeps_existing_tasks_and_builds_its_indexes_concurrently() {
        // Given a database at V31 with an open task of bank-a that has no escalation
        DataSource ds = TestEngine.emptySchema("mig_v32_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "31").migrate();
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                + "created_by, created_at, lock_version) VALUES ('bank-a', 'D1', 'k', 1, 'DRAFT', 'k', '{}'::jsonb, "
                + "'h', 'BPMN_XML', 'x', 'h', 1, 'IMPORT', NULL, 'u', now(), 0)").update();
        jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                + "variables, seq, lock_version, started_by, started_at) VALUES ('bank-a', 'I1', 'D1', 'k', 1, "
                + "'RUNNING', '{}'::jsonb, 0, 0, 'u', now())").update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, current_node_id, "
                + "status, entered_at, created_at) VALUES ('bank-a', 'S1', 'I1', 'D1', 'SCOPE', 'scope', 'WAITING_SCOPE', "
                + "now(), now())").update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                + "current_node_id, status, entered_at, created_at) VALUES ('bank-a', 'K1', 'I1', 'D1', 'FLOW', 'S1', "
                + "'review', 'WAITING_USER', now(), now())").update();
        jdbc.sql("INSERT INTO pre_task (tenant_id, id, instance_id, token_id, definition_id, node_id, name, "
                + "assignee_role, four_eyes, status, lock_version, created_at) VALUES ('bank-a', 'T1', 'I1', 'K1', "
                + "'D1', 'review', 'Review', 'credit-officer', true, 'OPEN', 0, now())").update();

        // When V32 runs with the migrator's settings
        int applied = flyway(ds, "32").migrate().migrationsExecuted;

        // Then it ran once, the task is as it was, and both indexes are valid
        assertThat(applied).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status || ':' || assignee_role || ':' || coalesce(escalation_level::text, '-') "
                + "|| ':' || coalesce(next_escalation_at::text, '-') FROM pre_task WHERE id = 'T1'")
                .query(String.class).single()).isEqualTo("OPEN:credit-officer:-:-");
        assertThat(index(jdbc, "idx_pre_task_escalation_due")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_task_escalation_due").get("def"))
                .contains("(next_escalation_at, tenant_id, id)").contains("next_escalation_at IS NOT NULL");
        assertThat(index(jdbc, "idx_pre_calendar_change_status")).containsEntry("valid", true);
        assertThat((String) index(jdbc, "idx_pre_calendar_change_status").get("def"))
                .contains("(tenant_id, status, proposed_at DESC, id DESC)");
        assertThat(jdbc.sql("SELECT success FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE
                + " WHERE version = '32'").query(Boolean.class).single()).isTrue();
    }
}
