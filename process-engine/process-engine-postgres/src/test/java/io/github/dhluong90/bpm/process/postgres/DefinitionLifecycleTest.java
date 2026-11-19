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
        return as("bob", APPROVER, () -> engine.services.approve(v.ref(), v.lockVersion(), "checked", null));
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
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), null, "k-alice"))
                // Then it is refused with the REST code and writes nothing
                .isInstanceOf(SelfApprovalForbiddenException.class)
                .extracting(e -> ((ProcessException) e).code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(stored(2).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(stored(2).lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(historyBefore);
        assertThat(engine.events).hasSize(eventsBefore);

        // When the importer approves (default rule: makers)
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:approve", "processes:read");
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), null, null))
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
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), null, null))
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
        assertThatThrownBy(() -> relaxed.services.approve(pending.ref(), pending.lockVersion(), null, null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        relaxed.identity.as("bank-a", "pipeline", "processes:deploy", "processes:approve", "processes:read");
        DefinitionVersion approved = relaxed.services.approve(pending.ref(), pending.lockVersion(), null, null);

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
        ProcessInstance completed = engine.services.complete(review.id(), Map.of("approved", true), review.lockVersion(),
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
        assertThatThrownBy(() -> failing.services.approve(pending.ref(), pending.lockVersion(), null, null))
                .isInstanceOf(EventListenerFailedException.class);

        // Then the version stays pending and no approval was written
        failing.identity.as("bank-a", "auditor", "processes:read");
        assertThat(failing.services.find(v1.ref()).orElseThrow().status()).isEqualTo("PENDING_APPROVAL");
        assertThat(failing.services.history(v1.ref())).extracting(DefinitionHistoryEntry::action)
                .containsExactly("IMPORTED", "REQUESTED");
    }

    @Test
    void another_tenants_version_is_not_found_and_nothing_is_written() {
        DefinitionVersion pending = request(importAs(1), null);
        int history = count("SELECT count(*) FROM pre_definition_history");

        engine.identity.as("bank-b", "carol", APPROVER);
        assertThat(engine.services.find(pending.ref())).isEmpty();
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), null, null))
                .isInstanceOf(DefinitionNotFoundException.class);
        assertThatThrownBy(() -> engine.services.reject(pending.ref(), pending.lockVersion(), "not ours", null))
                .isInstanceOf(DefinitionNotFoundException.class);

        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(count("SELECT count(*) FROM pre_definition_history")).isEqualTo(history);
    }

    @Test
    void a_stale_lock_version_is_a_conflict_and_a_replayed_key_returns_the_first_answer() {
        DefinitionVersion v1 = importAs(1);
        DefinitionVersion pending = request(v1, null);

        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), v1.lockVersion(), null, null))
                .isInstanceOf(VersionConflictException.class);
        DefinitionVersion first = engine.services.approve(pending.ref(), pending.lockVersion(), null, "approve-kyc-1");
        DefinitionVersion replay = engine.services.approve(pending.ref(), pending.lockVersion(), null, "approve-kyc-1");

        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM pre_definition_history WHERE action = 'APPROVED'")).isEqualTo(1);
    }

    @Test
    void permissions_are_checked_in_the_service() {
        DefinitionVersion v1 = importAs(1);

        engine.identity.as("bank-a", "teller", "processes:read", "processes:start");
        assertThatThrownBy(() -> engine.services.requestApproval(v1.ref(), v1.lockVersion(), null, null))
                .isInstanceOf(PermissionDeniedException.class);
        DefinitionVersion pending = request(v1, null);
        engine.identity.as("bank-a", "carol", "processes:author", "processes:read");
        assertThatThrownBy(() -> engine.services.approve(pending.ref(), pending.lockVersion(), null, null))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void PRE_022_with_maker_checker_off_a_self_approval_is_recorded_as_the_system_approver() {
        TestEngine dev = TestEngine.replica("pod-1", clock).autoApprove(false).makerChecker(false).build();
        dev.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion v1 = dev.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(1), null));
        dev.identity.as("bank-a", "alice", AUTHOR);
        DefinitionVersion pending = dev.services.requestApproval(v1.ref(), v1.lockVersion(), null, null);

        DefinitionVersion approved = dev.services.approve(pending.ref(), pending.lockVersion(), null, null);

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
}
