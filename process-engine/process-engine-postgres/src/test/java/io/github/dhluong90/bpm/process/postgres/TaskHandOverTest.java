package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.AssigneeNotAllowedException;
import io.github.dhluong90.bpm.process.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.spi.AssigneeDirectory;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-183 to PRE-187 on PostgreSQL, in {@code loan-approval.bpmn}: {@code alice} starts LN-2027-0042 and {@code bob}
 * submits it; officers {@code carol} and {@code dan} hold LOAN_OFFICER in the host directory, {@code lan} holds
 * CUSTOMER_SERVICE only, and supervisor {@code hoa} holds {@code processes:task:manage} and {@code processes:task:work} (reading a task needs the latter).
 */
class TaskHandOverTest {

    private static final String[] OFFICER = {"processes:start", "processes:read", "processes:task:work"};
    private static final String[] SUPERVISOR = {"processes:read", "processes:task:work", "processes:task:manage"};
    private static final Map<String, String> ROLE_OF = Map.of("alice", "LOAN_OFFICER", "bob", "LOAN_OFFICER",
            "carol", "LOAN_OFFICER", "dan", "LOAN_OFFICER", "lan", "CUSTOMER_SERVICE");

    private final TestClock clock = TestClock.at("2027-03-01T02:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).build();
        engine.services.useAssigneeDirectory(directory());
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-approval.bpmn"), null));
    }

    private static AssigneeDirectory directory() {
        return new AssigneeDirectory() {
            @Override
            public boolean holdsRole(String tenantId, String userId, String role) {
                return "bank-a".equals(tenantId) && role.equals(ROLE_OF.get(userId));
            }

            @Override
            public boolean inGroup(String tenantId, String userId, String group) {
                return false;
            }
        };
    }

    private void asOfficer(String caller) {
        engine.identity.as("bank-a", caller, OFFICER).roles = Set.of("LOAN_OFFICER");
    }

    private void asSupervisor() {
        engine.identity.as("bank-a", "hoa", SUPERVISOR);
    }

    /** alice starts LN-2027-0042 and bob completes the submission: the approval and the document check are open. */
    private ProcessInstance submittedByBob() {
        asOfficer("alice");
        ProcessInstance started = engine.services.start(new StartRequest("loan-approval", "LN-2027-0042",
                Map.of("amount", Map.of("amount", "250000000", "currency", "VND")), null));
        asOfficer("bob");
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

    private record Snapshot(int history, int idempotency, int events, String status, long lockVersion, String claimant) {
    }

    private Snapshot snapshot(String taskId) {
        return new Snapshot(count("SELECT count(*) FROM pre_history"), count("SELECT count(*) FROM pre_idempotency"),
                engine.events.size(),
                TestEngine.jdbc().sql("SELECT status FROM pre_task WHERE id = ?").param(taskId).query(String.class)
                        .single(),
                TestEngine.jdbc().sql("SELECT lock_version FROM pre_task WHERE id = ?").param(taskId)
                        .query(Long.class).single(),
                TestEngine.jdbc().sql("SELECT coalesce(claimed_by, '') FROM pre_task WHERE id = ?").param(taskId)
                        .query(String.class).single());
    }

    private long events(String type) {
        return engine.events.stream().filter(e -> e.type().equals(type)).count();
    }

    @Test
    void PRE_183_reassign_and_delegate_move_a_task_and_record_both_people_and_the_reason() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        asSupervisor();
        assertThat(engine.services.task(check.id()).allowedActions()).containsExactly("reassign");

        // reassign the open check to carol
        TaskView reassigned = engine.services.reassign(check.id(), "carol", "Loan queue is backed up", 0, "re-1");

        assertThat(reassigned.status()).isEqualTo("CLAIMED");
        assertThat(reassigned.claimedBy()).isEqualTo("carol");
        assertThat(reassigned.lockVersion()).isEqualTo(1);
        assertThat(reassigned.assignee()).isEqualTo("role:LOAN_OFFICER");
        assertThat(reassigned.allowedActions()).containsExactly("delegate");
        assertThat(events("processes.task.reassigned.v1")).isEqualTo(1);
        var reassignedEvent = engine.events.stream().filter(e -> e.type().equals("processes.task.reassigned.v1"))
                .findFirst().orElseThrow();
        assertThat(reassignedEvent.tenantId()).isEqualTo("bank-a");
        assertThat(reassignedEvent.payload()).containsEntry("reassignedBy", "hoa").containsEntry("claimedBy", "carol")
                .containsEntry("reason", "Loan queue is backed up").containsEntry("taskId", check.id());
        assertThat(TestEngine.jdbc().sql("SELECT actor_id || '|' || details::text FROM pre_history WHERE "
                + "event_type = 'TASK_REASSIGNED'").query(String.class).single())
                .startsWith("hoa|").contains("\"claimedBy\": \"carol\"").contains("Loan queue is backed up");

        // the replay returns the first answer and writes nothing
        Snapshot afterFirst = snapshot(check.id());
        assertThat(engine.services.reassign(check.id(), "carol", "Loan queue is backed up", 0, "re-1").lockVersion())
                .isEqualTo(1);
        assertThat(snapshot(check.id())).isEqualTo(afterFirst);

        // carol falls ill: delegate to dan
        TaskView delegated = engine.services.delegate(check.id(), "dan", "Carol is on sick leave", 1, "de-1");

        assertThat(delegated.claimedBy()).isEqualTo("dan");
        assertThat(delegated.lockVersion()).isEqualTo(2);
        assertThat(delegated.status()).isEqualTo("CLAIMED");
        assertThat(delegated.assignee()).isEqualTo("role:LOAN_OFFICER");
        var delegatedEvent = engine.events.stream().filter(e -> e.type().equals("processes.task.delegated.v1"))
                .findFirst().orElseThrow();
        assertThat(delegatedEvent.payload()).containsEntry("delegatedBy", "hoa")
                .containsEntry("previousClaimant", "carol").containsEntry("claimedBy", "dan")
                .containsEntry("reason", "Carol is on sick leave");
        assertThat(count("SELECT count(*) FROM pre_history WHERE event_type = 'TASK_DELEGATED'")).isEqualTo(1);

        // dan, now the claimant, completes it
        asOfficer("dan");
        assertThat(engine.services.task(check.id()).allowedActions()).containsExactlyInAnyOrder("unclaim", "complete");
        engine.services.complete(check.id(), Map.of("complete", true), 2, "dan-check");
    }

