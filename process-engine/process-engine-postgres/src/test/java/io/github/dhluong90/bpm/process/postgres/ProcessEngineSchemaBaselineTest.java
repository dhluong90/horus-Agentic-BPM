package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The schema the single V1 baseline leaves behind (#479): a database made by an earlier build is refused with a request
 * to reset, and the time index of {@code pre_history} is valid and inherited by partitions created later. Each test
 * migrates a schema of its own.
 */
class ProcessEngineSchemaBaselineTest {

    private static final String PARENT = "idx_pre_history_time_brin";

    @Test
    void a_database_made_by_a_build_before_the_baseline_is_refused_with_a_request_to_reset() {
        // Given a history written by an earlier build: the recorded V1 migration has another checksum
        DataSource ds = TestEngine.emptySchema("baseline_refused");
        JdbcClient jdbc = JdbcClient.create(ds);
        new ProcessEngineSchemaMigrator(ds).migrate();
        jdbc.sql("UPDATE " + ProcessEngineSchemaMigrator.HISTORY_TABLE + " SET checksum = 1 WHERE version = '1'").update();
        long rows = jdbc.sql("SELECT count(*) FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE)
                .query(Long.class).single();

        // When the engine starts, Then it stops, says to reset, and changes nothing
        assertThatThrownBy(() -> new ProcessEngineSchemaMigrator(ds).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("drop the schema and start again");
        assertThat(jdbc.sql("SELECT count(*) FROM " + ProcessEngineSchemaMigrator.HISTORY_TABLE)
                .query(Long.class).single()).isEqualTo(rows);
    }

    @Test
    void a_clean_database_is_migrated_once_and_a_second_run_applies_nothing() {
        DataSource ds = TestEngine.emptySchema("baseline_clean");
        ProcessEngineSchemaMigrator migrator = new ProcessEngineSchemaMigrator(ds);

        assertThat(migrator.migrate()).isEqualTo(3);
        assertThat(migrator.migrate()).isZero();
        assertThat(migrator.currentVersion()).isEqualTo("3");
    }

    @Test
    void the_history_time_index_is_valid_on_the_default_partition_and_inherited_by_new_partitions() {
        DataSource ds = TestEngine.emptySchema("baseline_brin");
        JdbcClient jdbc = JdbcClient.create(ds);
        new ProcessEngineSchemaMigrator(ds).migrate();
        assertThat(attachedPartitions(jdbc)).containsExactly("pre_history_default");
        assertThat(jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(?)").param(PARENT)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT pg_get_indexdef(to_regclass(?))").param(PARENT).query(String.class).single())
                .contains("USING brin (occurred_at)");

        // When the host adds a monthly partition, by PARTITION OF and by ATTACH PARTITION
        jdbc.sql("CREATE TABLE pre_history_2026_11 PARTITION OF pre_history "
                + "FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00')").update();
        jdbc.sql("CREATE TABLE pre_history_2026_12 (LIKE pre_history INCLUDING DEFAULTS INCLUDING CONSTRAINTS)").update();
        jdbc.sql("ALTER TABLE pre_history ATTACH PARTITION pre_history_2026_12 "
                + "FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00')").update();

        // Then each carries its own valid BRIN attached to the parent index
        assertThat(attachedPartitions(jdbc))
                .containsExactly("pre_history_2026_11", "pre_history_2026_12", "pre_history_default");
    }

    private static List<String> attachedPartitions(JdbcClient jdbc) {
        return jdbc.sql("SELECT t.relname FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid "
                        + "JOIN pg_class t ON t.oid = x.indrelid WHERE i.inhparent = to_regclass(?) AND x.indisvalid "
                        + "ORDER BY t.relname").param(PARENT).query(String.class).list();
    }
}
