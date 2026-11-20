package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-007 on PostgreSQL: in {@code loan-approval.bpmn} alice starts the instance, bob submits the application, and
 * the four-eyes credit approval runs in parallel with a document check. Neither the starter nor an earlier completer
 * may claim or complete the approval, a claimant who completes the parallel check afterwards may no longer complete
 * it, and every refusal writes nothing.
 */
class FourEyesApprovalTest {

    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work"};

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-approval.bpmn"), null));
    }

    private void as(String caller) {
        engine.identity.as("bank-a", caller, OFFICER).roles = Set.of("LOAN_OFFICER");
    }

    /** alice starts LN-2027-0042, bob claims and completes the submission: the approval and the check are open. */
    private ProcessInstance submittedByBob() {
        as("alice");
        ProcessInstance started = engine.services.start(new StartRequest("loan-approval", "LN-2027-0042",
                Map.of("amount", Map.of("amount", "250000000", "currency", "VND")), null));
        as("bob");
        ProcessInstance.Task submit = task(started, "submit-application");
        long claimed = engine.services.claim(submit.id(), submit.lockVersion(), "bob-claim-submit").lockVersion();
        return engine.services.complete(submit.id(), Map.of("complete", true), claimed, "bob-submit");
    }

    private static ProcessInstance.Task task(ProcessInstance instance, String nodeId) {
        return instance.tasks().stream().filter(t -> t.nodeId().equals(nodeId) && !"COMPLETED".equals(t.status()))
                .findFirst().orElseThrow();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    private record Snapshot(int history, int idempotency, int events, String status, long lockVersion) {
    }

    private Snapshot snapshot(String taskId) {
        return new Snapshot(count("SELECT count(*) FROM pre_history"), count("SELECT count(*) FROM pre_idempotency"),
                engine.events.size(),
                TestEngine.jdbc().sql("SELECT status FROM pre_task WHERE id = ?").param(taskId).query(String.class)
                        .single(),
                TestEngine.jdbc().sql("SELECT lock_version FROM pre_task WHERE id = ?").param(taskId)
                        .query(Long.class).single());
    }

    @Test
    void PRE_007_the_starter_cannot_claim_the_four_eyes_approval_and_nothing_is_written() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task approval = task(instance, "approve-credit");
        Snapshot before = snapshot(approval.id());

        as("alice");
        assertThatThrownBy(() -> engine.services.claim(approval.id(), approval.lockVersion(), "alice-claim"))
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .satisfies(e -> assertThat(((SelfApprovalForbiddenException) e).code())
                        .isEqualTo("SELF_APPROVAL_FORBIDDEN"));

        assertThat(snapshot(approval.id())).isEqualTo(before);
        assertThat(engine.services.task(approval.id()).allowedActions()).isEmpty();
    }

    @Test
    void PRE_007_the_completer_of_an_earlier_step_cannot_claim_the_four_eyes_approval_and_nothing_is_written() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task approval = task(instance, "approve-credit");
        Snapshot before = snapshot(approval.id());

        as("bob");
        assertThatThrownBy(() -> engine.services.claim(approval.id(), approval.lockVersion(), "bob-claim"))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        assertThat(snapshot(approval.id())).isEqualTo(before);
        // bob may still work the parallel document check, which has no four-eyes rule
        ProcessInstance.Task check = task(instance, "check-documents");
        assertThat(engine.services.claim(check.id(), check.lockVersion(), "bob-claim-check").status())
                .isEqualTo("CLAIMED");
    }

    @Test
    void PRE_007_a_claimant_who_then_completes_an_earlier_task_cannot_complete_the_approval() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task approval = task(instance, "approve-credit");
        ProcessInstance.Task check = task(instance, "check-documents");

        // carol claims the approval while she is not yet a participant, then completes the document check
        as("carol");
        long claimed = engine.services.claim(approval.id(), approval.lockVersion(), "carol-claim").lockVersion();
        long checkClaimed = engine.services.claim(check.id(), check.lockVersion(), "carol-claim-check").lockVersion();
        engine.services.complete(check.id(), Map.of("complete", true), checkClaimed, "carol-check");
        Snapshot before = snapshot(approval.id());

        // her completion of the approval is refused, and nothing is written
        assertThatThrownBy(() -> engine.services.complete(approval.id(), Map.of("approved", true), claimed,
                "carol-approve")).isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(snapshot(approval.id())).isEqualTo(before);
        assertThat(before.status()).isEqualTo("CLAIMED");
    }

    @Test
    void PRE_007_another_officer_claims_and_completes_the_approval() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task approval = task(instance, "approve-credit");
        ProcessInstance.Task check = task(instance, "check-documents");

        as("dung");
        assertThat(engine.services.task(approval.id()).allowedActions()).containsExactly("claim");
        long claimed = engine.services.claim(approval.id(), approval.lockVersion(), "dung-claim").lockVersion();
        ProcessInstance approved = engine.services.complete(approval.id(), Map.of("approved", true), claimed,
                "dung-approve");
        long checkClaimed = engine.services.claim(check.id(), check.lockVersion(), "dung-claim-check").lockVersion();
        ProcessInstance done = engine.services.complete(check.id(), Map.of("complete", true), checkClaimed,
                "dung-check");

        assertThat(approved.tasks()).filteredOn(t -> t.id().equals(approval.id())).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo("COMPLETED"));
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM pre_task WHERE id = ? AND completed_by = 'dung'", approval.id()))
                .isEqualTo(1);
    }

    @Test
    void PRE_007_a_caller_of_another_tenant_cannot_claim_or_complete_the_approval_and_nothing_is_written() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task approval = task(instance, "approve-credit");
        Snapshot before = snapshot(approval.id());

        engine.identity.as("bank-b", "zoe", OFFICER).roles = Set.of("LOAN_OFFICER");
        assertThatThrownBy(() -> engine.services.claim(approval.id(), approval.lockVersion(), "zoe-claim"))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.services.complete(approval.id(), Map.of("approved", true), 1L, "zoe-approve"))
                .isInstanceOf(TaskNotFoundException.class);

        assertThat(snapshot(approval.id())).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pre_idempotency WHERE tenant_id = 'bank-b'")).isZero();
        assertThat(count("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-b'")).isZero();
    }
}
