package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Discarding a draft (RLE-091, #418). card-fee v1 is approved and in effect; v2 is lan's draft; minh holds
 * {@code rules:author} too; bob approves; bank-b is another tenant.
 */
class DiscardDraftTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-12-01T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final VersionService versions = engines(true).versionService();

    RuleEngines engines(boolean draftAuthoring) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withDraftAuthoring(draftAuthoring)), stores, stores, stores);
        engines.cursorKeys().load();
        return engines;
    }

    RuleSetVersion lansDraftV2() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null)
                .expectingContent(requested.canonicalHash()));
        caller.become("bank-a", "lan", AUTHOR);
        return versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()));
    }

    @Test
    void RLE_091_the_author_discards_a_draft_which_stays_in_the_history_and_leaves_the_list() {
        // Given lan's draft v2 and the rows written so far
        RuleSetVersion draft = lansDraftV2();
        int versionRows = stores.versions.size();
        int events = stores.outbox.size();

        // When lan discards it with a reason, then repeats the same request
        WriteOptions options = new WriteOptions("k-910", draft.lockVersion(), "Started from the wrong version");
        RuleSetVersion discarded = versions.discard("card-fee", 2, options);
        RuleSetVersion repeated = versions.discard("card-fee", 2, options);

        // Then v2 is DISCARDED with the lock version moved on, and the repeat returns the same answer
        assertThat(discarded.status()).isEqualTo(VersionStatus.DISCARDED);
        assertThat(discarded.lockVersion()).isEqualTo(draft.lockVersion() + 1);
        assertThat(discarded.canonicalHash()).isEqualTo(draft.canonicalHash());
        assertThat(repeated).isEqualTo(discarded);
        // and nothing was deleted: the row is still stored, with its content and author
        assertThat(stores.versions).hasSize(versionRows);
        RuleSetVersion stored = versions.find("card-fee", 2, true).orElseThrow();
        assertThat(stored.status()).isEqualTo(VersionStatus.DISCARDED);
        assertThat(stored.createdBy()).isEqualTo("lan");
        // and the history of v2 ends with one DISCARDED row naming lan and the reason
        List<VersionHistoryEntry> history = versions.history("card-fee", 2);
        assertThat(history).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.DISCARDED);
        assertThat(history.getLast()).satisfies(row -> {
            assertThat(row.actor()).isEqualTo("lan");
            assertThat(row.reason()).isEqualTo("Started from the wrong version");
            assertThat(row.fromStatus()).isEqualTo(VersionStatus.DRAFT);
            assertThat(row.toStatus()).isEqualTo(VersionStatus.DISCARDED);
        });
        // and exactly one event was written, with the author and the content hash
        assertThat(stores.outbox).hasSize(events + 1);
        assertThat(stores.outbox.getLast().payload()).contains("rules.version.discarded.v1", "\"discardedBy\":\"lan\"",
                draft.canonicalHash());
        // and the version list shows v1 only, while the number is not used again
        assertThat(versions.listVersions("card-fee", PageRequest.first()).items())
                .extracting(VersionSummary::version).containsExactly(1);
        assertThat(versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml())).version()).isEqualTo(3);
    }

    @Test
    void RLE_091_only_a_draft_is_discarded_so_approved_content_is_never_removed() {
        // Given v1 published and v2 a draft that lan discards
        RuleSetVersion draft = lansDraftV2();
        RuleSetVersion v1 = versions.find("card-fee", 1, true).orElseThrow();
        versions.discard("card-fee", 2, new WriteOptions("k-911", draft.lockVersion(), null));
        int history = stores.history.size();
        int events = stores.outbox.size();

        // When lan discards the published v1, or the discarded v2 again with a new key
        assertThatThrownBy(() -> versions.discard("card-fee", 1, new WriteOptions("k-912", v1.lockVersion(), null)))
                .isInstanceOf(InvalidStateException.class);
        assertThatThrownBy(() -> versions.discard("card-fee", 2, new WriteOptions("k-913", draft.lockVersion() + 1, null)))
                .isInstanceOf(InvalidStateException.class);

        // Then v1 is still published and nothing was written
        assertThat(versions.find("card-fee", 1, true).orElseThrow().status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_091_only_the_author_with_rules_author_discards_and_the_tenant_decides_what_can_be_seen() {
        // Given lan's draft
        RuleSetVersion draft = lansDraftV2();
        int history = stores.history.size();
        WriteOptions options = new WriteOptions("k-914", draft.lockVersion(), null);

        // When minh (another author), bob (approver only) and a bank-b author try
        caller.become("bank-a", "minh", AUTHOR);
        assertThatThrownBy(() -> versions.discard("card-fee", 2, options)).isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.discard("card-fee", 2, options)).isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "eva", AUTHOR);
        assertThatThrownBy(() -> versions.discard("card-fee", 2, options)).isInstanceOf(RuleSetNotFoundException.class);

        // and lan uses a stale lock version
        caller.become("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.discard("card-fee", 2, new WriteOptions("k-915", 9L, null)))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> versions.discard("card-fee", 2,
                new WriteOptions("k-916", draft.lockVersion(), "x".repeat(2_001)))).isInstanceOf(InputInvalidException.class);

        // Then the draft is still a draft and nothing was written
        assertThat(versions.find("card-fee", 2, true).orElseThrow().status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_091_the_discard_action_is_offered_to_the_author_of_a_draft_only() {
        Set<String> author = AUTHOR;
        assertThat(VersionActions.allowed(VersionStatus.DRAFT, "lan", null, "lan", author))
                .containsExactly("request-publish", "discard");
        assertThat(VersionActions.allowed(VersionStatus.DRAFT, "lan", null, "minh", author))
                .containsExactly("request-publish");
        assertThat(VersionActions.allowed(VersionStatus.DRAFT, "lan", null, "lan", Set.of("rules:read"))).isEmpty();
        assertThat(VersionActions.allowed(VersionStatus.DISCARDED, "lan", null, "lan", author)).isEmpty();
        assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", null, "lan", author)).isEmpty();
    }

    @Test
    void RLE_091_discarding_is_allowed_when_cockpit_drafts_are_off_because_it_brings_in_no_content() {
        // Given a host with draft editing off and a draft lan imported
        VersionService off = engines(false).versionService();
        caller.become("bank-a", "lan", AUTHOR);
        off.importRuleSet(ImportRequest.yaml(CardFee.yaml()));

        // When lan discards it
        RuleSetVersion discarded = off.discard("card-fee", 1, new WriteOptions("k-917", 0L, null));

        // Then it is discarded
        assertThat(discarded.status()).isEqualTo(VersionStatus.DISCARDED);
    }
}
