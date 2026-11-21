package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ContentChangedException;
import io.github.dhluong90.bpm.process.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
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
}
