package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.AuthorUnknownException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.VersionActions;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A pending version with no recorded author cannot be approved (RLE-054, PD-105), through the public services on the
 * in-memory stores. Versions written before the author was recorded carry none; the test clears it on a card fee
 * version requested by lan. bob and carol hold {@code rules:approve}. Nobody's approval passes, nothing is written,
 * and the change goes through once it is imported again with its author.
 */
class AuthorUnknownTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> AUTHOR_APPROVER = Set.of("rules:read", "rules:author", "rules:approve");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    VersionService versions;
    RuleSetVersion pending;

    @BeforeEach
    void setUp() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withRequireContentHash(false)), stores, stores, stores);
        engines.cursorKeys().load();
        versions = engines.versionService();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
        versions.requestPublish("card-fee", 1, WriteOptions.idempotencyKey("rp-1"));
        // As a row written before the author column existed: pending, requested by lan, no author
        StoredVersion stored = stores.versions.getFirst();
        stores.versions.set(0, withoutAuthor(stored));
        pending = versions.find("card-fee", 1, false).orElseThrow();
    }

    static StoredVersion withoutAuthor(StoredVersion v) {
        return new StoredVersion(v.tenantId(), v.id(), v.ruleSetId(), v.ruleSet(), v.version(), v.status(),
                v.hitPolicy(), v.effectiveFrom(), v.canonicalJson(), v.canonicalHash(), v.sourceFormat(),
                v.sourceDocument(), v.sourceHash(), v.lockVersion(), v.requestedBy(), v.requestedAt(), v.approvedBy(),
                v.approvedAt(), v.approvalComment(), null, v.createdAt(), v.retiredAt());
    }

    WriteOptions approval(String key) {
        return new WriteOptions(key, pending.lockVersion(), "reviewed");
    }

    @Test
    void RLE_054_a_pending_version_with_no_recorded_author_cannot_be_approved() {
        // Given card-fee v1 pending with no recorded author
        assertThat(pending.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(pending.createdBy()).isNull();
        int history = stores.history.size();
        int events = stores.outbox.size();

        // When / Then: bob and carol, neither of them the requester, are both refused with AUTHOR_UNKNOWN
        for (String approver : new String[] {"bob", "carol"}) {
            caller.become("bank-a", approver, APPROVER);
            assertThatThrownBy(() -> versions.approve("card-fee", 1, approval("ap-" + approver)))
                    .isInstanceOfSatisfying(AuthorUnknownException.class, e -> {
                        assertThat(e.code()).isEqualTo("AUTHOR_UNKNOWN");
                        assertThat(e.getMessage()).contains("card-fee v1", "no recorded author", "import the change again");
                    });
        }

        // Then nothing is written and no author is invented
        RuleSetVersion stored = versions.find("card-fee", 1, false).orElseThrow();
        assertThat(stored.status()).isEqualTo(VersionStatus.PENDING_APPROVAL);
        assertThat(stored.lockVersion()).isEqualTo(pending.lockVersion());
        assertThat(stored.createdBy()).isNull();
        assertThat(stored.approvedBy()).isNull();
        assertThat(stores.history).hasSize(history);
        assertThat(stores.outbox).hasSize(events);
    }

    @Test
    void RLE_054_the_requester_is_still_refused_by_four_eyes_first() {
        // Given lan, who requested v1, also holds rules:approve
        caller.become("bank-a", "lan", AUTHOR_APPROVER);

        // When / Then: the four-eyes check comes before the unknown-author check (DESIGN 3.3)
        assertThatThrownBy(() -> versions.approve("card-fee", 1, approval("ap-lan")))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void RLE_054_allowed_actions_offer_reject_but_not_approve() {
        // When
        var forBob = VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "bob",
                APPROVER);
        var forLan = VersionActions.allowed(pending.status(), pending.createdBy(), pending.requestedBy(), "lan",
                AUTHOR_APPROVER);

        // Then
        assertThat(forBob).containsExactly(VersionActions.REJECT);
        assertThat(forLan).containsExactly(VersionActions.WITHDRAW);
    }

    @Test
    void RLE_054_rejected_or_withdrawn_then_imported_again_the_change_is_approved() {
        // Given bob rejects the author-less v1, which stays allowed
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion rejected = versions.reject("card-fee", 1,
                new WriteOptions("rj-1", pending.lockVersion(), "author unknown, submit again"));
        assertThat(rejected.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(rejected.createdBy()).isNull();

        // When lan imports the same change again and requests publication
        caller.become("bank-a", "lan", AUTHOR);
        RuleSetVersion v2 = versions.importVersion("card-fee", ImportRequest.yaml(CardFee.yaml()));
        RuleSetVersion requested = versions.requestPublish("card-fee", v2.version(), WriteOptions.idempotencyKey("rp-2"));

        // Then the new version records lan as author, and bob approves it
        assertThat(requested.createdBy()).isEqualTo("lan");
        caller.become("bank-a", "bob", APPROVER);
        RuleSetVersion published = versions.approve("card-fee", v2.version(),
                new WriteOptions("ap-2", requested.lockVersion(), "reviewed"));
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(published.createdBy()).isEqualTo("lan");
        assertThat(published.approvedBy()).isEqualTo("bob");
    }

    @Test
    void RLE_054_the_requester_may_withdraw_an_author_less_version() {
        // When lan takes the request back
        RuleSetVersion withdrawn = versions.withdraw("card-fee", 1,
                new WriteOptions("wd-1", pending.lockVersion(), "submitting again with an author"));

        // Then
        assertThat(withdrawn.status()).isEqualTo(VersionStatus.DRAFT);
    }
}