    @Test
    void PRE_183_a_replay_returns_the_first_answer_and_writes_nothing() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        asSupervisor();
        engine.services.reassign(check.id(), "carol", "Loan queue is backed up", 0, "re-1");
        Snapshot before = snapshot(check.id());

        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Loan queue is backed up", 0, "re-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(snapshot(check.id())).isEqualTo(before);
    }

    @Test
    void PRE_183_a_completed_or_cancelled_task_is_never_moved() {
        ProcessInstance instance = submittedByBob();
        ProcessInstance.Task check = task(instance, "check-documents");
        ProcessInstance.Task approval = task(instance, "approve-credit");
        asOfficer("carol");
        long claimed = engine.services.claim(check.id(), check.lockVersion(), "carol-claim").lockVersion();
        engine.services.complete(check.id(), Map.of("complete", true), claimed, "carol-check");
        asSupervisor();
        Snapshot done = snapshot(check.id());

        // a completed task: INVALID_STATE for both commands
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for Carol", claimed + 1, "x-1"))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> engine.services.delegate(check.id(), "dan", "Cover for Carol", claimed + 1, "x-2"))
                .isInstanceOf(InvalidStateException.class);
        assertThat(snapshot(check.id())).isEqualTo(done);

        // a cancelled instance cancels its tasks
        engine.identity.as("bank-a", "hoa", "processes:cancel", "processes:read", "processes:task:work",
                "processes:task:manage");
        engine.services.cancel(instance.id(), engine.services.instance(instance.id()).lockVersion(),
                "Customer withdrew the application", "cancel-1");
        Snapshot cancelled = snapshot(approval.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> engine.services.reassign(approval.id(), "dan", "Cover for Carol",
                cancelled.lockVersion(), "x-3")).isInstanceOf(InvalidStateException.class);
        assertThat(snapshot(approval.id())).isEqualTo(cancelled);

        // an open task cannot be delegated, and a claimed one cannot be reassigned
        assertThat(engine.services.task(approval.id()).allowedActions()).isEmpty();
    }

    @Test
    void PRE_183_a_claimed_task_is_delegated_not_reassigned_and_an_open_one_the_reverse() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        asSupervisor();

        assertThatThrownBy(() -> engine.services.delegate(check.id(), "dan", "Cover for Carol", 0, "x-1"))
                .isInstanceOf(InvalidStateException.class);
        engine.services.reassign(check.id(), "carol", "Loan queue is backed up", 0, "re-1");
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for Carol", 1, "x-2"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void PRE_184_the_starter_and_an_earlier_completer_cannot_be_given_a_four_eyes_task() {
        ProcessInstance.Task approval = task(submittedByBob(), "approve-credit");
        asOfficer("carol");
        long claimed = engine.services.claim(approval.id(), approval.lockVersion(), "carol-claim").lockVersion();
        asSupervisor();
        Snapshot before = snapshot(approval.id());

        for (String target : new String[] {"alice", "bob", "carol", "system:timer"}) {
            assertThatThrownBy(() -> engine.services.delegate(approval.id(), target, "Cover for Carol", claimed,
                    "to-" + target)).isInstanceOf(AssigneeNotAllowedException.class)
                    .satisfies(e -> assertThat(((AssigneeNotAllowedException) e).code())
                            .isEqualTo("ASSIGNEE_NOT_ALLOWED"));
            assertThat(snapshot(approval.id())).isEqualTo(before);
        }

        assertThat(engine.services.delegate(approval.id(), "dan", "Cover for Carol", claimed, "to-dan").claimedBy())
                .isEqualTo("dan");
    }

    @Test
    void PRE_184_a_user_without_the_role_or_group_is_refused_and_a_user_task_goes_to_anyone() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        asSupervisor();
        Snapshot before = snapshot(check.id());

        assertThatThrownBy(() -> engine.services.reassign(check.id(), "lan", "Cover the queue", 0, "to-lan"))
                .isInstanceOf(AssigneeNotAllowedException.class);
        assertThat(snapshot(check.id())).isEqualTo(before);

        // the task is assigned to one named officer, who is away: any user may take it
        TestEngine.jdbc().sql("UPDATE pre_task SET assignee_role = NULL, assignee_user = 'carol' WHERE id = ?")
                .param(check.id()).update();
        assertThat(engine.services.reassign(check.id(), "lan", "Carol is away", 0, "to-lan-2").claimedBy())
                .isEqualTo("lan");
    }

    @Test
    void PRE_184_without_a_directory_a_role_task_cannot_be_given_to_a_named_user() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        engine.services.useAssigneeDirectory(null);
        asSupervisor();
        Snapshot before = snapshot(check.id());

        assertThatThrownBy(() -> engine.services.reassign(check.id(), "carol", "Loan queue is backed up", 0, "re-1"))
                .isInstanceOf(AssigneeNotAllowedException.class);

        assertThat(snapshot(check.id())).isEqualTo(before);
    }

    @Test
    void PRE_187_moving_a_task_needs_the_manage_permission_and_the_task_stays_unchanged_when_refused() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        Snapshot before = snapshot(check.id());

        asOfficer("carol");
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for me", 0, "k-1"))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(engine.services.task(check.id()).allowedActions()).doesNotContain("reassign", "delegate");

        asSupervisor();
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for me", 7, "k-2"))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for me", 0, null))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.PreconditionRequiredException.class);
        for (String reason : new String[] {null, "", "   ", "x".repeat(501)}) {
            assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", reason, 0, "k-3"))
                    .isInstanceOf(InputInvalidException.class);
        }
        assertThatThrownBy(() -> engine.services.reassign(check.id(), " ", "Cover for me", 0, "k-4"))
                .isInstanceOf(InputInvalidException.class);
        assertThat(snapshot(check.id())).isEqualTo(before);

        // 500 characters is the longest reason
        assertThat(engine.services.reassign(check.id(), "dan", "x".repeat(500), 0, "k-5").claimedBy())
                .isEqualTo("dan");
    }

    @Test
    void PRE_187_another_tenant_cannot_see_or_move_the_task() {
        ProcessInstance.Task check = task(submittedByBob(), "check-documents");
        Snapshot before = snapshot(check.id());

        engine.identity.as("bank-b", "thu", SUPERVISOR);
        assertThatThrownBy(() -> engine.services.reassign(check.id(), "dan", "Cover for me", 0, "k-1"))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> engine.services.delegate(check.id(), "dan", "Cover for me", 0, "k-2"))
                .isInstanceOf(TaskNotFoundException.class);

        assertThat(snapshot(check.id())).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM pre_idempotency WHERE tenant_id = 'bank-b'")).isZero();
    }
}
