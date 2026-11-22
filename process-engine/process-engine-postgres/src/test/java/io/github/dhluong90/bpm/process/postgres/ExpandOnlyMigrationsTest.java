package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * PRE-218: every migration after the head of the previous release only expands the schema, so a replica of the
 * previous release keeps working while the next release migrates (TR-OPS-02). The few statements that are not plain
 * additions are listed below with the reason each is safe.
 */
class ExpandOnlyMigrationsTest {

    /** The highest migration the previous release (0.2.0) shipped. It moves to the new head when 0.3.0 ships. */
    static final int PREVIOUS_RELEASE_HEAD = 10;

    /**
     * Statements that drop a constraint to put a wider one in its place (a status or job kind gains a value): the
     * replacement is added and validated in the same migration, and the new value is written only once every replica
     * runs a release that reads it (reader first, then writer), so what an older replica reads stays valid. Key: {@code V<version>:<rule>}.
     */
    static final Map<String, String> ACCEPTED = Map.of(
            "V29:DROP_CONSTRAINT", "pre_incident status gains RESOLUTION_PENDING; the old release never writes it",
            "V34:DROP_CONSTRAINT", "pre_migration status and action checks gain values; the old release never writes them",
            "V42:DROP_CONSTRAINT", "pre_token status gains WAITING_CHILD and pre_timer job kind gains CALL_RESULT; "
                    + "call activities are not started until every replica runs the new release (release notes)");

    static final Path MIGRATIONS = Path.of("src/main/resources/io/github/dhluong90/bpm/process/postgres/migration");

    @Test
    void PRE_218_every_migration_after_the_previous_release_only_expands_the_schema() throws IOException {
        List<ExpandOnlyMigrations.Finding> findings = ExpandOnlyMigrations.scan(MIGRATIONS, PREVIOUS_RELEASE_HEAD);

        assertThat(findings.stream().filter(f -> !ACCEPTED.containsKey("V" + f.version() + ":" + f.rule()))
                .map(ExpandOnlyMigrations.Finding::toString).toList()).isEmpty();
        // An entry that no longer matches a finding is stale: remove it.
        assertThat(findings.stream().map(f -> "V" + f.version() + ":" + f.rule()).collect(Collectors.toSet()))
                .containsAll(ACCEPTED.keySet());
    }

    @Test
    void PRE_218_the_lint_names_each_kind_of_destructive_statement() {
        String sql = """
                -- the next release tidies up
                ALTER TABLE pre_task DROP COLUMN claimed_at;
                ALTER TABLE pre_task RENAME COLUMN name TO title;
                ALTER TABLE pre_task ALTER COLUMN name TYPE VARCHAR(10);
                ALTER TABLE pre_task ALTER COLUMN due_at SET NOT NULL;
                ALTER TABLE pre_task ADD COLUMN note TEXT NOT NULL;
                ALTER TABLE pre_task DROP CONSTRAINT ck_pre_task_status;
                DROP TABLE pre_history;
                DELETE FROM pre_idempotency;
                TRUNCATE pre_timer;
                """;

        List<String> rules = ExpandOnlyMigrations.scan("V99__tidy.sql", 99, sql).stream()
                .map(ExpandOnlyMigrations.Finding::rule).toList();

        assertThat(rules).containsExactlyInAnyOrder("DROP_COLUMN", "RENAME_TABLE_OR_COLUMN", "RETYPE_COLUMN",
                "SET_NOT_NULL", "ADD_NOT_NULL_COLUMN_WITHOUT_DEFAULT", "DROP_CONSTRAINT", "DROP_TABLE", "DELETE_ROWS",
                "TRUNCATE");
    }

    @Test
    void PRE_218_the_lint_accepts_the_expansions_a_release_may_make() {
        String sql = """
                ALTER TABLE pre_instance
                  ADD COLUMN call_depth INT NOT NULL DEFAULT 0 CHECK (call_depth >= 0),
                  ADD COLUMN parent_instance_id VARCHAR(26);
                CREATE TABLE pre_next (tenant_id TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (tenant_id, id));
                CREATE INDEX CONCURRENTLY idx_pre_next ON pre_next (tenant_id);
                ALTER INDEX idx_pre_next RENAME TO idx_pre_next_invalid;
                DROP INDEX CONCURRENTLY IF EXISTS idx_pre_next_invalid;
                UPDATE pre_instance SET call_depth = 0 WHERE call_depth IS NULL;
                """;

        assertThat(ExpandOnlyMigrations.scan("V99__expand.sql", 99, sql)).isEmpty();
    }
}
