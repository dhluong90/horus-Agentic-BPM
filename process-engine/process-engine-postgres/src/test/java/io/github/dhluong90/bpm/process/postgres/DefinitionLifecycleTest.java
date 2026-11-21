package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.EventListenerFailedException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import io.github.dhluong90.bpm.process.api.VersionSupersededException;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PRE-018 to PRE-021 and PRE-037 on PostgreSQL: a KYC onboarding process imported as a draft, requested by alice,
 * approved by bob, effective on its date, rejected with a reason, never approved over a newer version, with history
 * and events in the transaction of each change.
 */
class DefinitionLifecycleTest {

    private static final String KEY = "kyc-onboarding";
    private static final String[] PIPELINE = {"processes:deploy", "processes:read", "processes:start"};
    private static final String[] AUTHOR = {"processes:author", "processes:read", "processes:approve"};
    private static final String[] APPROVER = {"processes:approve", "processes:read"};

    private final TestClock clock = TestClock.at("2027-03-27T03:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).autoApprove(false).build();
    }

    private static byte[] kyc(int variant) {
        String text = new String(TestEngine.resource("/bpmn/kyc-onboarding.bpmn"), StandardCharsets.UTF_8);
        return (variant == 1 ? text : text.replace("Review identity documents", "Review identity documents, variant "
                + variant)).getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionVersion importAs(int variant) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(variant), null));
    }

    private DefinitionVersion as(String caller, String[] perms, java.util.function.Supplier<DefinitionVersion> command) {
        engine.identity.as("bank-a", caller, perms);
        return command.get();
    }

    private DefinitionVersion request(DefinitionVersion v, Instant effectiveFrom) {
        return as("alice", AUTHOR, () -> engine.services.requestApproval(v.ref(), v.lockVersion(), effectiveFrom, null));
    }

    private DefinitionVersion approveByBob(DefinitionVersion v) {
        return as("bob", APPROVER, () -> engine.services.approve(v.ref(), v.lockVersion(), v.definitionHash(), "checked", null));
    }

    /** Imports a variant and takes it through request (alice) and approval (bob), effective now. */
    private DefinitionVersion activate(int variant) {
        return approveByBob(request(importAs(variant), null));
    }

    private ProcessInstance start(String businessKey) {
        engine.identity.as("bank-a", "teller", PIPELINE);
        return engine.services.start(new StartRequest(KEY, businessKey, Map.of(), null));
    }

    private DefinitionVersion stored(int version) {
        engine.identity.as("bank-a", "auditor", "processes:read");
        return engine.services.find(DefinitionRef.of(KEY, version)).orElseThrow();
    }

    private List<String> eventTypes() {
        return engine.events.stream().map(ProcessEventPublisher.Event::type).toList();
    }

    private static int count(String sql, Object... params) {
        return TestEngine.jdbc().sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void PRE_018_import_creates_draft_not_active_version() {
        // Given kyc-onboarding v1 active, outside the dev profile
        activate(1);

        // When the pipeline imports a changed document
        DefinitionVersion v2 = importAs(2);

        // Then v2 is a draft from an import, with its hash, and new instances still start on v1
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.status()).isEqualTo("DRAFT");
        assertThat(v2.source()).isEqualTo("IMPORT");
        assertThat(v2.definitionHash()).hasSize(64).isNotEqualTo(stored(1).definitionHash());
        assertThat(start("KYC-2027-0001").definitionVersion()).isEqualTo(1);
        assertThat(stored(1).status()).isEqualTo("ACTIVE");
        engine.identity.as("bank-a", "auditor", "processes:read");
        assertThat(engine.services.history(v2.ref())).extracting(DefinitionHistoryEntry::action)
                .containsExactly("IMPORTED");
    }

    @Test
    void PRE_019_no_maker_of_a_definition_version_can_approve() {
        // Given v2 pending approval, requested by alice; alice and bob both hold processes:approve
        activate(1);
        DefinitionVersion pending = request(importAs(2), null);
        int historyBefore = count("SELECT count(*) FROM pre_definition_history");
        int eventsBefore = engine.events.size();

        // When alice approves her own request through the Java API
        engine.identity.as("bank-a", "alice", AUTHOR);
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, "k-alice"))
                // Then it is refused with the REST code and writes nothing
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(stored(2).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(stored(2).lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(historyBefore);
        assertThat(engine.events).hasSize(eventsBefore);

        // When the importer approves (default rule: makers)
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:approve", "processes:read");
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                // Then it is refused as well, naming why, and nothing is written
                .isInstanceOf(SelfApprovalForbiddenException.class).hasMessageContaining("importer");
        assertThat(stored(2).lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(historyBefore);

        // When bob approves with the current lock version
        DefinitionVersion approved = approveByBob(pending);

        // Then v2 is active, approved by bob
        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.approvedBy()).isEqualTo("bob");
        assertThat(approved.requestedBy()).isEqualTo("alice");
    }

    @Test
    void PRE_019_the_importer_cannot_approve_under_the_default_rule() {
        // Given the pipeline imported v1 and alice requested it; the pipeline identity also holds processes:approve
        DefinitionVersion pending = request(importAs(1), null);

        // When the importer approves
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:approve", "processes:read");
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                // Then it is refused as a maker (PD-096) and nothing is written
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .hasMessageContaining("approver-must-differ-from");
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM pre_definition_history WHERE action = 'APPROVED'")).isZero();
    }

    @Test
    void PRE_019_the_requester_rule_lets_the_importer_approve_records_it_and_still_refuses_the_requester() {
        // Given the relaxation approver-must-differ-from: requester
        TestEngine relaxed = TestEngine.replica("pod-1", clock).autoApprove(false)
                .approverRule(io.github.dhluong90.bpm.process.internal.service.EngineSettings.ApproverRule.REQUESTER)
                .build();
        relaxed.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion v1 = relaxed.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        relaxed.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion pending = relaxed.services.requestApproval(v1.ref(), v1.lockVersion(), null, null);

        // When the requester approves, then the importer
        assertThatThrownBy(() -> relaxed.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        relaxed.identity.as("bank-a", "pipeline", "processes:deploy", "processes:approve", "processes:read");
        DefinitionVersion approved = relaxed.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null);

        // Then the importer's approval stands, and the approval row records the relaxation
        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.approvedBy()).isEqualTo("pipeline");
        assertThat(TestEngine.jdbc().sql("SELECT details->>'approverMustDifferFrom' FROM pre_definition_history "
                + "WHERE action = 'APPROVED'").query(String.class).single()).isEqualTo("requester");
    }

    @Test
    void PRE_021_a_requester_cannot_reject_their_own_request() {
        // Given v1 pending, requested by alice, who also holds processes:approve
        DefinitionVersion pending = request(importAs(1), null);
        int history = count("SELECT count(*) FROM pre_definition_history");

        // When alice rejects her own request
        engine.identity.as("bank-a", "alice", AUTHOR);
        assertThatThrownBy(() -> engine.services.reject(pending.ref(), pending.lockVersion(), "changed my mind", null))
                // Then it is refused, pointing to withdraw, and nothing is written (PD-097)
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .hasMessageContaining("withdraw");
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
    }

    @Test
    void TR_OBS_01_definition_history_refuses_update_delete_and_truncate() {
        importAs(1);

        for (String sql : new String[] {"UPDATE pre_definition_history SET actor_id = 'mallory'",
                "DELETE FROM pre_definition_history", "TRUNCATE pre_definition_history"}) {
            assertThatThrownBy(() -> TestEngine.jdbc().sql(sql).update()).as(sql)
                    .hasMessageContaining("pre_definition_history is append-only");
        }
        assertThat(TestEngine.jdbc().sql("SELECT actor_id FROM pre_definition_history").query(String.class).single())
                .isEqualTo("pipeline");
    }

    // ---- reserved system identities: refused at every entry point (403 PERMISSION_DENIED) ----------------------

    private static final String[] EVERY_PERMISSION = {"processes:deploy", "processes:author", "processes:approve",
        "processes:start", "processes:cancel", "processes:read", "processes:task:work"};

    /** Acts as a host user named like an engine identity, holding every permission; asserts the refusal. */
    private void refusedAsSystem(String systemId, Runnable call) {
        engine.identity.as("bank-a", systemId, EVERY_PERMISSION).roles = java.util.Set.of("KYC_OFFICER");
        int history = count("SELECT count(*) FROM pre_definition_history");
        int instanceHistory = count("SELECT count(*) FROM pre_history");
        assertThatThrownBy(call::run).isInstanceOf(PermissionDeniedException.class).hasMessageContaining("reserved");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM pre_history")).isEqualTo(instanceHistory);
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_import() {
        refusedAsSystem("system:startup", () -> engine.services.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null)));
        assertThat(count("SELECT count(*) FROM pre_definition")).isZero();
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_request_approval() {
        DefinitionVersion v1 = importAs(1);
        refusedAsSystem("system:dev-auto-approve", () -> engine.services.requestApproval(v1.ref(), v1.lockVersion(),
                null, null));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_approve() {
        DefinitionVersion pending = request(importAs(1), null);
        refusedAsSystem("system:dev-auto-approve", () -> engine.services.approve(pending.ref(), pending.lockVersion(),
                null, null));
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_reject() {
        DefinitionVersion pending = request(importAs(1), null);
        refusedAsSystem("system:effective-date", () -> engine.services.reject(pending.ref(), pending.lockVersion(),
                "no", null));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_retire() {
        DefinitionVersion v1 = activate(1);
        refusedAsSystem("system:effective-date", () -> engine.services.retire(v1.ref(), v1.lockVersion(), "stop",
                true, null));
        assertThat(stored(1).status()).isEqualTo("ACTIVE");
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_definition_reads() {
        DefinitionVersion v1 = importAs(1);
        refusedAsSystem("system:startup", () -> engine.services.find(v1.ref()));
        refusedAsSystem("system:startup", () -> engine.services.history(v1.ref()));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_instance_start() {
        activate(1);
        refusedAsSystem("system:timer", () -> engine.services.start(new StartRequest(KEY, "KYC-SYS-1", Map.of(), null)));
        assertThat(count("SELECT count(*) FROM pre_instance")).isZero();
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_instance_read() {
        activate(1);
        ProcessInstance instance = start("KYC-SYS-2");
        refusedAsSystem("system:timer", () -> engine.services.instance(instance.id()));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_task_completion() {
        // A user named system:timer must never complete a task: the history would credit the engine
        activate(1);
        ProcessInstance instance = start("KYC-SYS-3");
        ProcessInstance.Task task = instance.tasks().get(0);
        refusedAsSystem("system:timer", () -> engine.services.complete(task.id(), Map.of("approved", true),
                task.lockVersion(), "complete-sys-3"));
        assertThat(count("SELECT count(*) FROM pre_task WHERE status = 'OPEN'")).isEqualTo(1);
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_definition_list_and_version_read() {
        importAs(1);
        refusedAsSystem("system:startup", () -> engine.services.definitions(PageRequest.first()));
        refusedAsSystem("system:startup", () -> engine.services.version(KEY, 1));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_instance_list() {
        activate(1);
        start("KYC-SYS-4");
        refusedAsSystem("system:timer", () -> engine.services.instances(InstanceQuery.running()));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_instance_cancel() {
        // A user named system:timer must never cancel a case: the history would show the engine stopping it
        activate(1);
        ProcessInstance instance = start("KYC-SYS-5");
        refusedAsSystem("system:timer", () -> engine.services.cancel(instance.id(), instance.lockVersion(),
                "Customer withdrew the application", "cancel-sys-5"));
        assertThat(count("SELECT count(*) FROM pre_instance WHERE status = 'RUNNING'")).isEqualTo(1);
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_task_read() {
        activate(1);
        ProcessInstance.Task task = start("KYC-SYS-6").tasks().get(0);
        refusedAsSystem("system:timer", () -> engine.services.task(task.id()));
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_task_claim() {
        activate(1);
        ProcessInstance.Task task = start("KYC-SYS-7").tasks().get(0);
        refusedAsSystem("system:timer", () -> engine.services.claim(task.id(), task.lockVersion(), "claim-sys-7"));
        assertThat(count("SELECT count(*) FROM pre_task WHERE status = 'OPEN' AND claimed_by IS NULL")).isEqualTo(1);
    }

    @Test
    void TR_SEC_04_reserved_system_identity_is_refused_on_task_unclaim() {
        // Given the task claimed by a real officer
        activate(1);
        ProcessInstance.Task task = start("KYC-SYS-8").tasks().get(0);
        engine.identity.as("bank-a", "officer", "processes:task:work", "processes:read").roles = java.util.Set.of(
                "KYC_OFFICER");
        long claimed = engine.services.claim(task.id(), task.lockVersion(), "claim-sys-8").lockVersion();

        // When a user named system:timer unclaims it, then it is refused and the claim stays
        refusedAsSystem("system:timer", () -> engine.services.unclaim(task.id(), claimed, "unclaim-sys-8"));
        assertThat(count("SELECT count(*) FROM pre_task WHERE status = 'CLAIMED' AND claimed_by = 'officer'"))
                .isEqualTo(1);
    }

    @Test
    void PRE_019_the_table_refuses_an_approver_equal_to_the_requester() {
        DefinitionVersion pending = request(importAs(1), null);

        assertThatThrownBy(() -> TestEngine.jdbc().sql("UPDATE pre_definition SET status = 'ACTIVE', approved_by = "
                + "requested_by, effective_from = now() WHERE id = ?").param(pending.id()).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void PRE_020_effective_date_switches_version_and_retires_previous() {
        // Given v1 active with a running instance, and v2 requested for 2027-04-01 00:00 +07:00
        activate(1);
        ProcessInstance onV1 = start("KYC-2027-0100");
        Instant aprilFirst = OffsetDateTime.parse("2027-04-01T00:00:00+07:00").toInstant();
        DefinitionVersion requested = request(importAs(2), aprilFirst);
        clock.set(Instant.parse("2027-03-28T02:00:00Z"));
        DefinitionVersion approved = approveByBob(requested);
        assertThat(approved.effectiveFrom()).isEqualTo(aprilFirst);
        assertThat(stored(1).status()).as("v1 stays active until the date").isEqualTo("ACTIVE");
        TestEngine otherReplica = TestEngine.replica("pod-2", clock).autoApprove(false).build();

        // When instances start on 31 March 23:59 and 1 April 00:01 (+07:00), and the replicas poll their jobs
        clock.set(OffsetDateTime.parse("2027-03-31T23:59:00+07:00").toInstant());
        int firedEarly = engine.jobs.pollOnce() + otherReplica.jobs.pollOnce();
        ProcessInstance before = start("KYC-2027-0101");
        clock.set(OffsetDateTime.parse("2027-04-01T00:01:00+07:00").toInstant());
        ProcessInstance after = start("KYC-2027-0102");
        int fired = engine.jobs.pollOnce() + otherReplica.jobs.pollOnce();
        int firedAgain = engine.jobs.pollOnce() + otherReplica.jobs.pollOnce();

        // Then the first pins v1, the second v2, and v1 is retired once at the effective time
        assertThat(before.definitionVersion()).isEqualTo(1);
        assertThat(after.definitionVersion()).isEqualTo(2);
        assertThat(firedEarly).isZero();
        assertThat(fired).isEqualTo(1);
        assertThat(firedAgain).isZero();
        DefinitionVersion v1 = stored(1);
        assertThat(v1.status()).isEqualTo("RETIRED");
        assertThat(v1.supersededBy()).isEqualTo(2);
        assertThat(v1.retiredBy()).isEqualTo("system:effective-date");
        assertThat(engine.events).filteredOn(e -> e.type().equals("processes.definition.retired.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("version", 1).containsEntry("supersededBy", 2));
        // and the instance already on v1 continues on v1
        engine.identity.as("bank-a", "officer", "processes:task:work", "processes:read").roles = java.util.Set.of(
                "KYC_OFFICER");
        ProcessInstance.Task review = onV1.tasks().get(0);
        long claimed = engine.services.claim(review.id(), review.lockVersion(), "claim-kyc-0100").lockVersion();
        ProcessInstance completed = engine.services.complete(review.id(), Map.of("approved", true), claimed,
                "complete-kyc-0100");
        assertThat(completed.definitionVersion()).isEqualTo(1);
        assertThat(completed.status()).isEqualTo("COMPLETED");
    }

    @Test
    void PRE_021_reject_returns_draft_and_superseded_approval_refused() {
        // Given v3 pending approval, requested by alice
        activate(1);
        importAs(2);
        DefinitionVersion v3 = request(importAs(3), null);

        // When bob rejects without a reason, then with one
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.reject(v3.ref(), v3.lockVersion(), " ", null))
                .isInstanceOf(InputInvalidException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("INPUT_INVALID");
        assertThatThrownBy(() -> engine.services.reject(v3.ref(), v3.lockVersion(), "x".repeat(2001), null))
                .as("a reason above lifecycle.reason-max-length (2,000)").isInstanceOf(InputInvalidException.class);
        DefinitionVersion rejected = engine.services.reject(v3.ref(), v3.lockVersion(), "missing fraud check", null);

        // Then v3 is a draft again with the reason stored, and rejected.v1 is published
        assertThat(rejected.status()).isEqualTo("DRAFT");
        assertThat(rejected.decisionComment()).isEqualTo("missing fraud check");
        assertThat(count("SELECT count(*) FROM pre_definition WHERE id = ? AND decision_comment = 'missing fraud check'",
                v3.id())).isEqualTo(1);
        assertThat(engine.events).filteredOn(e -> e.type().equals("processes.definition.rejected.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("rejectedBy", "bob")
                        .containsEntry("reason", "missing fraud check"));

        // Given v3 pending again, and v4 approved and active meanwhile
        DefinitionVersion pendingAgain = request(rejected, null);
        activate(4);

        // When bob approves v3
        assertThatThrownBy(() -> approveByBob(pendingAgain))
                // Then it is refused as superseded and v3 stays pending
                .isInstanceOf(VersionSupersededException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("VERSION_SUPERSEDED");
        assertThat(stored(3).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(stored(4).status()).isEqualTo("ACTIVE");
    }

    @Test
    void PRE_037_definition_events_published_in_transaction() {
        // Given v1 active and v2 imported
        activate(1);
        DefinitionVersion v2 = importAs(2);
        engine.events.clear();

        // When v2 is requested, rejected, requested, approved, and then superseded by v3
        DefinitionVersion pending = request(v2, null);
        DefinitionVersion draft = as("bob", APPROVER,
                () -> engine.services.reject(pending.ref(), pending.lockVersion(), "missing fraud check", null));
        DefinitionVersion pendingAgain = request(draft, null);
        DefinitionVersion active = approveByBob(pendingAgain);
        activate(3);

        // Then the events of v2 are recorded in order, each with the full envelope
        List<ProcessEventPublisher.Event> ofV2 = engine.events.stream()
                .filter(e -> Integer.valueOf(2).equals(e.payload().get("version"))).toList();
        assertThat(ofV2).extracting(ProcessEventPublisher.Event::type).containsExactly(
                "processes.definition.publish-requested.v1", "processes.definition.rejected.v1",
                "processes.definition.publish-requested.v1", "processes.definition.approved.v1",
                "processes.definition.retired.v1");
        assertThat(ofV2).allSatisfy(e -> {
            assertThat(e.eventId()).hasSize(26);
            assertThat(e.tenantId()).isEqualTo("bank-a");
            assertThat(e.occurredAt()).isNotNull();
            assertThat(e.payload()).containsEntry("definitionKey", KEY);
        });
        assertThat(ofV2).extracting(ProcessEventPublisher.Event::eventId).doesNotHaveDuplicates();
        assertThat(ofV2.get(3).payload()).containsEntry("approvedBy", "bob").containsEntry("requestedBy", "alice")
                .containsEntry("sourceHash", active.sourceHash()).containsKey("readerVersion");
        assertThat(ofV2.get(4).payload()).containsEntry("supersededBy", 3);
    }

    @Test
    void PRE_037_a_publisher_that_throws_rolls_the_approval_back() {
        // Given a host listener that fails on approvals
        TestEngine failing = TestEngine.replica("pod-1", clock).autoApprove(false).publisher(e -> {
            if (e.type().equals("processes.definition.approved.v1")) {
                throw new IllegalStateException("outbox insert failed");
            }
        }).build();
        failing.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion v1 = failing.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        failing.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion pending = failing.services.requestApproval(v1.ref(), v1.lockVersion(), null, null);

        // When bob approves
        failing.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> failing.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                .isInstanceOf(EventListenerFailedException.class);

        // Then the version stays pending and no approval was written
        failing.identity.as("bank-a", "auditor", "processes:read");
        assertThat(failing.services.find(v1.ref()).orElseThrow().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(failing.services.history(v1.ref())).extracting(DefinitionHistoryEntry::action)
                .containsExactly("IMPORTED", "REQUESTED");
    }

    @Test
    void TR_TEN_01_another_tenants_version_is_not_found_and_nothing_is_written() {
        DefinitionVersion pending = request(importAs(1), null);
        int history = count("SELECT count(*) FROM pre_definition_history");

        engine.identity.as("bank-b", "carol", APPROVER);
        assertThat(engine.services.find(pending.ref())).isEmpty();
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                .isInstanceOf(DefinitionNotFoundException.class);
        assertThatThrownBy(() -> engine.services.reject(pending.ref(), pending.lockVersion(), "not ours", null))
                .isInstanceOf(DefinitionNotFoundException.class);

        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
    }

    @Test
    void TR_IDEM_01_a_stale_lock_version_is_a_conflict_and_a_replayed_key_returns_the_first_answer() {
        DefinitionVersion v1 = importAs(1);
        DefinitionVersion pending = request(v1, null);

        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), v1.lockVersion(), pending.definitionHash(), null, null))
                .isInstanceOf(VersionConflictException.class);
        DefinitionVersion first = engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, "approve-kyc-1");
        DefinitionVersion replay = engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, "approve-kyc-1");

        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_definition_history WHERE action = 'APPROVED'")).isEqualTo(1);
    }

    @Test
    void TR_SEC_04_lifecycle_permissions_are_checked_in_the_service() {
        DefinitionVersion v1 = importAs(1);

        engine.identity.as("bank-a", "teller", "processes:read", "processes:start");
        assertThatThrownBy(() -> engine.services.requestApproval(v1.ref(), v1.lockVersion(), null, null))
                .isInstanceOf(PermissionDeniedException.class);
        DefinitionVersion pending = request(v1, null);
        engine.identity.as("bank-a", "carol", "processes:author", "processes:read");
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void PRE_022_with_maker_checker_off_a_self_approval_is_recorded_as_the_system_approver() {
        TestEngine dev = TestEngine.replica("pod-1", clock).autoApprove(false).makerChecker(false).build();
        dev.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion v1 = dev.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        dev.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion pending = dev.services.requestApproval(v1.ref(), v1.lockVersion(), null, null);

        DefinitionVersion approved = dev.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null);

        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.approvedBy()).isEqualTo("system:dev-auto-approve");
        assertThat(dev.services.history(v1.ref())).last().satisfies(h -> {
            assertThat(h.action()).isEqualTo("APPROVED");
            assertThat(h.actor()).isEqualTo("alice");
        });
    }

    @Test
    void PRE_022_dev_auto_approval_activates_imports_and_retires_the_previous_version() {
        TestEngine dev = TestEngine.replica("pod-1", clock).autoApprove(true).build();
        dev.identity.as("bank-a", "pipeline", PIPELINE);

        DefinitionVersion v1 = dev.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        DefinitionVersion v2 = dev.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(2), null));

        assertThat(v1.status()).isEqualTo("ACTIVE");
        assertThat(v2.status()).isEqualTo("ACTIVE");
        assertThat(v2.approvedBy()).isEqualTo("system:dev-auto-approve");
        dev.identity.as("bank-a", "auditor", "processes:read");
        assertThat(dev.services.find(v1.ref()).orElseThrow().status()).isEqualTo("RETIRED");
    }

    // ---- retirement (PD-101, PD-102, PRE-053) ----------------------------------------------------------------

    @Test
    void PRE_053_retire_stops_new_starts_keeps_running_instances_and_confirms_last_version() {
        // Given v1 in effect with three running instances, and v2 approved for a later date with its job due then
        clock.set(Instant.parse("2027-04-20T09:00:00Z"));
        activate(1);
        List<ProcessInstance> running = List.of(start("LN-0001"), start("LN-0002"), start("LN-0003"));
        DefinitionVersion v2 = approveByBob(request(importAs(2), Instant.parse("2027-05-01T00:00:00Z")));
        DefinitionVersion v1 = stored(1);
        engine.events.clear();

        // Step 1: bob retires v2 (future), with an idempotency key, and replays it
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion retiredV2 = engine.services.retire(v2.ref(), v2.lockVersion(),
                "pricing step points at the wrong rule set", false, "retire-v2");
        DefinitionVersion replay = engine.services.retire(v2.ref(), v2.lockVersion(),
                "pricing step points at the wrong rule set", false, "retire-v2");
        assertThat(retiredV2.status()).isEqualTo("RETIRED");
        assertThat(replay).isEqualTo(retiredV2);
        assertThat(TestEngine.jdbc().sql("SELECT status FROM pre_timer WHERE definition_id = ?").param(v2.id())
                .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(engine.events).singleElement().satisfies(e -> assertThat(e.payload())
                .containsEntry("reason", "pricing step points at the wrong rule set")
                .containsEntry("approvalMode", "single").containsEntry("noVersionInEffect", false));

        // Step 2: v1 without the confirmation
        int history = count("SELECT count(*) FROM pre_definition_history");
        assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), "flawed credit routing", false, null))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.LastVersionInEffectException.class)
                .satisfies(e -> {
                    var last = (io.github.dhluong90.bpm.process.api.LastVersionInEffectException) e;
                    assertThat(last.code()).isEqualTo("LAST_VERSION_IN_EFFECT");
                    assertThat(last.definitionKey()).isEqualTo(KEY);
                    assertThat(last.version()).isEqualTo(1);
                    assertThat(last.setting()).isEqualTo("confirm");
                    assertThat(last.nextEffectiveFrom()).isNull();
                });
        assertThat(stored(1).status()).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);

        // Failure cases: lan (author) is refused, a blank reason is refused; nothing changes
        engine.identity.as("bank-a", "lan", "processes:author", "processes:start", "processes:read");
        assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), "flawed credit routing", true, null))
                .isInstanceOf(PermissionDeniedException.class);
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), " ", true, null))
                .isInstanceOf(InputInvalidException.class);
        assertThat(stored(1).status()).isEqualTo("ACTIVE");

        // Step 3: v1 with the confirmation
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion retiredV1 = engine.services.retire(v1.ref(), v1.lockVersion(), "flawed credit routing", true,
                null);
        assertThat(retiredV1.status()).isEqualTo("RETIRED");
        assertThat(TestEngine.jdbc().sql("SELECT comment || ' ' || (details->>'approvalMode') || ' ' "
                + "|| (details->>'noVersionInEffect') FROM pre_definition_history WHERE action = 'RETIRED' "
                + "AND definition_id = ?").param(v1.id()).query(String.class).single())
                .isEqualTo("flawed credit routing single true");
        assertThat(engine.events).last().satisfies(e -> assertThat(e.payload()).containsEntry("noVersionInEffect", true));

        // Steps 4 and 5: running instances stay on v1 and advance; a new start finds no version
        engine.identity.as("bank-a", "officer", "processes:task:work", "processes:read").roles = java.util.Set.of(
                "KYC_OFFICER");
        ProcessInstance.Task task = running.get(0).tasks().get(0);
        long claimed = engine.services.claim(task.id(), task.lockVersion(), "claim-ln-0001").lockVersion();
        ProcessInstance advanced = engine.services.complete(task.id(), Map.of("approved", true), claimed,
                "complete-ln-0001");
        assertThat(advanced.status()).isEqualTo("COMPLETED");
        assertThat(running).allSatisfy(i -> assertThat(i.definitionVersion()).isEqualTo(1));
        assertThat(count("SELECT count(*) FROM pre_instance WHERE definition_ver = 1 AND status = 'RUNNING'"))
                .isEqualTo(2);
        engine.identity.as("bank-a", "lan", "processes:author", "processes:start", "processes:read");
        assertThatThrownBy(() -> engine.services.start(new StartRequest(KEY, "LN-0004", Map.of(), null)))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.NoEffectiveVersionException.class);

        // Step 6: on 1 May the cancelled job retires or starts nothing
        clock.set(Instant.parse("2027-05-01T00:01:00Z"));
        assertThat(engine.jobs.pollOnce()).isZero();
        assertThat(stored(2).status()).isEqualTo("RETIRED");
        assertThat(count("SELECT count(*) FROM pre_definition_history WHERE action = 'RETIRED'")).isEqualTo(2);
    }

    @Test
    void PRE_053_a_version_that_just_took_effect_is_the_last_in_effect_and_its_superseded_versions_retire_with_it() {
        // Given v1 active, and v2 approved for 1 May; on 1 May v2 is effective but its job has not fired, so v1 is
        // still ACTIVE
        clock.set(Instant.parse("2027-04-20T09:00:00Z"));
        activate(1);
        DefinitionVersion v2 = approveByBob(request(importAs(2), Instant.parse("2027-05-01T00:00:00Z")));
        clock.set(Instant.parse("2027-05-01T00:00:30Z"));
        int history = count("SELECT count(*) FROM pre_definition_history");

        // When bob retires v2 without the confirmation
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.retire(v2.ref(), v2.lockVersion(), "wrong rule set", false, null))
                // Then it is the last version in effect: 409, and the refusal writes nothing
                .isInstanceOf(io.github.dhluong90.bpm.process.api.LastVersionInEffectException.class);
        assertThat(stored(1).status()).isEqualTo("ACTIVE");
        assertThat(stored(2).status()).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
        assertThat(start("LN-0500").definitionVersion()).isEqualTo(2);

        // When bob confirms
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion retired = engine.services.retire(v2.ref(), v2.lockVersion(), "wrong rule set", true, null);

        // Then v2 is retired by bob and v1 as superseded by v2, in the same transaction; a new start gets 404
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.retiredBy()).isEqualTo("bob");
        DefinitionVersion v1 = stored(1);
        assertThat(v1.status()).isEqualTo("RETIRED");
        assertThat(v1.retiredBy()).isEqualTo("system:effective-date");
        assertThat(v1.supersededBy()).isEqualTo(2);
        assertThatThrownBy(() -> start("LN-0501"))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.NoEffectiveVersionException.class);

        // And v2's job later changes nothing
        assertThat(engine.jobs.pollOnce()).isZero();
        assertThat(stored(1).status()).isEqualTo("RETIRED");
        assertThat(stored(2).status()).isEqualTo("RETIRED");
    }

    @Test
    void PRE_053_with_retire_last_version_refuse_the_last_version_cannot_be_retired_even_when_confirmed() {
        TestEngine strict = TestEngine.replica("pod-1", clock).autoApprove(false)
                .retireLastVersion(io.github.dhluong90.bpm.process.internal.service.EngineSettings.RetireLastVersionRule
                        .REFUSE).build();
        strict.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion v1 = strict.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        strict.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion pending = strict.services.requestApproval(v1.ref(), v1.lockVersion(), null, null);
        strict.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion active = strict.services.approve(pending.ref(), pending.lockVersion(), pending.definitionHash(), null, null);

        assertThatThrownBy(() -> strict.services.retire(active.ref(), active.lockVersion(), "stop it", true, null))
                .isInstanceOf(io.github.dhluong90.bpm.process.api.LastVersionInEffectException.class)
                .satisfies(e -> assertThat(((io.github.dhluong90.bpm.process.api.LastVersionInEffectException) e)
                        .setting()).isEqualTo("refuse"));
        assertThat(strict.services.find(v1.ref()).orElseThrow().status()).isEqualTo("ACTIVE");
    }

    @Test
    void PRE_053_retiring_an_active_version_needs_processes_approve() {
        DefinitionVersion v1 = activate(1);

        engine.identity.as("bank-a", "carol", "processes:author", "processes:read");
        assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), "stop it", true, null))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(stored(1).status()).isEqualTo("ACTIVE");
    }

    // ---- a superseded version is not retired by hand (PD-102, #297) ------------------------------------------

    /** v1 active, v2 approved for 1 May; at 1 May 00:00:30 v2 is in effect and its job has not run, so v1 is ACTIVE. */
    private DefinitionVersion v2InEffectWithJobPending() {
        clock.set(Instant.parse("2027-04-20T09:00:00Z"));
        activate(1);
        DefinitionVersion v2 = approveByBob(request(importAs(2), Instant.parse("2027-05-01T00:00:00Z")));
        clock.set(Instant.parse("2027-05-01T00:00:30Z"));
        return v2;
    }

    @Test
    void PRE_053_a_version_a_newer_version_in_effect_supersedes_cannot_be_retired_by_hand() {
        // Given v2 in effect and its effective-date job not yet run, so v1 is still ACTIVE
        v2InEffectWithJobPending();
        DefinitionVersion v1 = stored(1);
        assertThat(v1.status()).isEqualTo("ACTIVE");
        int history = count("SELECT count(*) FROM pre_definition_history");
        int idempotency = count("SELECT count(*) FROM pre_idempotency");
        engine.events.clear();

        // When bob retires v1 by hand, with and without the confirmation
        engine.identity.as("bank-a", "bob", APPROVER);
        for (boolean confirm : new boolean[] {false, true}) {
            assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), "flawed credit routing",
                    confirm, "retire-v1-" + confirm))
                    // Then 409 INVALID_STATE, naming v2
                    .isInstanceOf(io.github.dhluong90.bpm.process.api.InvalidStateException.class)
                    .satisfies(e -> {
                        assertThat(((ProcessException) e).code()).isEqualTo("INVALID_STATE");
                        assertThat(e.getMessage()).contains(KEY + " v1").contains("v2 is in effect and supersedes it");
                    });
        }

        // And nothing is written: no history row, no event, no stored answer, no change to either version
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
        assertThat(count("SELECT count(*) FROM pre_idempotency")).isEqualTo(idempotency);
        assertThat(engine.events).isEmpty();
        DefinitionVersion after = stored(1);
        assertThat(after.status()).isEqualTo("ACTIVE");
        assertThat(after.lockVersion()).isEqualTo(v1.lockVersion());
        assertThat(after.retiredBy()).isNull();
        assertThat(stored(2).status()).isEqualTo("ACTIVE");
    }

    @Test
    void PRE_053_a_superseded_version_is_still_retired_by_the_effective_date_job() {
        // Given v2 in effect, its job pending, and a refused manual retirement of v1
        v2InEffectWithJobPending();
        DefinitionVersion v1 = stored(1);
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.retire(v1.ref(), v1.lockVersion(), "flawed credit routing", true,
                null)).isInstanceOf(io.github.dhluong90.bpm.process.api.InvalidStateException.class);

        // When the effective-date job runs
        assertThat(engine.jobs.pollOnce()).isEqualTo(1);

        // Then v1 is RETIRED by the system as superseded by v2, with one RETIRED history row and no reason
        DefinitionVersion retired = stored(1);
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.retiredBy()).isEqualTo("system:effective-date");
        assertThat(retired.supersededBy()).isEqualTo(2);
        assertThat(TestEngine.jdbc().sql("SELECT actor_id || ' ' || coalesce(comment, '-') FROM pre_definition_history "
                + "WHERE action = 'RETIRED' AND definition_id = ?").param(v1.id()).query(String.class).single())
                .isEqualTo("system:effective-date -");
        assertThat(stored(2).status()).isEqualTo("ACTIVE");
    }

    @Test
    void PRE_053_the_version_in_effect_is_still_retired_as_before_and_its_superseded_versions_with_it() {
        // Given v2 in effect, its job pending, v1 still ACTIVE
        DefinitionVersion v2 = v2InEffectWithJobPending();

        // When bob retires v2, the version in effect, with the confirmation (PD-101)
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion retired = engine.services.retire(v2.ref(), v2.lockVersion(), "wrong rule set", true, null);

        // Then the retirement goes through as before: v2 by bob, v1 by the system as superseded
        assertThat(retired.retiredBy()).isEqualTo("bob");
        assertThat(stored(1).retiredBy()).isEqualTo("system:effective-date");
        assertThat(stored(1).supersededBy()).isEqualTo(2);
    }
}
