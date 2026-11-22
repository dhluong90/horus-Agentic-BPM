package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V45 (#438, TR-OPS-01): the index of the start-up summary of unclassified fields is built {@code CONCURRENTLY}
 * outside a transaction on a table that already holds definitions, partial on the active status, and the active
 * versions read through it come back in tenant, key and version order.
 */
class ActiveDefinitionsIndexMigrationTest {

    private static final String INDEX = "idx_pre_definition_active";

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE).locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    private static void definition(JdbcClient jdbc, String tenant, String id, String key, int version, String status) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, created_by, "
                + "created_at, approved_by, approved_at, effective_from) VALUES (?, ?, ?, ?, ?, 'Loan', '{}', "
                + "repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 4, 'IMPORT', 'pipeline', now(), "
                + "'bob', now(), now())").params(tenant, id, key, version, status).update();
    }

    @Test
    void TR_OPS_01_V45_builds_a_valid_partial_index_on_existing_definitions_concurrently() {
        // Given a database at V35 with an active, a retired and a draft version written by the last release
        DataSource ds = TestEngine.emptySchema("mig_v45_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "35").migrate();
        definition(jdbc, "bank-b", "DEF00000000000000000000003", "loan-disbursement", 1, "ACTIVE");
        definition(jdbc, "bank-a", "DEF00000000000000000000001", "loan-disbursement", 1, "RETIRED");
        definition(jdbc, "bank-a", "DEF00000000000000000000002", "loan-disbursement", 2, "ACTIVE");
        definition(jdbc, "bank-a", "DEF00000000000000000000004", "loan-disbursement", 3, "DRAFT");

        // When V45 runs with the migrator's settings
        int applied = flyway(ds, "45").migrate().migrationsExecuted;

        // Then the index is valid, led by the tenant, over active versions only
        assertThat(applied).isEqualTo(1);
        Map<String, Object> index = jdbc.sql("SELECT i.indisvalid AS valid, pg_get_indexdef(i.indexrelid) AS def "
                + "FROM pg_index i WHERE i.indexrelid = to_regclass(?)").param(INDEX).query().singleRow();
        assertThat(index).containsEntry("valid", true);
        assertThat((String) index.get("def")).contains("(tenant_id, key_name, version_num)").contains("ACTIVE");

        // And the start-up read returns the two active versions, by tenant, key and version
        assertThat(new JdbcProcessStore(ds).activeDefinitions(10)).extracting(d -> d.tenantId() + "/" + d.version())
                .containsExactly("bank-a/2", "bank-b/1");
    }
}
