package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.EffectiveDateInPastException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A publication request can name the date the version takes effect (RLE-210, #470). At bank-a on 15 January 2027 lan
 * requests publication of {@code card-fee} v1 to take effect on 1 February 2027 and bob approves.
 */
class RequestedEffectiveDateTest {

    static final Instant JAN_15 = Instant.parse("2027-01-15T02:00:00Z");
    static final Instant FEB_1 = Instant.parse("2027-02-01T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    VersionService versions;

    @BeforeEach
    void draft() {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(JAN_15, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withDraftAuthoring(true)), stores, stores, stores);
        engines.cursorKeys().load();
        versions = engines.versionService();
        caller.become("bank-a", "lan", AUTHOR);
        versions.importRuleSet(ImportRequest.yaml(CardFee.yaml()));
    }

    @Test
    void RLE_210_a_requested_effective_date_is_stored_shown_to_the_approver_and_applied() {
        RuleSetVersion pending = versions.requestPublish("card-fee", 1,
                WriteOptions.none().withEffectiveFrom(FEB_1));
        assertThat(pending.effectiveFrom()).isEqualTo(FEB_1);

        caller.become("bank-a", "bob", APPROVER);
        PendingApproval item = versions.pendingApprovals(null).items().get(0);
        assertThat(item.effectiveFrom()).isEqualTo(FEB_1);

        RuleSetVersion published = versions.approve("card-fee", 1,
                new WriteOptions("ap-1", item.lockVersion(), null).expectingContent(item.canonicalHash()));
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(published.effectiveFrom()).isEqualTo(FEB_1);
    }

    @Test
    void RLE_210_without_a_date_the_version_takes_effect_at_approval() {
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.become("bank-a", "bob", APPROVER);
        PendingApproval item = versions.pendingApprovals(null).items().get(0);
        assertThat(item.effectiveFrom()).isNull();
        RuleSetVersion published = versions.approve("card-fee", 1,
                new WriteOptions("ap-1", item.lockVersion(), null).expectingContent(item.canonicalHash()));
        assertThat(published.effectiveFrom()).isEqualTo(JAN_15);
    }

    @Test
    void RLE_210_a_past_effective_date_is_refused_and_the_draft_stays_a_draft() {
        int history = stores.history.size();
        assertThatThrownBy(() -> versions.requestPublish("card-fee", 1,
                WriteOptions.none().withEffectiveFrom(JAN_15.minusSeconds(1))))
                .isInstanceOfSatisfying(EffectiveDateInPastException.class,
                        e -> assertThat(e.code()).isEqualTo("EFFECTIVE_DATE_IN_PAST"));
        assertThat(versions.find("card-fee", 1, false).orElseThrow().status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(stores.history).hasSize(history);
        // now itself is allowed
        assertThat(versions.requestPublish("card-fee", 1, WriteOptions.none().withEffectiveFrom(JAN_15)).status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
    }

    @Test
    void RLE_210_the_effective_date_does_not_change_the_hash_or_the_four_eyes() {
        RuleSetVersion dated = versions.requestPublish("card-fee", 1, WriteOptions.none().withEffectiveFrom(FEB_1));
        RuleSetVersion plain = versions.withdraw("card-fee", 1, new WriteOptions("wd", dated.lockVersion(), null));
        RuleSetVersion again = versions.requestPublish("card-fee", 1, new WriteOptions("rq", plain.lockVersion(), null));
        assertThat(again.canonicalHash()).isEqualTo(dated.canonicalHash());

        // the requester still cannot approve, whatever date was asked for
        caller.become("bank-a", "lan", Set.of("rules:read", "rules:author", "rules:approve"));
        assertThatThrownBy(() -> versions.approve("card-fee", 1,
                new WriteOptions("ap-x", again.lockVersion(), null).expectingContent(again.canonicalHash())))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }
}
