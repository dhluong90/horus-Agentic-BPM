package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V23 to V25 (#74): the plan tables with tenant-led keys and an append-only history, and the two dry-run indexes
 * built {@code CONCURRENTLY} outside a transaction on the hot tables {@code pre_token} and {@code pre_instance},
 * safe to run again after a build that failed and left an INVALID index (TR-OPS-01). Each test migrates a schema of
 * its own in the shared database.
 */
class MigrationPlanSchemaMigrationTest {

    private static final String TOKEN_INDEX = "idx_pre_token_live_node_instance";
    private static final String INSTANCE_INDEX = "idx_pre_instance_running_definition";

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
    void TR_OPS_01_the_dry_run_indexes_are_built_concurrently_outside_a_transaction() {
        DataSource ds = TestEngine.emptySchema("mig_v25_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "23").migrate();
        twoLoans(jdbc);

        int applied = flyway(ds, "25").migrate().migrationsExecuted;

        assertThat(applied).isEqualTo(2);
        assertThat(index(jdbc, TOKEN_INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, TOKEN_INDEX).get("def"))
                .contains("(tenant_id, definition_id, current_node_id, instance_id)").contains("WHERE (status = ANY");
        assertThat(index(jdbc, INSTANCE_INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INSTANCE_INDEX).get("def")).contains("(tenant_id, definition_id, id)")
                .contains("WHERE (status = 'RUNNING'");
        assertThat(jdbc.sql("SELECT count(*) FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE
                + " WHERE version IN ('23', '24', '25') AND success").query(Long.class).single()).isEqualTo(3);
    }

    @Test
    void TR_OPS_01_a_rerun_after_failed_builds_drops_the_invalid_indexes_and_builds_them_again() {
        DataSource ds = TestEngine.emptySchema("mig_v25_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "23").migrate();
        twoLoans(jdbc);
        // Earlier concurrent builds under both names failed: two rows of one tenant break a unique index.
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY " + TOKEN_INDEX + " ON pre_token (tenant_id)")
                .update()).hasMessageContaining("could not create unique index");
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY " + INSTANCE_INDEX
                + " ON pre_instance (tenant_id)").update()).hasMessageContaining("could not create unique index");
        assertThat(index(jdbc, TOKEN_INDEX)).containsEntry("valid", false);
        assertThat(index(jdbc, INSTANCE_INDEX)).containsEntry("valid", false);

        int applied = flyway(ds, "25").migrate().migrationsExecuted;

        assertThat(applied).isEqualTo(2);
        assertThat(index(jdbc, TOKEN_INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, TOKEN_INDEX).get("def")).doesNotContain("UNIQUE");
        assertThat(index(jdbc, INSTANCE_INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INSTANCE_INDEX).get("def")).doesNotContain("UNIQUE");
        assertThat(index(jdbc, TOKEN_INDEX + "_invalid")).isEmpty();
        assertThat(index(jdbc, INSTANCE_INDEX + "_invalid")).isEmpty();
    }

    @Test
    void PRE_175_plan_rows_are_tenant_keyed_and_the_history_is_append_only() {
        DataSource ds = TestEngine.emptySchema("mig_v23_plan");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "25").migrate();
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

    @Test
    void TR_OPS_01_V33_and_V34_add_the_layout_table_and_widen_the_plan_checks_without_touching_existing_plans() {
        // Given a database at V30 with a plan made by the last release and its history row
        DataSource ds = TestEngine.emptySchema("mig_v34_plan");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "30").migrate();
        twoLoans(jdbc);
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at, effective_from, approved_by, approved_at) VALUES ('bank-a', 'DEFMIG0000000000000000002', "
                + "'loan-review', 2, 'ACTIVE', 'Loan review', '{}', repeat('c', 64), 'BPMN_XML', '<definitions/>', "
                + "repeat('d', 64), 3, 'IMPORT', 'lan', now(), now(), 'minh', now())").update();
        jdbc.sql("INSERT INTO pre_migration (tenant_id, id, definition_key, source_definition_id, source_version, "
                + "target_definition_id, target_version, plan_hash, approval, status, created_by, created_at) VALUES "
                + "('bank-a', 'MIG0000000000000000000001', 'loan-review', 'DEFMIG0000000000000000001', 1, "
                + "'DEFMIG0000000000000000002', 2, repeat('e', 64), 'four-eyes', 'PLANNED', 'thu', now())").update();

        // When V33 and V34 run with the migrator's settings
        int applied = flyway(ds, "34").migrate().migrationsExecuted;

        // Then the plan is as it was, with empty execution columns, and the new checks hold
        assertThat(applied).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status || ':' || total_count || ':' || cursor_instance_id || ':' || "
                + "coalesce(lease_owner, 'none') || ':' || coalesce(approved_by, 'none') FROM pre_migration")
                .query(String.class).single()).isEqualTo("PLANNED:0::none:none");
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_migration SET status = 'RUNNING'").update())
                .hasMessageContaining("ck_pre_migration_approved");
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_migration SET status = 'DONE'").update())
                .hasMessageContaining("ck_pre_migration_status");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO pre_migration_history (tenant_id, id, migration_id, action, "
                + "actor_id, occurred_at) VALUES ('bank-a', 'MIGH000000000000000000009', 'MIG0000000000000000000001', "
                + "'EXECUTED', 'thu', now())").update()).hasMessageContaining("ck_pre_migration_history_action");
        jdbc.sql("INSERT INTO pre_migration_history (tenant_id, id, migration_id, action, actor_id, occurred_at) "
                + "VALUES ('bank-a', 'MIGH000000000000000000002', 'MIG0000000000000000000001', 'PROPOSED', 'thu', now())")
                .update();
        // and the layout table refuses to change or delete a revision
        jdbc.sql("INSERT INTO pre_definition_layout (tenant_id, definition_id, revision, layout_json, layout_hash, "
                + "definition_hash, source, created_by, created_at) VALUES ('bank-a', 'DEFMIG0000000000000000001', 1, "
                + "'{}', repeat('a', 64), repeat('b', 64), 'API', 'carol', now())").update();
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_definition_layout SET created_by = 'dave'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO pre_definition_layout (tenant_id, definition_id, revision, "
                + "layout_json, layout_hash, definition_hash, source, created_by, created_at) VALUES ('bank-b', "
                + "'DEFMIG0000000000000000001', 1, '{}', repeat('a', 64), repeat('b', 64), 'API', 'mallory', now())")
                .update()).hasMessageContaining("foreign key");
    }
}
