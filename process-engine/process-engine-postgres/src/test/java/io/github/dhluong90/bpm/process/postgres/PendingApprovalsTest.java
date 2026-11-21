package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PendingApproval;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRE-190 (#31) on PostgreSQL: the pending approvals list of the cockpit's approvals queue. At bank-a the pipeline
 * imports kyc-onboarding v1 and v2 and loan-origination v1, alice requests approval of kyc-onboarding v1 (5 April),
 * loan-origination v1 (6 April) and kyc-onboarding v2 (7 April); v1 stays pending when v2 is requested, so it is
 * pending and not the latest. bob approves, dan only reads, and an auditor of bank-b checks tenant isolation.
 */
class PendingApprovalsTest {

    private static final String[] PIPELINE = {"processes:deploy", "processes:read", "processes:start"};
    private static final String[] AUTHOR = {"processes:author", "processes:read"};
    private static final String[] APPROVER = {"processes:approve", "processes:read"};

    private final TestClock clock = TestClock.at("2027-04-05T03:00:00Z");
    private TestEngine engine;
    private DefinitionVersion kycV1;
    private DefinitionVersion kycV2;
    private DefinitionVersion loanV1;

    private static byte[] bpmn(String resource, String step) {
        return new String(TestEngine.resource(resource), StandardCharsets.UTF_8)
                .replace("Review identity documents", step).getBytes(StandardCharsets.UTF_8);
    }

    private DefinitionVersion imported(byte[] document) {
        engine.identity.as("bank-a", "pipeline", PIPELINE);
        return engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, document, null));
    }

    private DefinitionVersion requested(DefinitionVersion draft, String requester) {
        engine.identity.as("bank-a", requester, AUTHOR);
        return engine.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);
    }

    @BeforeEach
    void setUp() {
        TestEngine.reset();
        engine = TestEngine.replica("pod-1", clock).autoApprove(false).build();
        DefinitionVersion draft1 = imported(bpmn("/bpmn/kyc-onboarding.bpmn", "Review identity documents"));
        DefinitionVersion draft2 = imported(bpmn("/bpmn/kyc-onboarding.bpmn", "Review identity documents and address"));
        DefinitionVersion loan = imported(TestEngine.resource("/bpmn/loan-origination.bpmn"));
        kycV1 = requested(draft1, "alice");
        clock.advance(Duration.ofDays(1));
        loanV1 = requested(loan, "alice");
        clock.advance(Duration.ofDays(1));
        kycV2 = requested(draft2, "alice");
    }

    private List<PendingApproval> list(String tenant, String user, String... permissions) {
        engine.identity.as(tenant, user, permissions);
        return engine.queries.pendingApprovals(PageRequest.first()).items();
    }

    @Test
    void PRE_190_lists_every_pending_version_oldest_request_first_including_one_that_is_not_the_latest() {
        // When bob, who holds processes:approve and made none of them, lists
        List<PendingApproval> items = list("bank-a", "bob", APPROVER);

        // Then kyc-onboarding v1 (not the latest of its key) comes first, with the hash a reviewer must send back
        assertThat(items).extracting(PendingApproval::key, PendingApproval::version, PendingApproval::requestedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("kyc-onboarding", 1, "alice"),
                        org.assertj.core.groups.Tuple.tuple("loan-origination", 1, "alice"),
                        org.assertj.core.groups.Tuple.tuple("kyc-onboarding", 2, "alice"));
        assertThat(items).extracting(PendingApproval::definitionHash).containsExactly(kycV1.definitionHash(),
                loanV1.definitionHash(), kycV2.definitionHash());
        assertThat(items).extracting(PendingApproval::lockVersion).containsExactly(kycV1.lockVersion(),
                loanV1.lockVersion(), kycV2.lockVersion());
        assertThat(items.getFirst().requestedAt()).isEqualTo(java.time.Instant.parse("2027-04-05T03:00:00Z"));
        assertThat(items.getFirst().allowedActions()).containsExactly("approve", "reject");
        assertThat(items.getFirst().deniedActions()).isEmpty();
    }

    @Test
    void PRE_190_the_requester_gets_neither_approve_nor_reject_and_the_reason_as_a_code() {
        // When alice, who requested all three and holds processes:approve too, lists
        List<PendingApproval> items = list("bank-a", "alice", "processes:approve", "processes:read");

        // Then she may do nothing with her own requests
        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.allowedActions()).isEmpty();
            assertThat(item.deniedActions()).containsEntry("approve", "SELF_APPROVAL_FORBIDDEN")
                    .containsEntry("reject", "SELF_APPROVAL_FORBIDDEN");
        });
    }

    @Test
    void PRE_190_the_maker_may_reject_but_not_approve_under_the_default_makers_rule() {
        // When the pipeline, which imported all three, holds processes:approve as well
        List<PendingApproval> items = list("bank-a", "pipeline", "processes:approve", "processes:read");

        // Then approve is denied as a self-approval, and reject is offered, as the commands decide
        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.allowedActions()).containsExactly("reject");
            assertThat(item.deniedActions()).containsOnlyKeys("approve")
                    .containsEntry("approve", "SELF_APPROVAL_FORBIDDEN");
        });
    }

    @Test
    void PRE_190_a_reader_without_approve_sees_the_queue_with_permission_required() {
        List<PendingApproval> items = list("bank-a", "dan", "processes:read");

        assertThat(items).hasSize(3).allSatisfy(item -> {
            assertThat(item.allowedActions()).isEmpty();
            assertThat(item.deniedActions()).containsEntry("approve", "PERMISSION_REQUIRED")
                    .containsEntry("reject", "PERMISSION_REQUIRED");
        });
    }

    @Test
    void PRE_190_pages_by_signed_cursor_and_a_decided_version_leaves_the_queue() {
        // Given bob lists one at a time
        engine.identity.as("bank-a", "bob", APPROVER);
        Page<PendingApproval> first = engine.queries.pendingApprovals(new PageRequest(1, null));
        Page<PendingApproval> second = engine.queries.pendingApprovals(new PageRequest(1, first.nextCursor()));
        Page<PendingApproval> third = engine.queries.pendingApprovals(new PageRequest(1, second.nextCursor()));

        // Then each page carries the next and the last has none
        assertThat(first.items()).extracting(PendingApproval::key, PendingApproval::version)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("kyc-onboarding", 1));
        assertThat(second.items()).extracting(PendingApproval::key).containsExactly("loan-origination");
        assertThat(third.items()).extracting(PendingApproval::version).containsExactly(2);
        assertThat(third.nextCursor()).isNull();

        // When bob approves the loan-origination version naming its hash, it is no longer pending
        engine.services.approve(loanV1.ref(), loanV1.lockVersion(), loanV1.definitionHash(), "checked", "ap-1");
        assertThat(engine.queries.pendingApprovals(PageRequest.first()).items())
                .extracting(PendingApproval::key).containsExactly("kyc-onboarding", "kyc-onboarding");
    }

    @Test
    void PRE_190_another_tenant_sees_none_and_its_cursor_is_not_ours() {
        // Given bank-a's cursor
        engine.identity.as("bank-a", "bob", APPROVER);
        String cursor = engine.queries.pendingApprovals(new PageRequest(1, null)).nextCursor();

        // Then an auditor of bank-b sees none of bank-a's versions, and bank-a's cursor is refused
        assertThat(list("bank-b", "eve", "processes:read")).isEmpty();
        assertThatThrownBy(() -> engine.queries.pendingApprovals(new PageRequest(1, cursor)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void PRE_190_a_forged_or_cross_list_cursor_a_bad_limit_and_a_missing_permission_are_refused() {
        // Given bob has a cursor of the definition list
        engine.identity.as("bank-a", "bob", APPROVER);
        String definitions = engine.queries.definitions(new PageRequest(1, null)).nextCursor();
        String queue = engine.queries.pendingApprovals(new PageRequest(1, null)).nextCursor();

        // Then an unsigned payload, an edited signature, another list's cursor and a bad limit are QUERY_INVALID
        String unsigned = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "pending-approvals:2027-04-05T03:00:00Z|01ARZ3NDEKTSV4RRFFQ69G5FAV".getBytes(StandardCharsets.UTF_8));
        for (String cursor : new String[] {unsigned, queue.substring(0, queue.indexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA",
                definitions, "not-a-cursor"}) {
            assertThatThrownBy(() -> engine.queries.pendingApprovals(new PageRequest(null, cursor))).as(cursor)
                    .isInstanceOf(QueryInvalidException.class);
        }
        assertThatThrownBy(() -> engine.queries.pendingApprovals(new PageRequest(500, null)))
                .isInstanceOf(QueryInvalidException.class);

        // And a caller without processes:read gets nothing
        engine.identity.as("bank-a", "teller", "processes:start");
        assertThatThrownBy(() -> engine.queries.pendingApprovals(PageRequest.first()))
                .isInstanceOf(PermissionDeniedException.class);
    }
}
