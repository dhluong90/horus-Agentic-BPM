package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.process.api.DryRunRequiredException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationNotFoundException;
import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.internal.service.MigrationExecutions;
import io.github.dhluong90.bpm.process.internal.service.MigrationRunner;
import io.github.dhluong90.bpm.process.internal.service.MigrationSettings;
import io.github.dhluong90.bpm.process.internal.service.Migrations;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Executing a migration plan on PostgreSQL (#90, PRE-015, PRE-190 to PRE-193). Loan review v1 holds each loan at
 * {@code review}; v2 keeps that step and adds an {@code audit} step after it, so every waiting loan can move. A plan is
 * proposed by one operator and approved by another; the runner then moves the loans in batches, and the tests stop it,
 * lock a loan, or take the plan from it, as a crash or a second replica would.
 */
class MigrationExecutionPostgresIT {

    private static final String KEY = "loan-review";
    private static final String[] PIPELINE = {"processes:deploy", "processes:start", "processes:read"};
    private static final String MANAGE = "processes:migration:manage";
    private static final MigrationSettings SETTINGS = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100,
            Duration.ofHours(1), 100, Duration.ofMinutes(2));

    private static final String V1 = """
            <bpmn:startEvent id="start" name="Loan received"/>
            <bpmn:userTask id="review" name="Officer review" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="review"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__review" sourceRef="start" targetRef="review"/>
            <bpmn:sequenceFlow id="review__end" sourceRef="review" targetRef="end"/>
            """;
    private static final String V2 = """
            <bpmn:startEvent id="start" name="Loan received"/>
            <bpmn:userTask id="review" name="Officer review" horus:assignee="role:LOAN_OFFICER" horus:resultVariable="review"/>
            <bpmn:userTask id="audit" name="Audit sample" horus:assignee="role:AUDITOR" horus:resultVariable="audit"/>
            <bpmn:endEvent id="end" name="Decided"/>
            <bpmn:sequenceFlow id="start__review" sourceRef="start" targetRef="review"/>
            <bpmn:sequenceFlow id="review__audit" sourceRef="review" targetRef="audit"/>
            <bpmn:sequenceFlow id="audit__end" sourceRef="audit" targetRef="end"/>
            """;

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;
    private JdbcClient jdbc;
    private MigrationExecutions executions;
    private Migrations plans;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        jdbc = TestEngine.jdbc();
        executions = engine.migrationExecutions(SETTINGS);
        plans = engine.migrations(SETTINGS);
    }

    private static byte[] bpmn(String process) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                                  id="Definitions_loan-review" targetNamespace="urn:horus-bpm:examples"
                                  expressionLanguage="urn:horus-bpm:expr:v1">
                  <bpmn:process id="loan-review" name="Loan review" isExecutable="true">
                %s
                  </bpmn:process>
                </bpmn:definitions>
                """.formatted(process).getBytes(StandardCharsets.UTF_8);
    }

    private void importAs(String tenant, String process) {
        engine.identity.as(tenant, "pipeline", PIPELINE);
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn(process), null));
    }

    /** Starts {@code count} loans of 300 million VND on the effective version. */
    private void startLoans(String tenant, int count) {
        engine.identity.as(tenant, "officer-lan", PIPELINE);
        for (int i = 0; i < count; i++) {
            engine.services.start(new StartRequest(KEY, tenant + "-LN-2027-" + String.format("%06d", i),
                    Map.of("amount", Money.ofMinor("VND", 300_000_000L)), null));
        }
    }

    private void as(String tenant, String caller, String... permissions) {
        engine.identity.as(tenant, caller, permissions);
    }

    /** bank-a with {@code loans} loans waiting at review on v1, v2 active, and a plan v1 to v2 made by thu. */
    private MigrationPlan seed(String tenant, int loans) {
        importAs(tenant, V1);
        startLoans(tenant, loans);
        importAs(tenant, V2);
        as(tenant, "thu", MANAGE, "processes:read");
        return plans.plan(new MigrationPlanRequest(KEY, 1, 2, null));
    }

    private MigrationExecution propose(String tenant, MigrationPlan plan) {
        as(tenant, "thu", MANAGE, "processes:read");
        return executions.propose(plan.id(), "Add the audit sample after officer review, change CHG-2041", null);
    }

    private MigrationExecution approve(String tenant, MigrationPlan plan, long lock) {
        as(tenant, "lan", MANAGE, "processes:read");
        return executions.approve(plan.id(), lock, null);
    }

    private MigrationExecution approved(String tenant, MigrationPlan plan) {
        return approve(tenant, plan, propose(tenant, plan).lockVersion());
    }

    private MigrationRunner runner(String owner) {
        return engine.migrationRunner(SETTINGS, owner, MigrationRunner.Checkpoint.NONE);
    }

    private List<String> loanIds(String tenant) {
        return jdbc.sql("SELECT id FROM pre_instance WHERE tenant_id = ? ORDER BY id").param(tenant)
                .query(String.class).list();
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private long movedTo(String tenant, int version) {
        return count("SELECT count(*) FROM pre_instance WHERE tenant_id = ? AND definition_ver = ?", tenant, version);
    }

    private MigrationExecution progress(String tenant, MigrationPlan plan) {
        as(tenant, "lan", MANAGE, "processes:read");
        return executions.execution(plan.id());
    }

    private String planRow(String id) {
        return jdbc.sql("SELECT status || ':' || lock_version || ':' || migrated_count || ':' || failed_count "
                + "FROM pre_migration WHERE id = ?").param(id).query(String.class).single();
    }

    @Test
    void PRE_015_migration_execute_isolates_instance_failure() {
        // Given 250 loans waiting at review on v1, an approved plan v1 to v2, and loan #120 whose token has moved to a
        // step neither version has since the dry run (a mapping invariant error)
        MigrationPlan plan = seed("bank-a", 250);
        List<String> loans = loanIds("bank-a");
        String broken = loans.get(119);
        MigrationExecution proposal = propose("bank-a", plan);
        assertThat(proposal.status()).as("a proposal moves nothing").isEqualTo("PROPOSED");
        assertThat(runner("pod-1").runDue(10)).as("an unapproved plan is never run").isZero();
        assertThat(movedTo("bank-a", 2)).isZero();
        approve("bank-a", plan, proposal.lockVersion());
        jdbc.sql("UPDATE pre_token SET current_node_id = 'legacy-review' WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND token_kind = 'FLOW'").param(broken).update();
        int events = engine.events.size();

        // When the runner executes the plan
        int dealt = runner("pod-1").runDue(10);

        // Then 249 loans pin to v2 with MIGRATED history rows, loan #120 gets an incident and stays on v1
        assertThat(dealt).isEqualTo(250);
        assertThat(movedTo("bank-a", 2)).isEqualTo(249);
        assertThat(count("SELECT count(*) FROM pre_instance WHERE tenant_id = 'bank-a' AND id = ? AND definition_ver = 1",
                broken)).isEqualTo(1);
        assertThat(count("SELECT count(DISTINCT instance_id) FROM pre_history WHERE tenant_id = 'bank-a' "
                + "AND event_type = 'MIGRATED' AND actor_id = 'lan'")).isEqualTo(249);
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND event_type = 'MIGRATED' "
                + "AND instance_id = ?", broken)).isZero();
        assertThat(jdbc.sql("SELECT details->>'proposedBy' || '/' || (details->>'toVersion') || '/' || (details->>'reason') "
                + "FROM pre_history WHERE event_type = 'MIGRATED' LIMIT 1").query(String.class).single())
                .isEqualTo("thu/2/Add the audit sample after officer review, change CHG-2041");
        assertThat(jdbc.sql("SELECT error_code || ':' || node_id || ':' || status FROM pre_incident WHERE instance_id = ?")
                .param(broken).query(String.class).single()).isEqualTo("MIGRATION_FAILED:legacy-review:OPEN");
        assertThat(count("SELECT count(*) FROM pre_incident WHERE tenant_id = 'bank-a'")).isEqualTo(1);
        // and everything that carries the definition of a moved loan moved with it, the broken loan's did not
        String v2 = jdbc.sql("SELECT id FROM pre_definition WHERE tenant_id = 'bank-a' AND version_num = 2")
                .query(String.class).single();
        assertThat(count("SELECT count(*) FROM pre_token WHERE tenant_id = 'bank-a' AND definition_id = ?", v2))
                .isEqualTo(249 * 2);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND definition_id = ?", v2))
                .isEqualTo(249);
        assertThat(count("SELECT count(*) FROM pre_task WHERE tenant_id = 'bank-a' AND definition_id <> ?", v2))
                .isEqualTo(1);
        // and the plan reports it, with one outcome row per loan and the failure named
        MigrationExecution done = progress("bank-a", plan);
        assertThat(done.status()).isEqualTo("COMPLETED_WITH_FAILURES");
        assertThat(List.of(done.total(), done.migrated(), done.failed(), done.skipped())).containsExactly(250L, 249L, 1L, 0L);
        assertThat(done.startedAt()).isEqualTo(clock.instant());
        assertThat(done.finishedAt()).isEqualTo(clock.instant());
        List<MigrationOutcome> failed = executions.outcomes(plan.id(), "FAILED", null, 50);
        assertThat(failed).singleElement().satisfies(o -> {
            assertThat(o.instanceId()).isEqualTo(broken);
            assertThat(o.code()).isEqualTo("STEP_NOT_IN_TARGET");
            assertThat(o.incidentId()).isNotNull();
        });
        assertThat(executions.outcomes(plan.id(), null, null, 200)).hasSize(200);
        assertThat(executions.outcomes(plan.id(), "MIGRATED", loans.get(199), 200)).hasSize(50);
        assertThat(engine.events.subList(events, engine.events.size())).extracting(e -> e.type())
                .contains("processes.incident.raised.v1", "processes.migration.completed.v1");
        // and a repeat of the run moves nothing and writes nothing
        assertThat(runner("pod-2").runDue(10)).isZero();
        assertThat(movedTo("bank-a", 2)).isEqualTo(249);
    }

    @Test
    void PRE_015_a_moved_loan_continues_on_the_new_version() {
        MigrationPlan plan = seed("bank-a", 3);
        approved("bank-a", plan);
        runner("pod-1").runDue(10);
        String moved = loanIds("bank-a").getFirst();

        engine.identity.as("bank-a", "officer-lan", "processes:read", "processes:task:work");
        engine.identity.roles = java.util.Set.of("LOAN_OFFICER");
        ProcessInstance before = engine.services.instance(moved);
        assertThat(before.definitionVersion()).isEqualTo(2);
        String taskId = before.openTasks().getFirst().id();
        long lock = engine.services.claim(taskId, before.openTasks().getFirst().lockVersion(), "k-claim").lockVersion();
        ProcessInstance after = engine.services.complete(taskId, Map.of("review", "approved"), lock, "k-complete");

        // the review of a v1 loan now reaches the audit step that only v2 has
        assertThat(after.openTasks()).singleElement().satisfies(t -> assertThat(t.nodeId()).isEqualTo("audit"));
    }

    @Test
    void PRE_190_a_second_holder_approves_and_only_the_approval_lets_the_runner_move_instances() {
        MigrationPlan plan = seed("bank-a", 5);
        assertThat(plan.approval()).isEqualTo(MigrationPlan.Approval.FOUR_EYES);

        // When thu proposes, thu approves her own proposal, and the runner polls
        MigrationExecution proposal = propose("bank-a", plan);
        as("bank-a", "thu", MANAGE);
        assertThatThrownBy(() -> executions.approve(plan.id(), proposal.lockVersion(), null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> executions.reject(plan.id(), proposal.lockVersion(), "changed my mind", null))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        // Then the proposal records who and why, nothing changed, and nothing runs
        assertThat(proposal.proposedBy()).isEqualTo("thu");
        assertThat(proposal.reason()).isEqualTo("Add the audit sample after officer review, change CHG-2041");
        assertThat(proposal.approvedBy()).isNull();
        assertThat(planRow(plan.id())).isEqualTo("PROPOSED:1:0:0");
        assertThat(runner("pod-1").runDue(10)).isZero();
        assertThat(movedTo("bank-a", 1)).isEqualTo(5);

        // When lan approves with a stale lock version, then with the right one
        as("bank-a", "lan", MANAGE);
        assertThatThrownBy(() -> executions.approve(plan.id(), 0, null)).isInstanceOf(VersionConflictException.class);
        MigrationExecution approved = executions.approve(plan.id(), proposal.lockVersion(), null);

        // Then only the approval makes the plan runnable, and the history keeps both people
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approvedBy()).isEqualTo("lan");
        assertThat(approved.total()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT string_agg(action || ':' || actor_id, ',' ORDER BY action) "
                + "FROM pre_migration_history WHERE migration_id = ?").param(plan.id()).query(String.class).single())
                .isEqualTo("APPROVED:lan,PLANNED:thu,PROPOSED:thu");
        assertThat(engine.events).extracting(e -> e.type()).contains("processes.migration.proposed.v1",
                "processes.migration.approved.v1");
        assertThat(runner("pod-1").runDue(10)).isEqualTo(5);
        assertThat(movedTo("bank-a", 2)).isEqualTo(5);
        assertThat(planRow(plan.id())).isEqualTo("COMPLETED:2:5:0");
    }

    @Test
    void PRE_190_a_rejected_proposal_moves_nothing_and_may_be_proposed_again() {
        MigrationPlan plan = seed("bank-a", 4);
        MigrationExecution proposal = propose("bank-a", plan);
        as("bank-a", "lan", MANAGE);

        MigrationExecution rejected = executions.reject(plan.id(), proposal.lockVersion(), "Wait for the month end",
                null);

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectedBy()).isEqualTo("lan");
        assertThat(rejected.rejectionReason()).isEqualTo("Wait for the month end");
        assertThat(runner("pod-1").runDue(10)).isZero();
        assertThat(movedTo("bank-a", 2)).isZero();
        assertThatThrownBy(() -> executions.approve(plan.id(), rejected.lockVersion(), null))
                .isInstanceOf(InvalidStateException.class);
        MigrationExecution again = propose("bank-a", plan);
        assertThat(again.status()).isEqualTo("PROPOSED");
        assertThat(again.rejectedBy()).isNull();
        assertThat(jdbc.sql("SELECT string_agg(action, ',' ORDER BY action) FROM pre_migration_history "
                + "WHERE migration_id = ?").param(plan.id()).query(String.class).single())
                .isEqualTo("PLANNED,PROPOSED,PROPOSED,REJECTED");
    }

    @Test
    void PRE_190_a_proposal_needs_a_reason_a_passing_dry_run_and_the_permission() {
        MigrationPlan plan = seed("bank-a", 4);
        as("bank-a", "thu", MANAGE);
        assertThatThrownBy(() -> executions.propose(plan.id(), " ", null)).isInstanceOf(InputInvalidException.class);
        as("bank-a", "viewer", "processes:read");
        assertThatThrownBy(() -> executions.propose(plan.id(), "Reason", null))
                .isInstanceOf(PermissionDeniedException.class);
        // a loan waits at a step v2 does not have: the dry run has a conflict, so the plan cannot be proposed
        String broken = loanIds("bank-a").getFirst();
        jdbc.sql("UPDATE pre_token SET current_node_id = 'legacy-review' WHERE instance_id = ? AND token_kind = 'FLOW'")
                .param(broken).update();
        as("bank-a", "thu", MANAGE);
        long history = count("SELECT count(*) FROM pre_migration_history");

        assertThatThrownBy(() -> executions.propose(plan.id(), "Reason", null))
                .isInstanceOf(DryRunRequiredException.class).hasMessageContaining("1 conflict");

        assertThat(planRow(plan.id())).isEqualTo("PLANNED:0:0:0");
        assertThat(count("SELECT count(*) FROM pre_migration_history")).isEqualTo(history);
    }

    @Test
    void PRE_190_the_dry_run_must_still_be_fresh_and_still_pass_when_the_second_person_approves() {
        // Given a proposal made at 02:00
        MigrationPlan plan = seed("bank-a", 4);
        MigrationExecution proposal = propose("bank-a", plan);

        // When lan approves 61 minutes later
        clock.advance(Duration.ofMinutes(61));
        as("bank-a", "lan", MANAGE);
        assertThatThrownBy(() -> executions.approve(plan.id(), proposal.lockVersion(), null))
                .isInstanceOf(DryRunRequiredException.class).hasMessageContaining("propose the execution again");
        assertThat(planRow(plan.id())).as("still a proposal").isEqualTo("PROPOSED:1:0:0");

        // Then thu proposes again with a fresh dry run; and a loan that has since reached a step v2 lacks blocks it
        MigrationExecution refreshed = propose("bank-a", plan);
        assertThat(refreshed.lockVersion()).isGreaterThan(proposal.lockVersion());
        jdbc.sql("UPDATE pre_token SET current_node_id = 'legacy-review' WHERE instance_id = ? AND token_kind = 'FLOW'")
                .param(loanIds("bank-a").getFirst()).update();
        as("bank-a", "lan", MANAGE);
        assertThatThrownBy(() -> executions.approve(plan.id(), refreshed.lockVersion(), null))
                .isInstanceOf(DryRunRequiredException.class).hasMessageContaining("1 conflict");
        assertThat(movedTo("bank-a", 2)).isZero();
    }

    @Test
    void PRE_190_single_person_execution_is_approved_at_once_and_recorded_as_single() {
        // Given a host that runs the dev profile alone with migration.approval=single
        MigrationSettings single = new MigrationSettings(MigrationPlan.Approval.SINGLE, 100, Duration.ofHours(1), 100,
                Duration.ofMinutes(2));
        MigrationPlan plan = seed("bank-a", 3);
        Migrations singlePlans = engine.migrations(single);
        as("bank-a", "thu", MANAGE, "processes:read");
        MigrationPlan singlePlan = singlePlans.plan(new MigrationPlanRequest(KEY, 1, 2, "single-plan"));
        MigrationExecutions singleExecutions = engine.migrationExecutions(single);

        // When thu proposes
        MigrationExecution proposal = singleExecutions.propose(singlePlan.id(), "Dev data clean-up", null);

        // Then it is approved by her at once, recorded as single, and the runner moves the loans
        assertThat(plan.approval()).isEqualTo(MigrationPlan.Approval.FOUR_EYES);
        assertThat(proposal.status()).isEqualTo("APPROVED");
        assertThat(proposal.approvedBy()).isEqualTo("thu");
        assertThat(proposal.approval()).isEqualTo(MigrationPlan.Approval.SINGLE);
        assertThat(jdbc.sql("SELECT string_agg(action || ':' || (details->>'mode'), ',' ORDER BY occurred_at, id) "
                + "FROM pre_migration_history WHERE migration_id = ? AND action = 'APPROVED'")
                .param(singlePlan.id()).query(String.class).single()).isEqualTo("APPROVED:single");
        assertThat(engine.migrationRunner(single, "pod-1", MigrationRunner.Checkpoint.NONE).runDue(10)).isEqualTo(3);
        assertThat(movedTo("bank-a", 2)).isEqualTo(3);
    }

    @Test
    void PRE_190_a_proposal_is_idempotent_and_a_key_is_not_reused_for_another_reason() {
        MigrationPlan plan = seed("bank-a", 2);
        as("bank-a", "thu", MANAGE);

        MigrationExecution first = executions.propose(plan.id(), "Audit sample", "propose-1");
        MigrationExecution repeat = executions.propose(plan.id(), "Audit sample", "propose-1");

        assertThat(repeat).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_migration_history WHERE migration_id = ? AND action = 'PROPOSED'",
                plan.id())).isEqualTo(1);
        assertThatThrownBy(() -> executions.propose(plan.id(), "Another reason", "propose-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }

    @Test
    void PRE_190_the_table_refuses_a_running_plan_nobody_approved_and_a_four_eyes_approval_by_the_proposer() {
        MigrationPlan plan = seed("bank-a", 2);
        MigrationExecution proposal = propose("bank-a", plan);

        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_migration SET status = 'RUNNING' WHERE id = ?")
                .param(plan.id()).update()).hasMessageContaining("ck_pre_migration_approved");
        assertThatThrownBy(() -> jdbc.sql("UPDATE pre_migration SET status = 'APPROVED', approved_by = 'thu', "
                + "approved_at = now() WHERE id = ?").param(plan.id()).update())
                .hasMessageContaining("ck_pre_migration_four_eyes");
        assertThat(planRow(plan.id())).isEqualTo("PROPOSED:" + proposal.lockVersion() + ":0:0");
        assertThat(runner("pod-1").runDue(10)).isZero();
    }

    @Test
    void PRE_191_another_tenant_can_neither_propose_approve_nor_read_a_migration_and_nothing_is_written() {
        // Given bank-a's plan with a proposal, and bank-b with its own loans and plan
        MigrationPlan planA = seed("bank-a", 3);
        MigrationExecution proposalA = propose("bank-a", planA);
        MigrationPlan planB = seed("bank-b", 4);
        long rows = count("SELECT count(*) FROM pre_migration_history");
        String before = planRow(planA.id());
        int events = engine.events.size();

        // When a bank-b manager proposes, approves, rejects, reads and lists bank-a's plan
        as("bank-b", "mei", MANAGE, "processes:read");
        assertThatThrownBy(() -> executions.propose(planA.id(), "Reason", null)).isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> executions.approve(planA.id(), proposalA.lockVersion(), null))
                .isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> executions.reject(planA.id(), proposalA.lockVersion(), "No", null))
                .isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> executions.execution(planA.id())).isInstanceOf(MigrationNotFoundException.class);
        assertThatThrownBy(() -> executions.outcomes(planA.id(), null, null, 10))
                .isInstanceOf(MigrationNotFoundException.class);

        // Then nothing is written and no event is published
        assertThat(planRow(planA.id())).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pre_migration_history")).isEqualTo(rows);
        assertThat(engine.events).hasSize(events);

        // and when both tenants approve their plans, each runner pass moves only the loans of the plan it holds
        approve("bank-a", planA, proposalA.lockVersion());
        approved("bank-b", planB);
        runner("pod-1").runDue(10);
        runner("pod-1").runDue(10);
        assertThat(List.of(movedTo("bank-a", 2), movedTo("bank-b", 2))).containsExactly(3L, 4L);
        assertThat(count("SELECT count(*) FROM pre_migration_instance WHERE tenant_id = 'bank-a'")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM pre_migration_instance WHERE tenant_id = 'bank-b'")).isEqualTo(4);
        assertThat(progress("bank-b", planB).migrated()).isEqualTo(4);
    }

    @Test
    void PRE_192_a_crashed_runner_is_taken_over_after_its_lease_and_continues_after_the_last_committed_instance() {
        // Given 60 loans, batches of 25, and a runner that crashes after its 40th loan
        MigrationSettings batches = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100, Duration.ofHours(1), 25,
                Duration.ofMinutes(2));
        MigrationPlan plan = seed("bank-a", 60);
        approved("bank-a", plan);
        MigrationRunner crashing = engine.migrationRunner(batches, "pod-a", (migration, instance, dealt) -> {
            if (dealt == 40) {
                throw new IllegalStateException("simulated crash");
            }
        });
        assertThatThrownBy(() -> crashing.runDue(10)).hasMessage("simulated crash");

        // Then 40 loans moved, the plan is RUNNING, and pod-a still holds it
        assertThat(movedTo("bank-a", 2)).isEqualTo(40);
        assertThat(planRow(plan.id())).isEqualTo("RUNNING:2:40:0");
        assertThat(jdbc.sql("SELECT lease_owner FROM pre_migration WHERE id = ?").param(plan.id()).query(String.class)
                .single()).isEqualTo("pod-a");
        MigrationRunner other = engine.migrationRunner(batches, "pod-b", MigrationRunner.Checkpoint.NONE);
        assertThat(other.runDue(10)).as("the lease has not ended").isZero();
        assertThat(movedTo("bank-a", 2)).isEqualTo(40);

        // When the lease ends and pod-b polls
        clock.advance(Duration.ofMinutes(2).plusSeconds(1));
        assertThat(other.runDue(10)).as("the 20 loans after the last committed one").isEqualTo(20);

        // Then every loan moved exactly once and the plan is complete
        assertThat(movedTo("bank-a", 2)).isEqualTo(60);
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'MIGRATED'")).isEqualTo(60);
        assertThat(count("SELECT count(DISTINCT instance_id) FROM pre_history WHERE event_type = 'MIGRATED'")).isEqualTo(60);
        assertThat(count("SELECT count(*) FROM pre_migration_instance WHERE migration_id = ?", plan.id())).isEqualTo(60);
        assertThat(planRow(plan.id())).isEqualTo("COMPLETED:2:60:0");
        assertThat(count("SELECT count(*) FROM pre_incident")).isZero();
    }

    @Test
    void PRE_193_one_writer_per_plan_a_runner_that_lost_its_lease_writes_nothing_more() {
        // Given 30 loans, and pod-a that is slow: after its 5th loan its lease ends and pod-b takes the plan and finishes it
        MigrationPlan plan = seed("bank-a", 30);
        approved("bank-a", plan);
        MigrationRunner b = engine.migrationRunner(SETTINGS, "pod-b", MigrationRunner.Checkpoint.NONE);
        int[] bRan = {0};
        MigrationRunner a = engine.migrationRunner(SETTINGS, "pod-a", (migration, instance, dealt) -> {
            if (dealt == 5) {
                clock.advance(Duration.ofMinutes(2).plusSeconds(1));
                bRan[0] = b.runDue(10);
            }
        });

        // When pod-a goes on with its batch
        int aDealt = a.runDue(10);

        // Then pod-a stopped at its next loan, pod-b did the other 25, and no loan was moved or counted twice
        assertThat(aDealt).isEqualTo(5);
        assertThat(bRan[0]).isEqualTo(25);
        assertThat(movedTo("bank-a", 2)).isEqualTo(30);
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'MIGRATED'")).isEqualTo(30);
        assertThat(count("SELECT count(*) FROM pre_migration_instance WHERE migration_id = ?", plan.id())).isEqualTo(30);
        assertThat(planRow(plan.id())).isEqualTo("COMPLETED:2:30:0");
        assertThat(jdbc.sql("SELECT lease_owner IS NULL FROM pre_migration WHERE id = ?").param(plan.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void PRE_193_two_runners_never_take_the_same_plan() {
        MigrationPlan plan = seed("bank-a", 10);
        approved("bank-a", plan);
        MigrationSettings small = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100, Duration.ofHours(1), 4,
                Duration.ofMinutes(2));
        MigrationRunner[] second = new MigrationRunner[1];
        int[] secondDealt = {-1};
        MigrationRunner first = engine.migrationRunner(small, "pod-a", (migration, instance, dealt) -> {
            if (dealt == 1) {
                secondDealt[0] = second[0].runDue(10);
            }
        });
        second[0] = engine.migrationRunner(small, "pod-b", MigrationRunner.Checkpoint.NONE);

        assertThat(first.runDue(10)).isEqualTo(10);

        assertThat(secondDealt[0]).as("pod-b polled while pod-a held the lease").isZero();
        assertThat(movedTo("bank-a", 2)).isEqualTo(10);
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'MIGRATED'")).isEqualTo(10);
    }

    @Test
    void PRE_015_a_loan_another_transaction_holds_is_a_failure_with_an_incident_and_the_others_move()
            throws SQLException {
        // Given 6 loans and a transaction that holds the lock of the third, until the runner has dealt with that loan
        MigrationPlan plan = seed("bank-a", 6);
        approved("bank-a", plan);
        String held = loanIds("bank-a").get(2);
        Connection other = TestEngine.dataSource().getConnection();
        try {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.execute("SELECT id FROM pre_instance WHERE tenant_id = 'bank-a' AND id = '" + held
                        + "' FOR UPDATE");
            }
            MigrationRunner runner = engine.migrationRunner(SETTINGS, "pod-1", (migration, instance, dealt) -> {
                if (instance.equals(held)) {
                    // the failure is recorded without waiting for the lock; the holder lets go before the plan completes
                    assertThat(jdbc.sql("SELECT outcome || ':' || code || ':' || coalesce(incident_id, 'none') FROM "
                            + "pre_migration_instance WHERE instance_id = ?").param(held).query(String.class).single())
                            .isEqualTo("FAILED:LOCK_CONFLICT:none");
                    try {
                        other.rollback();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }
            });

            // When the runner executes the plan
            assertThat(runner.runDue(10)).isEqualTo(6);
        } finally {
            other.close();
        }

        // Then the held loan is a LOCK_CONFLICT whose incident was opened once the lock was free; the other five moved
        assertThat(movedTo("bank-a", 2)).isEqualTo(5);
        assertThat(executions.outcomes(plan.id(), "FAILED", null, 10)).singleElement().satisfies(o -> {
            assertThat(o.instanceId()).isEqualTo(held);
            assertThat(o.code()).isEqualTo("LOCK_CONFLICT");
            assertThat(o.incidentId()).isNotNull();
        });
        assertThat(count("SELECT count(*) FROM pre_incident WHERE instance_id = ? AND status = 'OPEN'", held)).isEqualTo(1);
        assertThat(planRow(plan.id())).isEqualTo("COMPLETED_WITH_FAILURES:2:5:1");
    }

    @Test
    void PRE_015_a_loan_that_ended_before_the_runner_reached_it_is_skipped_not_failed() {
        // Given 4 loans read in one batch, and the third ends after the batch was read
        MigrationPlan plan = seed("bank-a", 4);
        approved("bank-a", plan);
        String ended = loanIds("bank-a").get(2);
        MigrationRunner runner = engine.migrationRunner(SETTINGS, "pod-1", (migration, instance, dealt) -> {
            if (dealt == 1) {
                jdbc.sql("UPDATE pre_instance SET status = 'COMPLETED', ended_at = now() WHERE id = ?").param(ended)
                        .update();
            }
        });

        // When the runner reaches it
        assertThat(runner.runDue(10)).isEqualTo(4);

        // Then it is skipped, with no incident, and the other three moved
        MigrationExecution done = progress("bank-a", plan);
        assertThat(List.of(done.migrated(), done.failed(), done.skipped())).containsExactly(3L, 0L, 1L);
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(executions.outcomes(plan.id(), "SKIPPED", null, 10)).singleElement().satisfies(o -> {
            assertThat(o.instanceId()).isEqualTo(ended);
            assertThat(o.code()).isEqualTo("NOT_RUNNING");
            assertThat(o.incidentId()).isNull();
        });
        assertThat(count("SELECT count(*) FROM pre_incident")).isZero();
        assertThat(count("SELECT count(*) FROM pre_instance WHERE id = ? AND definition_ver = 1", ended)).isEqualTo(1);
    }
}
