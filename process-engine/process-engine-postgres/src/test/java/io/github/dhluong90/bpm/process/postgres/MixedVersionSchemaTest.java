package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rolling upgrade on one schema (PRE-218, TR-OPS-02): a replica of this release runs against a schema a replica of the
 * next release has already migrated. The next release is simulated by {@code V9999__next_release_expand.sql}: a new
 * nullable column, a defaulted column, a new table and a new job kind, all expansions. Loan {@code LN-2027-0042} is
 * started before the upgrade and finished after it; the replica is "old" because it knows none of the additions.
 */
class MixedVersionSchemaTest {

    private static final String NEXT_RELEASE = "classpath:mixed-version/pe-next";
    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work",
            "processes:deploy"};

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");

    /** The schema of this release plus the migration of the next one, as the next release's replica leaves it. */
    private static Flyway nextRelease(DataSource ds) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE)
                .locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION, NEXT_RELEASE)
                .baselineOnMigrate(true).baselineVersion("0").load();
    }

    /** This build's migrations with Flyway's validation set to refuse a migration it does not contain. */
    private static Flyway thisReleaseStrict(DataSource ds) {
        return Flyway.configure(ProcessEngineSchemaMigrator.class.getClassLoader())
                .configuration(ProcessEngineSchemaMigrator.LOCKING).dataSource(ds)
                .table(ProcessEngineSchemaMigrator.HISTORY_TABLE)
                .locations(ProcessEngineSchemaMigrator.MIGRATION_LOCATION)
                .ignoreMigrationPatterns("*:ignored")
                .baselineOnMigrate(true).baselineVersion("0").load();
    }

    private static ProcessInstance.Task open(ProcessInstance instance, String nodeId) {
        return instance.tasks().stream().filter(t -> t.nodeId().equals(nodeId) && !"COMPLETED".equals(t.status()))
                .findFirst().orElseThrow();
    }

    @Test
    void PRE_218_a_replica_of_this_release_starts_and_finishes_a_loan_on_a_schema_the_next_release_migrated() {
        DataSource ds = TestEngine.emptySchema("mixed_pe_run");
        JdbcClient jdbc = JdbcClient.create(ds);
        assertThat(new ProcessEngineSchemaMigrator(ds).migrate()).isPositive();
        TestEngine before = TestEngine.replica("pod-1", clock).dataSource(ds).build();
        before.identity.as("bank-a", "pipeline", OFFICER).roles = Set.of("LOAN_OFFICER");
        before.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-approval.bpmn"), null));
        before.identity.as("bank-a", "alice", OFFICER).roles = Set.of("LOAN_OFFICER");
        ProcessInstance started = before.services.start(new StartRequest("loan-approval", "LN-2027-0042",
                Map.of("amount", Map.of("amount", "250000000", "currency", "VND")), null));
        before.close();

        // The next release's replica migrates; this release's replica restarts against the newer schema.
        assertThat(nextRelease(ds).migrate().migrationsExecuted).isEqualTo(1);
        ProcessEngineSchemaMigrator migrator = new ProcessEngineSchemaMigrator(ds);
        assertThat(migrator.migrate()).isZero();
        assertThat(migrator.aheadMigrations()).isEqualTo(1);
        TestEngine old = TestEngine.replica("pod-2", clock).dataSource(ds).build();
        try {
            old.identity.as("bank-a", "bob", OFFICER).roles = Set.of("LOAN_OFFICER");
            ProcessInstance.Task submit = open(old.services.instance(started.id()), "submit-application");
            long claimed = old.services.claim(submit.id(), submit.lockVersion(), "bob-claim").lockVersion();
            ProcessInstance after = old.services.complete(submit.id(), Map.of("complete", true), claimed, "bob-submit");

            assertThat(after.tasks()).extracting(ProcessInstance.Task::nodeId).contains("approve-credit");
            // Rows the old replica wrote take the new columns' defaults; nothing else changed.
            assertThat(jdbc.sql("SELECT next_release_flag FROM pre_instance WHERE id = ?").param(started.id())
                    .query(Boolean.class).single()).isFalse();
            assertThat(jdbc.sql("SELECT count(*) FROM pre_task WHERE next_release_note IS NOT NULL")
                    .query(Integer.class).single()).isZero();
        } finally {
            old.close();
        }
    }

    @Test
    void PRE_218_the_migrator_ignores_applied_migrations_its_build_does_not_contain_and_counts_them() {
        DataSource ds = TestEngine.emptySchema("mixed_pe_strict");
        new ProcessEngineSchemaMigrator(ds).migrate();
        nextRelease(ds).migrate();

        // The control: validation that does not ignore future migrations refuses a build that lacks an applied one.
        assertThatThrownBy(() -> thisReleaseStrict(ds).validate()).isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("applied migration not resolved locally");
        // The migrator ignores a migration that is ahead of the build, and says how many.
        ProcessEngineSchemaMigrator migrator = new ProcessEngineSchemaMigrator(ds);
        assertThat(migrator.migrate()).isZero();
        assertThat(migrator.aheadMigrations()).isEqualTo(1);
    }

    @Test
    void PRE_218_a_schema_that_is_not_ahead_reports_none() {
        DataSource ds = TestEngine.emptySchema("mixed_pe_current");
        ProcessEngineSchemaMigrator migrator = new ProcessEngineSchemaMigrator(ds);
        migrator.migrate();

        assertThat(migrator.aheadMigrations()).isZero();
        assertThat(migrator.migrate()).isZero();
    }

    @Test
    void PRE_218_a_job_of_a_kind_only_the_next_release_knows_is_handed_back_not_failed() {
        DataSource ds = TestEngine.emptySchema("mixed_pe_kind");
        JdbcClient jdbc = JdbcClient.create(ds);
        new ProcessEngineSchemaMigrator(ds).migrate();
        nextRelease(ds).migrate();
        TestEngine old = TestEngine.replica("pod-2", clock).dataSource(ds).build();
        try {
            old.identity.as("bank-a", "pipeline", OFFICER);
            old.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    TestEngine.resource("/bpmn/loan-approval.bpmn"), null));
            old.identity.as("bank-a", "alice", OFFICER);
            ProcessInstance started = old.services.start(new StartRequest("loan-approval", "LN-2027-0043", Map.of(),
                    null));
            String token = started.tokens().get(0).id();
            jdbc.sql("INSERT INTO pre_timer (tenant_id, id, instance_id, token_id, job_kind, status, next_fire_at, "
                    + "lease_duration, created_at) VALUES ('bank-a', '01JNEXTRELEASEJOB00000001', ?, ?, "
                    + "'NEXT_RELEASE_KIND', 'DUE', ?, interval '30 seconds', ?)")
                    .params(started.id(), token, java.sql.Timestamp.from(clock.instant()),
                            java.sql.Timestamp.from(clock.instant())).update();

            assertThat(old.jobs.pollOnce()).isZero();
            Instant first = nextFire(jdbc);
            clock.advance(Duration.ofMinutes(5));
            assertThat(old.jobs.pollOnce()).isZero();

            // Still due, never failed, and no attempt used: the next release's replica claims it as if untouched.
            assertThat(jdbc.sql("SELECT status FROM pre_timer WHERE id = '01JNEXTRELEASEJOB00000001'")
                    .query(String.class).single()).isEqualTo("DUE");
            assertThat(jdbc.sql("SELECT attempt FROM pre_timer WHERE id = '01JNEXTRELEASEJOB00000001'")
                    .query(Integer.class).single()).isZero();
            assertThat(first).isAfter(Instant.parse("2027-03-01T02:00:00Z"));
            assertThat(nextFire(jdbc)).isAfter(first);
        } finally {
            old.close();
        }
    }

    private static Instant nextFire(JdbcClient jdbc) {
        return jdbc.sql("SELECT next_fire_at FROM pre_timer WHERE id = '01JNEXTRELEASEJOB00000001'")
                .query((rs, row) -> rs.getTimestamp(1).toInstant()).single();
    }
}
