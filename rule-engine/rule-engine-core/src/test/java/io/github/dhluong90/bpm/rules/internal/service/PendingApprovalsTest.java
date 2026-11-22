package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RLE-190 (#31): the pending approvals list of the cockpit's approvals queue, through the public services on the
 * in-memory stores. At bank-a, lan authors and requests card-fee v1 (10 January) and v2 (12 January) and ana requests
 * fx-margin v1 (11 January); card-fee v1 stays pending when v2 is requested, so it is pending and not the latest.
 * bob approves, dan only reads, and an auditor of bank-b checks tenant isolation.
 */
class PendingApprovalsTest {

    static final Set<String> AUTHOR_APPROVER = Set.of("rules:read", "rules:author", "rules:approve");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> READER = Set.of("rules:read");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2027-01-10T02:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, clock, stores, stores, EngineSettings.defaults()), stores, stores, stores);
    final VersionService versions = engines.versionService();
    RuleSetVersion cardFeeV1;
    RuleSetVersion cardFeeV2;
    RuleSetVersion fxMarginV1;

    @BeforeEach
    void setUp() {
        engines.cursorKeys().load();
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.importVersion("card-fee", ImportRequest.yaml(EditorApiTest.cardFee("12000 VND", "card-fee")));
        cardFeeV1 = versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "ana", AUTHOR_APPROVER);
        clock.set(Instant.parse("2027-01-11T02:00:00Z"));
        versions.importRuleSet(ImportRequest.yaml(EditorApiTest.cardFee("10000 VND", "fx-margin")));
        fxMarginV1 = versions.requestPublish("fx-margin", 1, WriteOptions.none());
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        clock.set(Instant.parse("2027-01-12T02:00:00Z"));
        cardFeeV2 = versions.requestPublish("card-fee", 2, WriteOptions.none());
    }

    @Test
    void RLE_190_lists_every_pending_version_oldest_request_first_including_one_that_is_not_the_latest() {
        // Given bob, who holds rules:approve and made none of them
        caller.become("bank-a", "bob", APPROVER);

        // When
        Page<PendingApproval> page = versions.pendingApprovals(PageRequest.first());

        // Then card-fee v1 (not the latest of its rule set) is there, with the hash a reviewer must send back
        assertThat(page.items()).extracting(PendingApproval::ruleSet, PendingApproval::version,
                PendingApproval::requestedBy, PendingApproval::requestedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("card-fee", 1, "lan", Instant.parse("2027-01-10T02:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple("fx-margin", 1, "ana", Instant.parse("2027-01-11T02:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple("card-fee", 2, "lan", Instant.parse("2027-01-12T02:00:00Z")));
        assertThat(page.items()).extracting(PendingApproval::canonicalHash).containsExactly(
                cardFeeV1.canonicalHash(), fxMarginV1.canonicalHash(), cardFeeV2.canonicalHash());
        assertThat(page.items()).extracting(PendingApproval::lockVersion)
                .containsExactly(cardFeeV1.lockVersion(), fxMarginV1.lockVersion(), cardFeeV2.lockVersion());
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void RLE_190_the_proposer_gets_no_approve_and_no_reject_and_the_reason_as_a_code() {
        // Given lan, who requested card-fee v1 and v2 and holds approve as well as author
        caller.become("bank-a", "lan", AUTHOR_APPROVER);

        // When
        List<PendingApproval> items = versions.pendingApprovals(PageRequest.first()).items();

        // Then lan may only withdraw their own requests and may decide on ana's
        PendingApproval own = items.get(0);
        assertThat(own.allowedActions()).containsExactly("withdraw");
        assertThat(own.deniedActions()).containsEntry("approve", "SELF_APPROVAL_FORBIDDEN")
                .containsEntry("reject", "SELF_APPROVAL_FORBIDDEN");
        PendingApproval others = items.get(1);
        assertThat(others.ruleSet()).isEqualTo("fx-margin");
        assertThat(others.allowedActions()).containsExactly("approve", "reject");
        assertThat(others.deniedActions()).isEmpty();
    }

    @Test
    void RLE_190_a_reader_without_approve_sees_the_queue_with_permission_required() {
        // Given dan, who holds rules:read only
        caller.become("bank-a", "dan", READER);

        // When
        List<PendingApproval> items = versions.pendingApprovals(PageRequest.first()).items();

        // Then
        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.allowedActions()).isEmpty();
            assertThat(item.deniedActions()).containsEntry("approve", "PERMISSION_REQUIRED")
                    .containsEntry("reject", "PERMISSION_REQUIRED");
        });
    }

    @Test
    void RLE_190_an_author_who_is_not_the_requester_may_reject_but_not_approve() {
        // Given lan drafts fx-margin v2 and ana requests it
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        versions.importVersion("fx-margin", ImportRequest.yaml(EditorApiTest.cardFee("9000 VND", "fx-margin")));
        caller.become("bank-a", "ana", AUTHOR_APPROVER);
        versions.requestPublish("fx-margin", 2, WriteOptions.none());

        // When lan, the author of v2, lists
        caller.become("bank-a", "lan", AUTHOR_APPROVER);
        PendingApproval authored = versions.pendingApprovals(PageRequest.first()).items().stream()
                .filter(a -> a.ruleSet().equals("fx-margin") && a.version() == 2).findFirst().orElseThrow();

        // Then
        assertThat(authored.allowedActions()).containsExactly("reject");
        assertThat(authored.deniedActions()).containsOnlyKeys("approve").containsEntry("approve", "SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void RLE_190_pages_by_signed_cursor_and_a_decided_version_leaves_the_queue() {
        // Given bob lists one at a time
        caller.become("bank-a", "bob", APPROVER);
        Page<PendingApproval> first = versions.pendingApprovals(new PageRequest(1, null));
        Page<PendingApproval> second = versions.pendingApprovals(new PageRequest(1, first.nextCursor()));
        Page<PendingApproval> third = versions.pendingApprovals(new PageRequest(1, second.nextCursor()));

        // Then each page carries the next and the last has none
        assertThat(first.items()).extracting(PendingApproval::ruleSet, PendingApproval::version).containsExactly(
                org.assertj.core.groups.Tuple.tuple("card-fee", 1));
        assertThat(second.items()).extracting(PendingApproval::ruleSet).containsExactly("fx-margin");
        assertThat(third.items()).extracting(PendingApproval::version).containsExactly(2);
        assertThat(third.nextCursor()).isNull();

        // When bob approves fx-margin v1, it is no longer pending
        versions.approve("fx-margin", 1, new WriteOptions(null, fxMarginV1.lockVersion(), "ok")
                .expectingContent(fxMarginV1.canonicalHash()));
        assertThat(versions.pendingApprovals(PageRequest.first()).items()).extracting(PendingApproval::ruleSet)
                .containsExactly("card-fee", "card-fee");
    }

    @Test
    void RLE_190_another_tenant_sees_none_and_its_cursor_is_not_ours() {
        // Given bank-a's cursor
        caller.become("bank-a", "bob", APPROVER);
        String cursor = versions.pendingApprovals(new PageRequest(1, null)).nextCursor();

        // When an auditor of bank-b lists, and replays bank-a's cursor
        caller.become("bank-b", "eve", READER);

        // Then none of bank-a's versions show, and the cursor is refused
        assertThat(versions.pendingApprovals(PageRequest.first()).items()).isEmpty();
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(1, cursor)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_190_a_forged_or_cross_list_cursor_a_bad_limit_and_a_missing_permission_are_refused() {
        // Given bob lists the rule sets and the queue
        caller.become("bank-a", "bob", APPROVER);
        String queue = versions.pendingApprovals(new PageRequest(1, null)).nextCursor();
        String ruleSets = versions.listRuleSets(new PageRequest(1, null)).nextCursor();

        // Then an unsigned payload, an edited signature and another list's cursor are QUERY_INVALID
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(null,
                Cursors.encode("pending-approvals", "2027-01-10T02:00:00Z|01ARZ3NDEKTSV4RRFFQ69G5FAV"))))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(null,
                queue.substring(0, queue.indexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA")))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(null, ruleSets)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(500, null)))
                .isInstanceOf(QueryInvalidException.class);
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(0, null)))
                .isInstanceOf(QueryInvalidException.class);

        // And a caller without rules:read gets nothing
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> versions.pendingApprovals(PageRequest.first()))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_190_a_pending_version_with_no_recorded_author_cannot_be_approved_by_anyone_and_says_so() {
        // Given card-fee v1 as written before authors were recorded
        var stored = stores.versions.stream().filter(v -> v.ruleSet().equals("card-fee") && v.version() == 1)
                .findFirst().orElseThrow();
        stores.versions.set(stores.versions.indexOf(stored), AuthorUnknownTest.withoutAuthor(stored));
        caller.become("bank-a", "bob", APPROVER);

        // When
        PendingApproval item = versions.pendingApprovals(PageRequest.first()).items().getFirst();

        // Then
        assertThat(item.ruleSet()).isEqualTo("card-fee");
        assertThat(item.allowedActions()).containsExactly("reject");
        assertThat(item.deniedActions()).containsEntry("approve", "AUTHOR_UNKNOWN");
    }

    @Test
    void RLE_196_each_item_carries_the_requester_display_name_and_falls_back_to_the_id() {
        // Given the host's directory names lan "Tran Thi Lan" and knows no name for ana
        caller.names = java.util.Map.of("lan", "Tran Thi Lan");
        caller.become("bank-a", "bob", APPROVER);

        // When bob lists the queue
        Page<PendingApproval> page = versions.pendingApprovals(PageRequest.first());

        // Then lan's requests show her name, ana's shows the id, and requestedBy keeps the id for the checks
        assertThat(page.items()).extracting(PendingApproval::requestedBy, PendingApproval::requestedByName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("lan", "Tran Thi Lan"),
                        org.assertj.core.groups.Tuple.tuple("ana", "ana"),
                        org.assertj.core.groups.Tuple.tuple("lan", "Tran Thi Lan"));
    }
}
