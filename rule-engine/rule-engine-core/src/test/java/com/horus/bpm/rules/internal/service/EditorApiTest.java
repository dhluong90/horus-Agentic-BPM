package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.IdempotencyKeyReuseException;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.InvalidStateException;
import com.horus.bpm.rules.api.LifecycleAction;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.Page;
import com.horus.bpm.rules.api.PageRequest;
import com.horus.bpm.rules.api.PermissionDeniedException;
import com.horus.bpm.rules.api.QueryInvalidException;
import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.SelfApprovalForbiddenException;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.VersionActions;
import com.horus.bpm.rules.api.VersionConflictException;
import com.horus.bpm.rules.api.VersionHistoryEntry;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.api.VersionSummary;
import com.horus.bpm.rules.api.Violation;
import com.horus.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The authoring API the cockpit rule editor needs (#147): lists, reject, history, located validation errors and
 * allowed actions, on the in-memory stores. Users: lan authors, minh approves, dan only reads, at bank-a; an
 * auditor of bank-b checks tenant isolation.
 */
class EditorApiTest {

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_10 = Instant.parse("2026-12-10T03:00:00Z");

    static final Set<String> AUTHOR_APPROVER = Set.of("rules:read", "rules:author", "rules:approve");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> READER = Set.of("rules:read");

    final MovableClock clock = new MovableClock(DEC_01);
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()), stores, stores, stores);
    final VersionService versions = engines.versionService();

    {
        engines.cursorKeys().load();   // as the starter does at start-up
    }

    // ---------------------------------------------------------------- RLE-025 reject

    @Test
    void RLE_025_reject_returns_pending_version_to_draft_with_reason() {
        // Given card-fee v1 published, v2 pending approval requested by lan, lockVersion 1
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        RuleSetVersion pending = versions.requestPublish("card-fee", 2, WriteOptions.none());
        int eventsBefore = stores.outbox.size();

        // When minh rejects it with a reason, an idempotency key and If-Match
        caller.become("bank-a", "minh", APPROVER);
        WriteOptions reject = new WriteOptions("k-201", pending.lockVersion(), "R2 waiver needs pricing committee minutes");
        RuleSetVersion rejected = versions.reject("card-fee", 2, reject);

        // Then v2 is a draft again with the request cleared and the lock moved on
        assertThat(rejected.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(rejected.lockVersion()).isEqualTo(pending.lockVersion() + 1);
        assertThat(rejected.requestedBy()).isNull();
        assertThat(rejected.requestedAt()).isNull();
        // and one REJECTED history row after CREATED and REQUESTED
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.REQUESTED, LifecycleAction.REJECTED);
        VersionHistoryEntry entry = versions.history("card-fee", 2).getLast();
        assertThat(entry.actor()).isEqualTo("minh");
        assertThat(entry.reason()).isEqualTo("R2 waiver needs pricing committee minutes");
        assertThat(entry.fromStatus()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(entry.toStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(entry.lockVersion()).isEqualTo(rejected.lockVersion());
        // and one rejected event with actor and reason
        assertThat(stores.outbox).hasSize(eventsBefore + 1);
        assertThat(stores.outbox.getLast().payload())
                .contains("\"eventType\":\"rules.version.rejected.v1\"", "\"rejectedBy\":\"minh\"",
                        "\"reason\":\"R2 waiver needs pricing committee minutes\"", "\"tenantId\":\"bank-a\"",
                        "\"ruleSet\":\"card-fee\"", "\"version\":2");

        // When the reject is replayed with k-201 and the same body
        RuleSetVersion replay = versions.reject("card-fee", 2, reject);

        // Then the same result, no second event and no second history row
        assertThat(replay).isEqualTo(rejected);
        assertThat(stores.outbox).hasSize(eventsBefore + 1);
        assertThat(versions.history("card-fee", 2)).hasSize(3);

        // And lan can request publication again
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        assertThat(versions.requestPublish("card-fee", 2, WriteOptions.none()).status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    @Test
    void RLE_025_reject_refuses_blank_reason_stale_lock_and_non_pending_versions_and_changes_nothing() {
        // Given v1 published and v2 pending
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        RuleSetVersion pending = versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "minh", APPROVER);
        int events = stores.outbox.size();
        int history = stores.history.size();

        // Then an empty reason is 400 INPUT_INVALID naming reason
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "")))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "   ")))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "x".repeat(2001))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("2000");
        // a stale If-Match is 409 VERSION_CONFLICT
        assertThatThrownBy(() -> versions.reject("card-fee", 2,
                new WriteOptions(null, pending.lockVersion() - 1, "R2 waiver needs pricing committee minutes")))
                .isInstanceOf(VersionConflictException.class);
        // a published version is 409 INVALID_STATE
        assertThatThrownBy(() -> versions.reject("card-fee", 1, new WriteOptions(null, null, "too late")))
                .isInstanceOf(InvalidStateException.class).extracting(e -> ((InvalidStateException) e).code())
                .isEqualTo("INVALID_STATE");

        // and nothing changed
        assertThat(versions.find("card-fee", 2, false).orElseThrow()).isEqualTo(pending);
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(history);
    }

    @Test
    void RLE_025_the_requester_cannot_reject_their_own_request_and_is_pointed_to_withdraw() {
        // Given fx-margin v1 pending, requested by minh, who also holds rules:approve
        caller.become("bank-a", "minh", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));
        RuleSetVersion pending = versions.requestPublish("fx-margin", 1, WriteOptions.none());
        int events = stores.outbox.size();
        int history = stores.history.size();

        // When minh rejects it, then PD-089: 403 SELF_APPROVAL_FORBIDDEN pointing to :withdraw, nothing changes
        assertThatThrownBy(() -> versions.reject("fx-margin", 1, new WriteOptions(null, null, "Wrong margin table attached")))
                .isInstanceOfSatisfying(SelfApprovalForbiddenException.class, e -> {
                    assertThat(e.code()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
                    assertThat(e.getMessage()).contains(":withdraw");
                });
        assertThat(versions.find("fx-margin", 1, false).orElseThrow()).isEqualTo(pending);
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(history);

        // and an approver who did not request it still can
        caller.become("bank-a", "lan", APPROVER);
        assertThat(versions.reject("fx-margin", 1, new WriteOptions(null, null, "Wrong margin table attached")).status())
                .isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void RLE_025_reject_with_the_same_key_and_another_reason_is_a_key_reuse_mismatch() {
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.become("bank-a", "minh", APPROVER);
        versions.reject("card-fee", 2, new WriteOptions("k-202", null, "first reason"));
        int events = stores.outbox.size();

        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions("k-202", null, "another reason")))
                .isInstanceOf(IdempotencyKeyReuseException.class)
                .extracting(e -> ((IdempotencyKeyReuseException) e).code()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_025_reject_needs_rules_approve_and_stays_inside_the_tenant() {
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        versions.requestPublish("card-fee", 2, WriteOptions.none());

        caller.become("bank-a", "dan", READER);
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "no")))
                .isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-b", "minh", APPROVER);
        assertThatThrownBy(() -> versions.reject("card-fee", 2, new WriteOptions(null, null, "no")))
                .isInstanceOf(RuleSetNotFoundException.class);

        caller.become("bank-a", "dan", READER);
        assertThat(versions.find("card-fee", 2, false).orElseThrow().status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    // ---------------------------------------------------------------- RLE-046 withdraw

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> EVALUATOR = Set.of("rules:evaluate");

    /** card-fee v1 published by bob, v2 pending approval with lockVersion 1, requested by alice (rules:author only). */
    RuleSetVersion alicesPendingCardFeeV2() {
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        versions.approve("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "alice", AUTHOR);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("0 VND", "card-fee")));
        return versions.requestPublish("card-fee", 2, WriteOptions.none());
    }

    @Test
    void RLE_046_requester_withdraws_own_pending_request_to_draft() {
        // Given card-fee v1 published and in effect, v2 pending approval requested by alice
        RuleSetVersion pending = alicesPendingCardFeeV2();
        int events = stores.outbox.size();

        // When alice withdraws it with k-301, If-Match and a reason
        WriteOptions withdraw = new WriteOptions("k-301", pending.lockVersion(), "Wrong fee for GOLD");
        RuleSetVersion draft = versions.withdraw("card-fee", 2, withdraw);

        // Then v2 is a draft again, the request cleared and the lock moved on
        assertThat(draft.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(draft.lockVersion()).isEqualTo(pending.lockVersion() + 1);
        assertThat(draft.requestedBy()).isNull();
        assertThat(draft.requestedAt()).isNull();
        // and one WITHDRAWN history row with actor, reason and statuses
        VersionHistoryEntry entry = versions.history("card-fee", 2).getLast();
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.REQUESTED, LifecycleAction.WITHDRAWN);
        assertThat(entry.actor()).isEqualTo("alice");
        assertThat(entry.reason()).isEqualTo("Wrong fee for GOLD");
        assertThat(entry.fromStatus()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(entry.toStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(entry.lockVersion()).isEqualTo(draft.lockVersion());
        // and exactly one withdrawn event, no rejected event
        assertThat(stores.outbox).hasSize(events + 1);
        assertThat(stores.outbox.getLast().payload())
                .contains("\"eventType\":\"rules.version.withdrawn.v1\"", "\"withdrawnBy\":\"alice\"",
                        "\"reason\":\"Wrong fee for GOLD\"", "\"tenantId\":\"bank-a\"", "\"ruleSet\":\"card-fee\"",
                        "\"version\":2", "\"occurredAt\":\"2026-12-01T00:00:00Z\"")
                .doesNotContain("rules.version.rejected.v1");
        // and evaluation still selects v1: nothing was published
        caller.become("bank-a", "teller", EVALUATOR);
        DecisionResult result = engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)));
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.outcome()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", Money.ofMinor("VND", 10000))));

        // When the withdrawal is replayed with k-301 and the same body
        caller.become("bank-a", "alice", AUTHOR);
        assertThat(versions.withdraw("card-fee", 2, withdraw)).isEqualTo(draft);
        // Then the same result and no second event or history row
        assertThat(stores.outbox).hasSize(events + 1);
        assertThat(versions.history("card-fee", 2)).hasSize(3);

        // When alice requests publication again, a new approval starts and bob may approve it
        RuleSetVersion again = versions.requestPublish("card-fee", 2, WriteOptions.none());
        assertThat(again.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        caller.become("bank-a", "bob", APPROVER);
        assertThat(versions.approve("card-fee", 2, new WriteOptions(null, again.lockVersion(), null)).status())
                .isEqualTo(VersionStatus.PUBLISHED);
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.REQUESTED, LifecycleAction.WITHDRAWN,
                        LifecycleAction.REQUESTED, LifecycleAction.APPROVED);
    }

    @Test
    void RLE_046_withdraw_without_a_reason_records_a_null_reason() {
        RuleSetVersion pending = alicesPendingCardFeeV2();

        versions.withdraw("card-fee", 2, new WriteOptions("k-302", pending.lockVersion(), null));

        assertThat(versions.history("card-fee", 2).getLast().reason()).isNull();
        assertThat(stores.outbox.getLast().payload()).contains("\"reason\":null", "\"withdrawnBy\":\"alice\"");
    }

    @Test
    void RLE_046_only_the_requester_withdraws_and_every_refusal_changes_nothing() {
        // Given v2 pending, requested by alice; bob approves, carol authors
        RuleSetVersion pending = alicesPendingCardFeeV2();
        int events = stores.outbox.size();
        int history = stores.history.size();
        int keys = stores.idempotency.size();

        // bob holds rules:approve only: 403 PERMISSION_DENIED (an approver uses :reject)
        caller.become("bank-a", "bob", APPROVER);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-b", 1L, null)))
                .isInstanceOf(PermissionDeniedException.class);
        // bob holding rules:author too is still not the requester
        caller.become("bank-a", "bob", AUTHOR_APPROVER);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-b2", 1L, null)))
                .isInstanceOfSatisfying(PermissionDeniedException.class, e -> {
                    assertThat(e.code()).isEqualTo("PERMISSION_DENIED");
                    assertThat(e.getMessage()).contains(":reject");
                });
        // carol is another author
        caller.become("bank-a", "carol", AUTHOR);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-c", 1L, null)))
                .isInstanceOf(PermissionDeniedException.class);
        // a reader
        caller.become("bank-a", "dan", READER);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-d", 1L, null)))
                .isInstanceOf(PermissionDeniedException.class);
        // alice with a stale If-Match: 409 VERSION_CONFLICT
        caller.become("bank-a", "alice", AUTHOR);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2,
                new WriteOptions("k-a1", pending.lockVersion() - 1, "Wrong fee for GOLD")))
                .isInstanceOf(VersionConflictException.class);
        // alice withdraws the published v1: 409 INVALID_STATE
        assertThatThrownBy(() -> versions.withdraw("card-fee", 1, new WriteOptions("k-a2", null, null)))
                .isInstanceOfSatisfying(InvalidStateException.class, e -> assertThat(e.code()).isEqualTo("INVALID_STATE"));
        // alice with a 2,001-character reason: 400 INPUT_INVALID naming reason
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2,
                new WriteOptions("k-a3", pending.lockVersion(), "x".repeat(2001))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("reason");
        // alice of another tenant does not see the rule set
        caller.become("bank-b", "alice", AUTHOR);
        assertThatThrownBy(() -> versions.withdraw("card-fee", 2, new WriteOptions("k-a4", pending.lockVersion(), null)))
                .isInstanceOf(RuleSetNotFoundException.class);

        // and in every case nothing changed
        caller.become("bank-a", "alice", AUTHOR);
        assertThat(versions.find("card-fee", 2, false).orElseThrow()).isEqualTo(pending);
        assertThat(stores.outbox).hasSize(events);
        assertThat(stores.history).hasSize(history);
        assertThat(stores.idempotency).hasSize(keys);
    }

    @Test
    void RLE_046_withdraw_with_the_same_key_and_another_reason_is_a_key_reuse_mismatch() {
        RuleSetVersion pending = alicesPendingCardFeeV2();
        versions.withdraw("card-fee", 2, new WriteOptions("k-303", pending.lockVersion(), "first reason"));
        int events = stores.outbox.size();

        assertThatThrownBy(() -> versions.withdraw("card-fee", 2,
                new WriteOptions("k-303", pending.lockVersion(), "another reason")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_046_allowed_actions_offer_withdraw_to_the_requester_only() {
        RuleSetVersion pending = alicesPendingCardFeeV2();

        // alice, the requester with rules:author: withdraw, never approve or reject
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "alice", AUTHOR))
                .containsExactly("withdraw");
        // a requester who also holds rules:approve still gets withdraw only
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "alice",
                AUTHOR_APPROVER)).containsExactly("withdraw");
        // bob approves or rejects; carol, another author, gets nothing
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "bob", APPROVER))
                .containsExactly("approve", "reject");
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "carol", AUTHOR))
                .isEmpty();
        // a requester without rules:author (approve only) cannot withdraw: PD-089 accepted edge case
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "alice", APPROVER))
                .isEmpty();
        // and once withdrawn, the draft offers request-publish again
        RuleSetVersion draft = versions.withdraw("card-fee", 2, new WriteOptions(null, pending.lockVersion(), null));
        assertThat(VersionActions.allowed(draft.status(), draft.createdBy(), draft.requestedBy(), "alice", AUTHOR))
                .containsExactly("request-publish");
    }

    // ---------------------------------------------------------------- RLE-039 rule set list

    @Test
    void RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor() {
        // Given bank-a: card-fee v1 published on 1 Dec and v2 requested on 10 Dec, fx-margin and kyc-risk drafts
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "kyc-risk")));
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        clock.set(DEC_10);
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        // and bank-b has its own aml-score
        caller.become("bank-b", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "aml-score")));

        // When dan of bank-a reads two at a time
        caller.become("bank-a", "dan", READER);
        Page<RuleSetSummary> first = versions.listRuleSets(new PageRequest(2, null));
        Page<RuleSetSummary> second = versions.listRuleSets(new PageRequest(2, first.nextCursor()));

        // Then
        assertThat(first.items()).containsExactly(
                new RuleSetSummary("card-fee", 2, VersionStatus.PENDING_APPROVAL, 1, DEC_10),
                new RuleSetSummary("fx-margin", 1, VersionStatus.DRAFT, null, DEC_01));
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.items()).extracting(RuleSetSummary::ruleSet).containsExactly("kyc-risk");
        assertThat(second.nextCursor()).isNull();
        assertThat(versions.findRuleSet("card-fee")).contains(first.items().getFirst());

        // and bank-b sees only its own
        caller.become("bank-b", "dan", READER);
        assertThat(versions.listRuleSets(PageRequest.first()).items()).extracting(RuleSetSummary::ruleSet)
                .containsExactly("aml-score");
        assertThat(versions.findRuleSet("card-fee")).isEmpty();
    }

    @Test
    void RLE_039_a_rejection_counts_as_the_latest_change() {
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        clock.set(DEC_10);
        caller.become("bank-a", "minh", APPROVER);
        versions.reject("card-fee", 2, new WriteOptions(null, null, "R2 waiver needs pricing committee minutes"));

        RuleSetSummary summary = versions.findRuleSet("card-fee").orElseThrow();

        assertThat(summary.lastChangedAt()).isEqualTo(DEC_10);
        assertThat(summary.latestStatus()).isEqualTo(VersionStatus.DRAFT);
    }

    @Test
    void RLE_039_limit_and_cursor_are_checked_and_rules_read_is_required() {
        publishedCardFee();
        caller.become("bank-a", "dan", READER);

        for (int limit : new int[] {0, -1, 201}) {
            assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(limit, null)))
                    .as("limit " + limit).isInstanceOf(QueryInvalidException.class).hasMessageContaining("limit");
        }
        String versionsCursor = Cursors.encode("versions", "2");
        for (String cursor : new String[] {"", "not base64 !", versionsCursor, "x".repeat(401)}) {
            assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(null, cursor)))
                    .as("cursor " + cursor).isInstanceOf(QueryInvalidException.class)
                    .extracting(e -> ((QueryInvalidException) e).code()).isEqualTo("QUERY_INVALID");
        }
        assertThat(versions.listRuleSets(new PageRequest(200, null)).items()).hasSize(1);

        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> versions.listRuleSets(PageRequest.first())).isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> versions.findRuleSet("card-fee")).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_039_the_maximum_page_size_is_a_setting() {
        RuleEngines small = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                new EngineSettings(1000, java.time.Duration.ofHours(24), 34, true, 1, 2000)), stores, stores, stores);
        small.cursorKeys().load();
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));

        assertThat(small.versionService().listRuleSets(PageRequest.first()).items()).hasSize(1);
        assertThatThrownBy(() -> small.versionService().listRuleSets(new PageRequest(2, null)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("1 to 1");
    }

    // ---------------------------------------------------------------- RLE-040 version list

    @Test
    void RLE_040_versions_are_listed_newest_first_with_their_actors() {
        // Given card-fee v1 by lan approved by minh, v2 requested by lan, v3 a draft by lan
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("20000 VND", "card-fee")));

        // When minh lists two at a time
        caller.become("bank-a", "minh", APPROVER);
        Page<VersionSummary> first = versions.listVersions("card-fee", new PageRequest(2, null));
        Page<VersionSummary> second = versions.listVersions("card-fee", new PageRequest(2, first.nextCursor()));

        // Then
        assertThat(first.items()).extracting(VersionSummary::version, VersionSummary::status,
                VersionSummary::createdBy, VersionSummary::requestedBy, VersionSummary::approvedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(3, VersionStatus.DRAFT, "lan", null, null),
                        org.assertj.core.groups.Tuple.tuple(2, VersionStatus.PENDING_APPROVAL, "lan", "lan", null));
        assertThat(second.items()).extracting(VersionSummary::version, VersionSummary::approvedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "minh"));
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void RLE_040_another_tenant_or_an_unknown_name_is_rule_set_not_found() {
        publishedCardFee();

        caller.become("bank-b", "minh", APPROVER);
        assertThatThrownBy(() -> versions.listVersions("card-fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThatThrownBy(() -> versions.history("card-fee", 1)).isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "minh", APPROVER);
        assertThatThrownBy(() -> versions.listVersions("no-such-set", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThatThrownBy(() -> versions.listVersions("card-fee", new PageRequest(500, null)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.listVersions("card-fee",
                new PageRequest(null, Cursors.encode("versions", "zero")))).isInstanceOf(QueryInvalidException.class);
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> versions.listVersions("card-fee", PageRequest.first()))
                .isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> versions.history("card-fee", 1)).isInstanceOf(PermissionDeniedException.class);
    }

    // ---------------------------------------------------------------- RLE-041 located violations

    @Test
    void RLE_041_validation_failure_lists_each_cell_problem_with_rule_and_column() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        byte[] bad = """
                schemaVersion: 1
                ruleSet: card-fee-bad
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: balance, type: money }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: { balance: ">= 50000000" }, then: { fee: "10000 VND" } }
                  - { id: R3, when: {}, then: { fee: "50000 VND" } }
                  - { id: R4, when: {}, then: { fee: "+ 1" } }
                """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.violations()).extracting(Violation::ruleId, Violation::column)
                            .containsExactly(org.assertj.core.groups.Tuple.tuple("R2", "balance"),
                                    org.assertj.core.groups.Tuple.tuple("R4", "fee"));
                    assertThat(e.violations()).allSatisfy(v -> assertThat(v.message()).doesNotStartWith("Rule"));
                    assertThat(e.getMessage()).startsWith("Rule R2, column balance: ").endsWith("(and 1 more)");
                });
        assertThat(stores.ruleSets).isEmpty();
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_041_a_structural_problem_is_one_violation_without_a_location() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        byte[] bad = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("hitPolicy: first", "hitPolicy: first\nmaks: true").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .singleElement().satisfies(v -> {
                            assertThat(v.ruleId()).isNull();
                            assertThat(v.column()).isNull();
                            assertThat(v.message()).contains("maks");
                        }));
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_041_an_unknown_input_in_a_rule_is_placed_on_its_cell() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        byte[] bad = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("when: { txCount: \">= 3\" }", "when: { txCnt: \">= 3\" }").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .singleElement().isEqualTo(new Violation("R2", "txCnt", "unknown input 'txCnt'")));
    }

    // ---------------------------------------------------------------- RLE-042 money output needs a currency

    @Test
    void RLE_042_money_output_without_currency_is_refused_at_import() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        for (String cell : new String[] {"\"30000\"", "30000"}) {
            byte[] file = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                    .replace("then: { fee: \"10000 VND\" }", "then: { fee: " + cell + " }").getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(file)))
                    .as(cell).isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                            .singleElement().satisfies(v -> {
                                assertThat(v.ruleId()).isEqualTo("R2");
                                assertThat(v.column()).isEqualTo("fee");
                                assertThat(v.message()).contains("money", "currency");
                            }));
        }
        assertThat(stores.versions).isEmpty();
    }

    @Test
    void RLE_042_an_output_cell_of_another_type_is_refused_and_a_correct_money_cell_evaluates() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        byte[] wrongType = """
                schemaVersion: 1
                ruleSet: fee-waiver
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: waived, type: boolean }
                  - { name: months, type: integer }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { waived: "'yes'", months: 1.5 } }
                  - { id: R2, when: {}, then: { waived: false, months: 0 } }
                """.getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(wrongType)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .extracting(Violation::ruleId, Violation::column)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("R1", "waived"),
                                org.assertj.core.groups.Tuple.tuple("R1", "months")));

        // A money cell with its currency imports and evaluates
        byte[] good = new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"30000 VND\" }").getBytes(StandardCharsets.UTF_8);
        versions.importRuleSet(ImportRequest.yaml(good));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "minh", Set.of("rules:approve", "rules:evaluate"));
        versions.approve("card-fee", 1, WriteOptions.none());
        RuleEngine engine = engines.ruleEngine();
        DecisionResult result = engine.evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)));
        assertThat(result.outcome()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", Money.ofMinor("VND", 30000))));
    }

    // ---------------------------------------------------------------- security review follow-ups

    @Test
    void RLE_041_a_document_with_more_than_100_problems_lists_the_first_100_and_says_so() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: many-mistakes
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                """);
        for (int i = 1; i <= 150; i++) {
            yaml.append("  - { id: R").append(i).append(", when: {}, then: { fee: \"30000\" } }\n");
        }

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(yaml.toString().getBytes(StandardCharsets.UTF_8))))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> {
                    assertThat(e.violations()).hasSize(100);
                    assertThat(e.violations().getFirst().ruleId()).isEqualTo("R1");
                    assertThat(e.violations().getLast().ruleId()).isEqualTo("R100");
                    assertThat(e.truncated()).isTrue();
                    assertThat(e.getMessage()).endsWith("(and more; the first 100 problems are listed)");
                });
    }

    @Test
    void RLE_041_a_document_with_few_problems_is_not_marked_truncated() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        byte[] bad = cardFee("30000", "card-fee");

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.truncated()).isFalse());
    }

    @Test
    void RLE_039_a_cursor_key_that_is_not_a_rule_set_name_is_query_invalid() {
        publishedCardFee();
        caller.become("bank-a", "dan", READER);

        for (String key : new String[] {"\u0000", "card-fee\u0000", "Card-Fee", "a".repeat(101), "card fee"}) {
            assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(null, Cursors.encode("rule-sets", key))))
                    .as(key).isInstanceOf(QueryInvalidException.class);
        }
    }

    @Test
    void RLE_039_a_cursor_replayed_in_another_tenant_is_query_invalid() {
        // Given bank-a has card-fee and fx-margin, bank-b has aml-score and zz-limits
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));
        caller.become("bank-b", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "aml-score")));
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "zz-limits")));
        caller.become("bank-a", "dan", READER);
        String cursor = versions.listRuleSets(new PageRequest(1, null)).nextCursor();
        assertThat(versions.listRuleSets(new PageRequest(1, cursor)).items()).extracting(RuleSetSummary::ruleSet)
                .containsExactly("fx-margin");

        // When a bank-b reader replays bank-a's cursor, then it was not issued to bank-b
        caller.become("bank-b", "dan", READER);
        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(10, cursor)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_039_an_edited_or_cross_list_cursor_is_query_invalid() {
        publishedCardFee();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("card-fee", ImportRequest.yaml(cardFee("10000 VND", "card-fee")));
        versions.importRuleSet(ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));
        versions.importVersion("fx-margin", ImportRequest.yaml(cardFee("10000 VND", "fx-margin")));
        caller.become("bank-a", "dan", READER);
        String versionsCursor = versions.listVersions("card-fee", new PageRequest(1, null)).nextCursor();
        String ruleSetsCursor = versions.listRuleSets(new PageRequest(1, null)).nextCursor();
        String edited = Cursors.encode("rule-sets", "d-not-a-rule-set") + ruleSetsCursor.substring(ruleSetsCursor.indexOf('.'));

        assertThat(versions.listVersions("card-fee", new PageRequest(1, versionsCursor)).items())
                .extracting(VersionSummary::version).containsExactly(1);
        assertThatThrownBy(() -> versions.listVersions("fx-margin", new PageRequest(1, versionsCursor)))
                .as("a card-fee cursor on fx-margin").isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(1, versionsCursor)))
                .as("a versions cursor on the rule set list").isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(1, edited)))
                .as("payload edited, signature kept").isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_039_a_name_that_cannot_be_a_rule_set_is_not_found() {
        publishedCardFee();
        caller.become("bank-a", "dan", READER);

        assertThat(versions.findRuleSet("card\u0000fee")).isEmpty();
        assertThat(versions.find("card\u0000fee", 1, false)).isEmpty();
        assertThatThrownBy(() -> versions.listVersions("Card-Fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThatThrownBy(() -> versions.history("card\u0000fee", 1)).isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_009_an_approval_comment_longer_than_the_reason_limit_is_refused_and_changes_nothing() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "minh", APPROVER);
        int history = stores.history.size();

        assertThatThrownBy(() -> versions.approve("card-fee", 1, new WriteOptions(null, null, "x".repeat(2001))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("comment").hasMessageContaining("2000");
        assertThat(stores.history).hasSize(history);
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.PENDING_APPROVAL);

        assertThat(versions.approve("card-fee", 1, new WriteOptions(null, null, "y".repeat(2000))).status())
                .isEqualTo(VersionStatus.PUBLISHED);
        assertThat(versions.history("card-fee", 1).getLast().reason()).hasSize(2000);
    }

    @Test
    void RLE_039_without_a_loaded_key_lists_are_schema_not_ready_naming_the_schema_state() {
        InMemoryStores fresh = new InMemoryStores();
        RuleEngines unloaded = RuleEngines.create(
                new EngineContext(caller, caller, clock, fresh, fresh, EngineSettings.defaults()), fresh, fresh, fresh);
        unloaded.cursorKeys().notReady("schema at version 4, 1 migration(s) pending, migrate-on-start=false");
        caller.become("bank-a", "dan", READER);

        assertThatThrownBy(() -> unloaded.versionService().listRuleSets(PageRequest.first()))
                .isInstanceOfSatisfying(com.horus.bpm.rules.api.SchemaNotReadyException.class, e -> {
                    assertThat(e.code()).isEqualTo("SCHEMA_NOT_READY");
                    assertThat(e.getMessage()).contains("schema at version 4", "migrate-on-start=false");
                });
        assertThat(fresh.engineKeys).as("no key is created on the request path").isEmpty();
    }

    // ---------------------------------------------------------------- RLE-043 allowed actions

    @Test
    void RLE_043_allowed_actions_follow_permissions_and_four_eyes() {
        // v2 pending, created and requested by lan
        assertThat(VersionActions.allowed(VersionStatus.PENDING_APPROVAL, "lan", "lan", "lan", AUTHOR_APPROVER))
                .containsExactly("withdraw");
        assertThat(VersionActions.allowed(VersionStatus.PENDING_APPROVAL, "lan", "lan", "minh", APPROVER))
                .containsExactly("approve", "reject");
        assertThat(VersionActions.allowed(VersionStatus.PENDING_APPROVAL, "lan", "lan", "dan", READER)).isEmpty();
        // the author may not approve even when someone else requested it
        assertThat(VersionActions.allowed(VersionStatus.PENDING_APPROVAL, "lan", "minh", "lan", AUTHOR_APPROVER))
                .containsExactly("reject");
        // v3 draft by lan
        assertThat(VersionActions.allowed(VersionStatus.DRAFT, "lan", null, "lan", AUTHOR_APPROVER))
                .containsExactly("request-publish");
        assertThat(VersionActions.allowed(VersionStatus.DRAFT, "lan", null, "minh", APPROVER)).isEmpty();
        // v1 published, and retired versions
        for (String user : new String[] {"lan", "minh", "dan"}) {
            assertThat(VersionActions.allowed(VersionStatus.PUBLISHED, "lan", "lan", user, AUTHOR_APPROVER)).isEmpty();
            assertThat(VersionActions.allowed(VersionStatus.RETIRED, "lan", "lan", user, AUTHOR_APPROVER)).isEmpty();
        }
    }

    @Test
    void RLE_043_allowed_actions_are_a_hint_and_the_command_still_checks() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion pending = versions.requestPublish("card-fee", 1, WriteOptions.none());
        assertThat(VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "lan",
                AUTHOR_APPROVER)).doesNotContain("approve");

        assertThatThrownBy(() -> versions.approve("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    // ---------------------------------------------------------------- helpers

    /** card-fee v1 by lan, published by minh on 1 Dec 2026. */
    void publishedCardFee() {
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "minh", APPROVER);
        versions.approve("card-fee", 1, WriteOptions.none());
    }

    /** card-fee.yaml with R2's fee replaced and the rule set renamed. */
    static byte[] cardFee(String r2Fee, String name) {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"" + r2Fee + "\" }")
                .replace("ruleSet: card-fee", "ruleSet: " + name)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A clock the test moves between steps. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
