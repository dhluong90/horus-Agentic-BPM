package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The migration plan tables (#74): tenant-led keys and an append-only history. Each test migrates a schema of its own
 * in the shared database.
 */
class MigrationPlanSchemaTest {

    /** One definition, two running loans of one tenant, each with a root token and a live token at one step. */
    private static void twoLoans(JdbcClient jdbc) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at, effective_from, approved_by, approved_at) VALUES ('bank-a', 'DEFMIG0000000000000000001', 'loan-review', 1, 'ACTIVE', 'Loan review', "
                + "'{}', repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 3, 'IMPORT', 'lan', now(), now(), 'minh', now())")
                .update();
        for (int i = 1; i <= 2; i++) {
            String instance = "INSTMIG00000000000000000" + i + "0";
            jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                    + "variables, started_at) VALUES ('bank-a', ?, 'DEFMIG0000000000000000001', 'loan-review', 1, "
                    + "'RUNNING', '{}', now())").param(instance).update();
            String scope = "TOKMIG000000000000000000" + i + "S";
            jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, current_node_id, "
                    + "status, entered_at, created_at) VALUES ('bank-a', ?, ?, 'DEFMIG0000000000000000001', 'SCOPE', "
                    + "'loan-review', 'WAITING_SCOPE', now(), now())").params(scope, instance).update();
            jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                    + "current_node_id, status, entered_at, created_at) VALUES ('bank-a', ?, ?, "
                    + "'DEFMIG0000000000000000001', 'FLOW', ?, 'legacy-review', 'WAITING_USER', now(), now())")
                    .params("TOKMIG000000000000000000" + i + "F", instance, scope).update();
        }
    }

    @Test
    void PRE_175_plan_rows_are_tenant_keyed_and_the_history_is_append_only() {
        DataSource ds = TestEngine.emptySchema("mig_v23_plan");
        JdbcClient jdbc = JdbcClient.create(ds);
        new ProcessEngineSchemaMigrator(ds).migrate();
        twoLoans(jdbc);
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at, effective_from, approved_by, approved_at) VALUES ('bank-a', 'DEFMIG0000000000000000002', 'loan-review', 2, 'ACTIVE', 'Loan review', "
                + "'{}', repeat('c', 64), 'BPMN_XML', '<definitions/>', repeat('d', 64), 3, 'IMPORT', 'lan', now(), now(), 'minh', now())")
                .update();
        String insert = "INSERT INTO pre_migration (tenant_id, id, definition_key, source_definition_id, source_version, "
                + "target_definition_id, target_version, plan_hash, approval, status, created_by, created_at) VALUES "
                + "(?, 'MIG0000000000000000000001', 'loan-review', 'DEFMIG0000000000000000001', 1, "
                + "'DEFMIG0000000000000000002', 2, repeat('e', 64), ?, 'PLANNED', 'thu', now())";

        // A plan cannot point at another tenant's definitions, nor record an approval other than the two modes.
        assertThatThrownBy(() -> jdbc.sql(insert).params("bank-b", "four-eyes").update())
                .hasMessageContaining("foreign key");
        assertThatThrownBy(() -> jdbc.sql(insert).params("bank-a", "none").update())
                .hasMessageContaining("check constraint");
        jdbc.sql(insert).params("bank-a", "four-eyes").update();
        jdbc.sql("INSERT INTO pre_migration_history (tenant_id, id, migration_id, action, actor_id, occurred_at) "
                + "VALUES ('bank-a', 'MIGH000000000000000000001', 'MIG0000000000000000000001', 'PLANNED', 'thu', now())")
                .update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_migration_history SET actor_id = 'someone-else'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM pre_migration_history").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE pre_migration_history").update())
                .hasMessageContaining("append-only");
    }
}
