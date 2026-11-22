package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ChangeSetTruncatedException;
import io.github.dhluong90.bpm.process.api.ContentChangedException;
import io.github.dhluong90.bpm.process.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.GraphDiff;
import io.github.dhluong90.bpm.process.api.GraphDiffPage;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-177 (PD-139, #406) on PostgreSQL: approving a process definition version names the hash of the content the
 * approver reviewed. A KYC onboarding version is imported by the pipeline, requested by alice and approved by bob.
 */
class ReviewedContentApprovalTest {

    private static final String KEY = "kyc-onboarding";
    private static final String[] PIPELINE = {"processes:deploy", "processes:read", "processes:start"};
    private static final String[] AUTHOR = {"processes:author", "processes:read"};
    private static final String[] APPROVER = {"processes:approve", "processes:read"};

    private final TestClock clock = TestClock.at("2027-04-05T03:00:00Z");
    private TestEngine engine;

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).autoApprove(false).build();
    }

    private static byte[] kyc(String step) {
        return new String(TestEngine.resource("/bpmn/kyc-onboarding.bpmn"), StandardCharsets.UTF_8)
                .replace("Review identity documents", step).getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionVersion pending(String step) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion draft = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, kyc(step),
                null));
        engine.identity.as("bank-a", "alice", AUTHOR);
        return engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);
    }

    private DefinitionVersion stored(int version) {
        engine.identity.as("bank-a", "auditor", "processes:read");
        return engine.services.find(DefinitionRef.of(KEY, version)).orElseThrow();
    }

    /** Everything an approval writes: the version row's lock version, history rows, events and effective-date jobs. */
    private Map<String, Object> footprint() {
        return TestEngine.jdbc().sql("SELECT (SELECT max(lock_version) FROM pre_definition) AS locks, "
                + "(SELECT count(*) FROM pre_definition_history) AS history, "
                + "(SELECT count(*) FROM pre_timer) AS jobs, "
                + "(SELECT count(*) FROM pre_definition WHERE status = 'ACTIVE') AS active").query().singleRow();
    }

    private long approvedEvents() {
        return engine.events.stream().map(ProcessEventPublisher.Event::type)
                .filter("processes.definition.approved.v1"::equals).count();
    }

    @Test
    void PRE_177_an_approval_with_the_reviewed_hash_publishes_the_version_and_records_the_hash() {
        // Given kyc-onboarding v1 pending, whose content bob reviewed with hash H
        DefinitionVersion v1 = pending("Review identity documents");
        String reviewed = v1.definitionHash();

        // When bob approves naming H
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion active = engine.services.approve(v1.ref(), v1.lockVersion(), reviewed, "checked", "ap-1");

        // Then it is active, and the history row and the event name the content reviewed
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(TestEngine.jdbc().sql("SELECT details->>'reviewedDefinitionHash' FROM pre_definition_history "
                + "WHERE action = 'APPROVED'").query(String.class).single()).isEqualTo(reviewed);
        assertThat(engine.events).filteredOn(e -> e.type().equals("processes.definition.approved.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("reviewedDefinitionHash", reviewed));
    }

    @Test
    void PRE_177_an_approval_of_content_changed_after_review_is_refused_naming_the_current_hash_and_writes_nothing() {
        // Given bob reviewed v1 with hash H, then its content was replaced and requested again (as a draft edit
        // followed by a new request would), so the stored version now has hash H2
        DefinitionVersion v1 = pending("Review identity documents");
        String reviewed = v1.definitionHash();
        DefinitionVersion other = pending("Review identity documents and proof of address");
        TestEngine.jdbc().sql("UPDATE pre_definition d SET definition_json = o.definition_json, definition_hash = "
                + "o.definition_hash, source_document = o.source_document, source_hash = o.source_hash FROM "
                + "pre_definition o WHERE d.version_num = 1 AND o.version_num = 2").update();
        Map<String, Object> before = footprint();

        // When bob approves v1 naming H, at the current lock version
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), reviewed, null, "ap-2"))
                // Then it is refused with the hash stored now, and nothing is written, published or recorded
                .isInstanceOfSatisfying(ContentChangedException.class, e -> {
                    assertThat(e.code()).isEqualTo("CONTENT_CHANGED");
                    assertThat(e.currentDefinitionHash()).isEqualTo(other.definitionHash());
                    assertThat(e.currentLockVersion()).isEqualTo(v1.lockVersion());
                });
        assertThat(footprint()).isEqualTo(before);
        assertThat(approvedEvents()).isZero();
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");

        // And once bob has reviewed the content stored now, the approval with its hash is accepted
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThat(engine.services.approve(v1.ref(), v1.lockVersion(), other.definitionHash(), null, "ap-3").status())
                .isEqualTo("ACTIVE");
    }

    @Test
    void PRE_177_an_approval_without_a_hash_is_refused_while_the_setting_is_on() {
        // Given v1 pending, and the default approval.require-content-hash: true
        DefinitionVersion v1 = pending("Review identity documents");
        Map<String, Object> before = footprint();

        // When bob approves without naming the content, through the old signature and with an explicit null
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), "checked", null))
                .isInstanceOf(ContentHashRequiredException.class)
                .extracting(e -> ((ContentHashRequiredException) e).code()).isEqualTo("CONTENT_HASH_REQUIRED");
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), null, "checked", null))
                .isInstanceOf(ContentHashRequiredException.class);
        // And a value that is not a hash is refused as input
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), "ABC", null, null))
                .isInstanceOf(InputInvalidException.class);

        // Then nothing is written
        assertThat(footprint()).isEqualTo(before);
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void PRE_177_with_the_dev_relaxation_an_approval_without_a_hash_is_accepted() {
        // Given approval.require-content-hash: false (accepted on the dev profile only, see the starter tests)
        engine = TestEngine.replica("pod-1", clock).autoApprove(false).requireContentHash(false).build();
        DefinitionVersion v1 = pending("Review identity documents");

        // When bob approves without a hash
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion active = engine.services.approve(v1.ref(), v1.lockVersion(), "checked", null);

        // Then it is active and the history records no reviewed hash
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(TestEngine.jdbc().sql("SELECT details->>'reviewedDefinitionHash' IS NOT NULL FROM pre_definition_history "
                + "WHERE action = 'APPROVED'").query(Boolean.class).single()).isFalse();
    }

    @Test
    void PRE_177_another_tenants_version_is_refused_and_nothing_is_written() {
        // Given bank-a's v1 pending with hash H
        DefinitionVersion v1 = pending("Review identity documents");
        Map<String, Object> before = footprint();

        // When a bank-b approver approves it, naming H
        engine.identity.as("bank-b", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null,
                "ap-b")).isInstanceOf(DefinitionNotFoundException.class);

        // Then nothing is written
        assertThat(footprint()).isEqualTo(before);
        assertThat(stored(1).status()).isEqualTo("PENDING_APPROVAL");
    }

    /** kyc-onboarding with 400 review steps in a row; {@code label} and {@code role} name and assign every one. */
    private static byte[] longKyc(String label, String role) {
        StringBuilder steps = new StringBuilder("<bpmn:startEvent id=\"start\" name=\"Application received\"/>");
        String previous = "start";
        for (int i = 0; i < 400; i++) {
            String id = "review-" + String.format("%03d", i);
            steps.append("<bpmn:userTask id=\"%s\" name=\"%s %d\" horus:assignee=\"role:%s\" horus:resultVariable=\"%s%d\"/>"
                    .formatted(id, label, i, role, label.toLowerCase(java.util.Locale.ROOT), i));
            steps.append("<bpmn:sequenceFlow id=\"%s__%s\" sourceRef=\"%s\" targetRef=\"%s\"/>"
                    .formatted(previous, id, previous, id));
            previous = id;
        }
        steps.append("<bpmn:endEvent id=\"end\" name=\"Account opened\"/>");
        steps.append("<bpmn:sequenceFlow id=\"last__end\" sourceRef=\"%s\" targetRef=\"end\"/>".formatted(previous));
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><bpmn:definitions "
                + "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" "
                + "id=\"Definitions_kyc-onboarding\" targetNamespace=\"urn:horus-bpm:examples\" "
                + "expressionLanguage=\"urn:horus-bpm:expr:v1\"><bpmn:process id=\"kyc-onboarding\" name=\"KYC onboarding\" "
                + "isExecutable=\"true\">" + steps + "</bpmn:process></bpmn:definitions>").getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionVersion pendingDocument(byte[] document) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        DefinitionVersion draft = engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, document,
                null));
        engine.identity.as("bank-a", "alice", AUTHOR);
        return engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);
    }

    @Test
    void PRE_177_a_truncated_diff_exposes_no_approvable_hash_and_an_approval_against_it_is_refused() {
        // Given v1 active, and v2 pending with every one of 400 steps renamed, reassigned and given another result
        // variable: 1,200 changed values, more than a diff lists
        DefinitionVersion v1 = pendingDocument(longKyc("Review", "KYC_OFFICER"));
        engine.identity.as("bank-a", "bob", APPROVER);
        engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null, "ap-t1");
        DefinitionVersion v2 = pendingDocument(longKyc("Check", "RISK_OFFICER"));

        // When bob reads the diff against the effective version
        engine.identity.as("bank-a", "bob", APPROVER);
        GraphDiff diff = engine.services.diffFromEffective(v2.ref());

        // Then it is cut off and offers no hash to approve
        assertThat(diff.truncated()).isTrue();
        assertThat(diff.nodesChanged()).hasSize(GraphDiff.MAX_CHANGES);
        assertThat(diff.approvableHash()).isNull();

        // And an approval naming v2's hash anyway is refused, writing, publishing and recording nothing
        Map<String, Object> before = footprint();
        long events = approvedEvents();
        assertThatThrownBy(() -> engine.services.approve(v2.ref(), v2.lockVersion(), v2.definitionHash(), null,
                "ap-t2"))
                .isInstanceOfSatisfying(ChangeSetTruncatedException.class,
                        e -> assertThat(e.code()).isEqualTo("CHANGE_SET_TRUNCATED"));
        assertThat(footprint()).isEqualTo(before);
        assertThat(approvedEvents()).isEqualTo(events);
        assertThat(stored(2).status()).isEqualTo("PENDING_APPROVAL");
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_idempotency WHERE idempotency_key = 'ap-t2'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void PRE_177_a_replay_with_another_hash_under_the_same_key_is_a_key_reuse() {
        // Given bob approved v1 naming H under key ap-9
        DefinitionVersion v1 = pending("Review identity documents");
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion first = engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null, "ap-9");

        // When the same request is repeated, and then the same key names other content
        DefinitionVersion replay = engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null,
                "ap-9");

        // Then the repeat replays the first answer, and the changed request is refused
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> engine.services.approve(v1.ref(), v1.lockVersion(), "0".repeat(64), null, "ap-9"))
                .extracting(e -> ((io.github.dhluong90.bpm.process.api.ProcessException) e).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(approvedEvents()).isEqualTo(1);
    }

    /** v1 of 400 steps active, v2 with all 1,200 values changed pending: a change set no unpaged diff lists in full. */
    private DefinitionVersion oversizeV2() {
        DefinitionVersion v1 = pendingDocument(longKyc("Review", "KYC_OFFICER"));
        engine.identity.as("bank-a", "bob", APPROVER);
        engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null, "ap-base");
        return pendingDocument(longKyc("Check", "RISK_OFFICER"));
    }

    /** Reads every page of the diff against the effective version and returns the last one. */
    private GraphDiffPage readAllPages(DefinitionVersion v2, int limit) {
        engine.identity.as("bank-a", "bob", APPROVER);
        GraphDiffPage page = engine.services.diffPageFromEffective(v2.ref(), limit, null);
        while (!page.last()) {
            page = engine.services.diffPageFromEffective(v2.ref(), limit, page.nextCursor());
        }
        return page;
    }

    @Test
    void PRE_201_the_whole_change_set_is_read_page_by_page_and_the_hashes_come_with_the_last_page() {
        // Given v2 differs from the effective v1 in 1,200 values
        DefinitionVersion v2 = oversizeV2();
        engine.identity.as("bank-a", "bob", APPROVER);

        // When bob reads the diff 500 changes at a time
        GraphDiffPage first = engine.services.diffPageFromEffective(v2.ref(), 500, null);
        GraphDiffPage second = engine.services.diffPageFromEffective(v2.ref(), 500, first.nextCursor());
        GraphDiffPage third = engine.services.diffPageFromEffective(v2.ref(), 500, second.nextCursor());

        // Then three pages hold 500, 500 and 200 changes, none truncated, in sort order without a gap
        assertThat(first.total()).isEqualTo(1200);
        assertThat(first.slice().nodesChanged()).hasSize(500);
        assertThat(second.slice().nodesChanged()).hasSize(500);
        assertThat(third.slice().nodesChanged()).hasSize(200);
        List<String> keys = java.util.stream.Stream.of(first, second, third)
                .flatMap(p -> p.slice().nodesChanged().stream()).map(c -> c.id() + " " + c.path()).toList();
        assertThat(keys).hasSize(1200).doesNotHaveDuplicates().isSorted();
        assertThat(first.slice().truncated()).isFalse();
        // And the hashes are on the last page only
        assertThat(first.approvableHash()).isNull();
        assertThat(first.changeSetHash()).isNull();
        assertThat(second.changeSetHash()).isNull();
        assertThat(third.approvableHash()).isEqualTo(v2.definitionHash());
        assertThat(third.changeSetHash()).matches("[0-9a-f]{64}");
        // And the whole-set hash does not depend on the page size
        assertThat(readAllPages(v2, 77).changeSetHash()).isEqualTo(third.changeSetHash());
    }

    @Test
    void PRE_201_a_cursor_this_change_set_did_not_issue_and_a_page_size_out_of_range_are_refused() {
        DefinitionVersion v2 = oversizeV2();
        engine.identity.as("bank-a", "bob", APPROVER);
        GraphDiffPage first = engine.services.diffPageFromEffective(v2.ref(), 500, null);

        assertThatThrownBy(() -> engine.services.diffPageFromEffective(v2.ref(), 500, "not-a-cursor"))
                .isInstanceOf(QueryInvalidException.class);
        // A cursor that names another offset than the digest was issued for is refused: pages cannot be skipped
        String forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("1000:" + new String(java.util.Base64.getUrlDecoder().decode(first.nextCursor()),
                        StandardCharsets.UTF_8).split(":")[1]).getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> engine.services.diffPageFromEffective(v2.ref(), 500, forged))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.services.diffPageFromEffective(v2.ref(), 501, null))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> engine.services.diffPageFromEffective(v2.ref(), 0, null))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_202_a_change_set_over_the_limit_is_approved_with_the_hash_of_the_whole_set_and_records_it() {
        // Given v2 with 1,200 changes, and bob has read every page
        DefinitionVersion v2 = oversizeV2();
        GraphDiffPage last = readAllPages(v2, 500);

        // When bob approves naming v2's hash and the whole-set hash
        engine.identity.as("bank-a", "bob", APPROVER);
        DefinitionVersion active = engine.services.approve(v2.ref(), v2.lockVersion(), last.approvableHash(),
                last.changeSetHash(), "read all three pages", "ap-set-1");

        // Then it is active outside dev, and the history row and the event name the whole set reviewed
        assertThat(active.status()).isEqualTo("ACTIVE");
        assertThat(TestEngine.jdbc().sql("SELECT h.details->>'reviewedChangeSetHash' FROM pre_definition_history h "
                + "JOIN pre_definition d ON d.tenant_id = h.tenant_id AND d.id = h.definition_id "
                + "WHERE h.action = 'APPROVED' AND d.version_num = 2").query(String.class).single())
                .isEqualTo(last.changeSetHash());
        assertThat(engine.events).filteredOn(e -> e.type().equals("processes.definition.approved.v1")).last()
                .satisfies(e -> assertThat(e.payload()).containsEntry("reviewedChangeSetHash", last.changeSetHash()));
    }

    @Test
    void PRE_202_a_change_set_hash_that_is_not_the_whole_set_now_is_refused_and_writes_nothing() {
        // Given v2 with 1,200 changes, read in full
        DefinitionVersion v2 = oversizeV2();
        GraphDiffPage last = readAllPages(v2, 500);
        Map<String, Object> before = footprint();
        long events = approvedEvents();

        // When bob approves with a hash of some other change set, then with the right one but at a stale lock version
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v2.ref(), v2.lockVersion(), last.approvableHash(),
                "0".repeat(64), null, "ap-set-2"))
                // Then it is CONTENT_CHANGED naming the hash of the change set now, and nothing is written
                .isInstanceOfSatisfying(ContentChangedException.class, e -> {
                    assertThat(e.currentChangeSetHash()).isEqualTo(last.changeSetHash());
                    assertThat(e.currentDefinitionHash()).isEqualTo(v2.definitionHash());
                });
        assertThat(footprint()).isEqualTo(before);
        assertThat(approvedEvents()).isEqualTo(events);
        assertThat(stored(2).status()).isEqualTo("PENDING_APPROVAL");
        // And not 64 hexadecimal characters is refused as input
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v2.ref(), v2.lockVersion(), last.approvableHash(), "ABC",
                null, null)).isInstanceOf(InputInvalidException.class);
    }

    @Test
    void PRE_202_an_unpaged_read_and_an_approval_without_the_set_hash_keep_refusing_a_truncated_set() {
        // Given v2 with 1,200 changes
        DefinitionVersion v2 = oversizeV2();

        // When bob approves naming only v2's hash (what the unpaged diff could never give him)
        engine.identity.as("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> engine.services.approve(v2.ref(), v2.lockVersion(), v2.definitionHash(), null,
                "ap-set-3")).isInstanceOf(ChangeSetTruncatedException.class);

        // Then the unpaged diff is still cut off at 1,000 values with no approvable hash
        GraphDiff diff = engine.services.diffFromEffective(v2.ref());
        assertThat(diff.truncated()).isTrue();
        assertThat(diff.approvableHash()).isNull();
    }

    @Test
    void PRE_202_a_change_set_within_the_limit_needs_no_set_hash_and_checks_one_when_sent() {
        // Given v1 active and v2 with one renamed step, pending
        DefinitionVersion v1 = pending("Review identity documents");
        engine.identity.as("bank-a", "bob", APPROVER);
        engine.services.approve(v1.ref(), v1.lockVersion(), v1.definitionHash(), null, "ap-small-1");
        DefinitionVersion v2 = pending("Review identity documents and proof of address");
        engine.identity.as("bank-a", "bob", APPROVER);
        GraphDiffPage page = engine.services.diffPageFromEffective(v2.ref(), null, null);
        assertThat(page.last()).isTrue();

        // When bob names a wrong set hash, then none
        assertThatThrownBy(() -> engine.services.approve(v2.ref(), v2.lockVersion(), v2.definitionHash(),
                "f".repeat(64), null, "ap-small-2")).isInstanceOf(ContentChangedException.class);
        DefinitionVersion active = engine.services.approve(v2.ref(), v2.lockVersion(), v2.definitionHash(), null,
                "ap-small-3");

        // Then the version is approved on its definition hash alone
        assertThat(active.status()).isEqualTo("ACTIVE");
    }
}
