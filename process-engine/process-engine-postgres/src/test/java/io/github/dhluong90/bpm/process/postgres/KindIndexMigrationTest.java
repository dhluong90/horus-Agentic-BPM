package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V9 builds the claim index of the runner per kind (#363) without blocking {@code pre_timer} (TR-OPS-01, DESIGN
 * section 11): {@code CREATE INDEX CONCURRENTLY} in a script that runs outside a transaction, safe to run again after
 * a build that failed and left an INVALID index. Each test migrates a schema of its own in the shared database.
 */
class KindIndexMigrationTest {

    private static final String INDEX = "idx_pre_timer_poll_kind";

    private static Flyway flyway(DataSource ds, String target) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE).locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION)
                .baselineOnMigrate(true).baselineVersion("0").target(target).load();
    }

    /** {@code indisvalid} and the definition of the index, or an empty map when there is none. */
    private static Map<String, Object> index(JdbcClient jdbc, String name) {
        return jdbc.sql("SELECT i.indisvalid AS valid, pg_get_indexdef(i.indexrelid) AS def FROM pg_index i "
                        + "WHERE i.indexrelid = to_regclass(?)").param(name).query().listOfRows().stream().findFirst()
                .orElse(Map.of());
    }

    /** Two open jobs of one kind: a unique index on {@code job_kind} cannot be built over them. */
    private static void twoDueJobsOfOneKind(JdbcClient jdbc) {
        for (String id : new String[] {"01JMIGRATION0000000000001", "01JMIGRATION0000000000002"}) {
            jdbc.sql("INSERT INTO pre_timer (tenant_id, id, job_kind, status, next_fire_at, lease_duration, created_at) "
                    + "VALUES ('bank-a', ?, 'DEFINITION_EFFECTIVE', 'DUE', now(), interval '30 seconds', now())")
                    .param(id).update();
        }
    }

    @Test
    void TR_OPS_01_the_kind_index_is_built_concurrently_outside_a_transaction() {
        DataSource ds = TestEngine.emptySchema("mig_v9_fresh");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "8").migrate();
        twoDueJobsOfOneKind(jdbc);

        int applied = new ProcessEngineSchemaMigrator(ds).migrate();

        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INDEX).get("def")).contains("(job_kind, next_fire_at, id)")
                .contains("WHERE (status = ANY");
        assertThat(jdbc.sql("SELECT success FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE + " WHERE version = '9'")
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void TR_OPS_01_a_rerun_after_a_failed_build_drops_the_invalid_index_and_builds_it_again() {
        // Given V8, and an earlier concurrent build of the index that failed and left it INVALID
        DataSource ds = TestEngine.emptySchema("mig_v9_rerun");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "8").migrate();
        twoDueJobsOfOneKind(jdbc);
        assertThatThrownBy(() -> jdbc.sql("CREATE UNIQUE INDEX CONCURRENTLY " + INDEX + " ON pre_timer (job_kind)")
                .update()).hasMessageContaining("could not create unique index");
        assertThat(index(jdbc, INDEX)).containsEntry("valid", false);

        // When V9 runs again
        int applied = new ProcessEngineSchemaMigrator(ds).migrate();

        // Then the invalid index is gone and the real one is built and valid
        assertThat(applied).isEqualTo(1);
        assertThat(index(jdbc, INDEX)).containsEntry("valid", true);
        assertThat((String) index(jdbc, INDEX).get("def")).doesNotContain("UNIQUE")
                .contains("(job_kind, next_fire_at, id)");
        assertThat(index(jdbc, INDEX + "_invalid")).isEmpty();
    }

    @Test
    void TR_OPS_01_a_valid_index_built_ahead_by_an_operator_is_kept() {
        DataSource ds = TestEngine.emptySchema("mig_v9_ahead");
        JdbcClient jdbc = JdbcClient.create(ds);
        flyway(ds, "8").migrate();
        jdbc.sql("CREATE INDEX CONCURRENTLY " + INDEX + " ON pre_timer (job_kind, next_fire_at, id) "
                + "WHERE status IN ('DUE','CLAIMED')").update();
        long oid = jdbc.sql("SELECT to_regclass(?)::oid").param(INDEX).query(Long.class).single();

        new ProcessEngineSchemaMigrator(ds).migrate();

        assertThat(jdbc.sql("SELECT to_regclass(?)::oid").param(INDEX).query(Long.class).single()).isEqualTo(oid);
        assertThat(index(jdbc, INDEX)).containsEntry("valid", true);
    }
}
