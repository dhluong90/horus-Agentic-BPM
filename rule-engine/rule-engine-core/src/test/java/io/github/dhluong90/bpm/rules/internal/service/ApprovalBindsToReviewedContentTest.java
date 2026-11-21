package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ContentChangedException;
import io.github.dhluong90.bpm.rules.api.ContentHashRequiredException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An approval or a rejection binds to the content the approver reviewed (RLE-084, #275), through the public services
 * on the in-memory stores. At bank-a, lan authors and requests publication of {@code card-fee} v1 and bob approves.
 * lan withdraws the request, changes the R2 fee from 10,000 VND to 12,000 VND and requests again under the same
 * number, so the lock version moves and a client that refreshed it still holds a decision made on the first fee.
 */
class ApprovalBindsToReviewedContentTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    VersionService versions;
    RuleSetVersion reviewed;

    @BeforeEach
    void pendingVersion() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withDraftAuthoring(true)), stores, stores, stores);
        engines.cursorKeys().load();
        versions = engines.versionService();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        reviewed = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
    }

    /** lan takes the request back, changes the R2 fee and requests again; returns what bob would now find. */
    RuleSetVersion changedAfterReview() {
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion draft = versions.withdraw("card-fee", 1,
                new WriteOptions("wd-1", reviewed.lockVersion(), "fee corrected"));
        byte[] changed = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"12000 VND\" }").getBytes(StandardCharsets.UTF_8);
        RuleSetVersion replaced = versions.replaceDraft("card-fee", 1, ImportRequest.yaml(changed),
                new WriteOptions("rp-1", draft.lockVersion(), null));
        RuleSetVersion again = versions.requestPublish("card-fee", 1, new WriteOptions("rq-2", replaced.lockVersion(), null));
        caller.become("bank-a", "bob", APPROVER);
        return again;
    }

    void assertNothingWritten(RuleSetVersion stored, int history, int events, int idempotency) {
        RuleSetVersion now = versions.find("card-fee", 1, false).orElseThrow();
        assertThat(now.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(now.lockVersion()).isEqualTo(stored.lockVersion());
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.idempotency).hasSize(idempotency);
    }

    @Test
    void RLE_084_an_approval_with_the_hash_of_the_current_content_publishes_as_before() {
        // When bob approves with the lock version and the canonical hash of what he reviewed
        RuleSetVersion published = versions.approve("card-fee", 1,
                new WriteOptions("ap-1", reviewed.lockVersion(), "Fees checked").expectingContent(reviewed.canonicalHash()));

        // Then v1 is published, and the same request with the same key replays the answer
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        int events = stores.outbox.size();
        RuleSetVersion replay = versions.approve("card-fee", 1,
                new WriteOptions("ap-1", reviewed.lockVersion(), "Fees checked").expectingContent(reviewed.canonicalHash()));
        assertThat(replay).isEqualTo(published);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_084_a_decision_without_a_hash_is_refused_by_default_and_writes_nothing() {
        int history = stores.history.size();
        int events = stores.outbox.size();
        int idempotency = stores.idempotency.size();

        // When bob approves, or rejects, without naming the content he reviewed
        assertThatThrownBy(() -> versions.approve("card-fee", 1, new WriteOptions("ap-1", reviewed.lockVersion(), null)))
                // Then it is 400 CONTENT_HASH_REQUIRED and nothing is written
                .isInstanceOfSatisfying(ContentHashRequiredException.class, e -> {
                    assertThat(e.code()).isEqualTo("CONTENT_HASH_REQUIRED");
                    assertThat(e.getMessage()).contains("expectedCanonicalHash");
                });
        assertThatThrownBy(() -> versions.reject("card-fee", 1,
                new WriteOptions("rj-1", reviewed.lockVersion(), "R2 waiver needs minutes")))
                .isInstanceOf(ContentHashRequiredException.class);
        assertNothingWritten(reviewed, history, events, idempotency);
    }

    @Test
    void RLE_084_a_decision_without_a_hash_is_allowed_when_the_requirement_is_off() {
        // Given a host that turned approval.require-content-hash off (dev only, PD-139)
        VersionService relaxed = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withDraftAuthoring(true).withRequireContentHash(false)),
                stores, stores, stores).versionService();

        // When bob approves without naming content
        RuleSetVersion published = relaxed.approve("card-fee", 1, new WriteOptions("ap-1", reviewed.lockVersion(), null));

        // Then it publishes on the lock version alone
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_084_a_version_withdrawn_replaced_and_requested_again_is_refused_with_the_old_hash_even_with_the_new_lock() {
        // Given v1 changed after bob reviewed it: new fee, same number, new lock version
        RuleSetVersion changed = changedAfterReview();
        assertThat(changed.canonicalHash()).isNotEqualTo(reviewed.canonicalHash());
        assertThat(changed.lockVersion()).isNotEqualTo(reviewed.lockVersion());
        int history = stores.history.size();
        int events = stores.outbox.size();
        int idempotency = stores.idempotency.size();

        // When bob approves with the new lock version and the hash of the content he read
        assertThatThrownBy(() -> versions.approve("card-fee", 1,
                new WriteOptions("ap-2", changed.lockVersion(), "ok").expectingContent(reviewed.canonicalHash())))
                // Then it is 409 CONTENT_CHANGED, never VERSION_CONFLICT (a client would refresh and retry that),
                // naming the current hash and lock version, and nothing is written
                .isInstanceOfSatisfying(ContentChangedException.class, e -> {
                    assertThat(e.code()).isEqualTo("CONTENT_CHANGED");
                    assertThat(e.currentCanonicalHash()).isEqualTo(changed.canonicalHash());
                    assertThat(e.currentLockVersion()).isEqualTo(changed.lockVersion());
                    assertThat(e.getMessage()).contains("changed since it was reviewed");
                });
        assertNothingWritten(changed, history, events, idempotency);

        // And after reviewing the new content bob approves it with its hash
        RuleSetVersion published = versions.approve("card-fee", 1,
                new WriteOptions("ap-3", changed.lockVersion(), "ok").expectingContent(changed.canonicalHash()));
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_084_a_rejection_of_other_content_than_reviewed_is_refused_and_writes_nothing() {
        // Given v1 changed after review
        RuleSetVersion changed = changedAfterReview();
        int history = stores.history.size();
        int events = stores.outbox.size();
        int idempotency = stores.idempotency.size();

        // When bob rejects with the old hash
        assertThatThrownBy(() -> versions.reject("card-fee", 1,
                new WriteOptions("rj-1", changed.lockVersion(), "R2 waiver needs minutes")
                        .expectingContent(reviewed.canonicalHash())))
                // Then the same refusal, with nothing written
                .isInstanceOfSatisfying(ContentChangedException.class,
                        e -> assertThat(e.currentCanonicalHash()).isEqualTo(changed.canonicalHash()));
        assertNothingWritten(changed, history, events, idempotency);

        // And a rejection with the current hash goes through
        RuleSetVersion rejected = versions.reject("card-fee", 1,
                new WriteOptions("rj-2", changed.lockVersion(), "R2 waiver needs minutes")
                        .expectingContent(changed.canonicalHash()));
        assertThat(rejected.status()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void RLE_084_a_stale_lock_version_is_still_refused_first_and_names_the_current_state() {
        // Given v1 changed after review
        RuleSetVersion changed = changedAfterReview();

        // When bob sends the old lock version with the old hash
        assertThatThrownBy(() -> versions.approve("card-fee", 1,
                new WriteOptions("ap-4", reviewed.lockVersion(), null).expectingContent(reviewed.canonicalHash())))
                // Then the lock check answers first, and also names what is stored
                .isInstanceOfSatisfying(VersionConflictException.class, e -> {
                    assertThat(e.getMessage()).contains("Expected lock version");
                    assertThat(e.currentCanonicalHash()).isEqualTo(changed.canonicalHash());
                    assertThat(e.currentLockVersion()).isEqualTo(changed.lockVersion());
                });
    }

    @Test
    void RLE_084_a_hash_that_is_not_64_lower_case_hexadecimal_characters_is_400_input_invalid() {
        for (String malformed : new String[] {"", "abc", reviewed.canonicalHash().toUpperCase(), "z".repeat(64)}) {
            assertThatThrownBy(() -> versions.approve("card-fee", 1,
                    new WriteOptions("ap-5", reviewed.lockVersion(), null).expectingContent(malformed)))
                    .isInstanceOf(InputInvalidException.class)
                    .hasMessageContaining("expectedCanonicalHash");
            assertThatThrownBy(() -> versions.reject("card-fee", 1,
                    new WriteOptions("rj-5", reviewed.lockVersion(), "reason").expectingContent(malformed)))
                    .isInstanceOf(InputInvalidException.class);
        }
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    @Test
    void RLE_084_the_same_key_with_another_hash_is_not_a_replay() {
        // Given an approval refused for old content under key ap-6
        RuleSetVersion changed = changedAfterReview();
        assertThatThrownBy(() -> versions.approve("card-fee", 1,
                new WriteOptions("ap-6", changed.lockVersion(), null).expectingContent(reviewed.canonicalHash())))
                .isInstanceOf(ContentChangedException.class);

        // When bob retries the same key with the hash of the current content, the refusal was never recorded
        RuleSetVersion published = versions.approve("card-fee", 1,
                new WriteOptions("ap-6", changed.lockVersion(), null).expectingContent(changed.canonicalHash()));

        // Then it publishes
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
    }
}
