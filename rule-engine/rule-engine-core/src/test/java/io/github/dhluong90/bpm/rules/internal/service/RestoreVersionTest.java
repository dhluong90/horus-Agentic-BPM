package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Restore an earlier version as the next draft (RLE-062, RLE-063, #40). Users of {@code bank-a}: lan authors, minh
 * approves, dan only reads. The default settings keep {@code authoring.cockpit-drafts} off, as in 0.1.0 (PD-121).
 */
class RestoreVersionTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> READER = Set.of("rules:read");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2027-01-10T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final UnmaskedPersonalDataTest.Policy policy = new UnmaskedPersonalDataTest.Policy();
    final RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
            EngineSettings.defaults(), policy), stores, stores, stores);
    final VersionService versions = engines.versionService();

    /** The card fee of a SILVER customer with 3 or more transactions. */
    static String cardFee(String fee) {
        return """
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string, mask: false }
                  - { name: txCount, type: integer, mask: false }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: { tier: "== 'SILVER'", txCount: ">= 3" }, then: { fee: "%s" } }
                  - { id: R3, when: {}, then: { fee: "50000 VND" } }
                """.formatted(fee);
    }

    static ImportRequest yaml(String text) {
        return ImportRequest.yaml(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Imports and requests as lan, approves as minh at {@code approvedAt}. */
    RuleSetVersion publish(String tenant, String document, Instant approvedAt) {
        return publish(versions, tenant, "card-fee", document, approvedAt);
    }

    RuleSetVersion publish(VersionService service, String tenant, String ruleSet, String document, Instant approvedAt) {
        caller.become(tenant, "lan", AUTHOR);
        int number = service.findRuleSet(ruleSet).isPresent()
                ? service.importVersion(ruleSet, yaml(document)).version()
                : service.importRuleSet(yaml(document)).version();
        long reviewed = service.requestPublish(ruleSet, number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.become(tenant, "minh", APPROVER);
        return service.approve(ruleSet, number, new WriteOptions(null, reviewed, null));
    }

    /** card-fee v1 at 10000 VND, superseded by v2 at 30000 VND, which is in effect. */
    void v1RetiredAndV2InEffect() {
        publish("bank-a", cardFee("10000 VND"), Instant.parse("2027-01-11T00:00:00Z"));
        publish("bank-a", cardFee("30000 VND"), Instant.parse("2027-01-20T00:00:00Z"));
    }

    @Test
    void RLE_062_an_earlier_version_is_restored_as_the_next_draft_and_needs_approval() {
        // Given card-fee v1 (10000 VND) RETIRED, superseded by v2 (30000 VND) in effect, and the default settings
        v1RetiredAndV2InEffect();
        caller.become("bank-a", "lan", READER);
        RuleSetVersion v1 = versions.find("card-fee", 1, false).orElseThrow();
        RuleSetVersion v2 = versions.find("card-fee", 2, false).orElseThrow();
        List<VersionHistoryEntry> historyV1 = versions.history("card-fee", 1);
        List<VersionHistoryEntry> historyV2 = versions.history("card-fee", 2);
        assertThat(v1.status()).isEqualTo(VersionStatus.RETIRED);
        clock.set(Instant.parse("2027-02-01T09:00:00Z"));

        // When lan restores v1 under k-601, then repeats it
        caller.become("bank-a", "lan", AUTHOR);
        int events = stores.outbox.size();
        RuleSetVersion v3 = versions.copyVersion("card-fee", 1, new WriteOptions("k-601", null, null));
        RuleSetVersion repeat = versions.copyVersion("card-fee", 1, new WriteOptions("k-601", null, null));

        // Then v3 is a DRAFT by lan with v1's canonical hash and format, no effective date, copiedFrom 1
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v3.createdBy()).isEqualTo("lan");
        assertThat(v3.canonicalHash()).isEqualTo(v1.canonicalHash());
        assertThat(v3.canonicalDocument()).isEqualTo(v1.canonicalDocument());
        assertThat(v3.effectiveFrom()).isNull();
        assertThat(v3.approvedBy()).isNull();
        assertThat(v3.copiedFrom()).isEqualTo(1);
        assertThat(v3.sourceFormat()).isEqualTo(v1.sourceFormat());
        assertThat(v3.sourceHash()).isEqualTo(v1.sourceHash());
        assertThat(versions.find("card-fee", 3, true).orElseThrow().sourceDocument())
                .isEqualTo(versions.find("card-fee", 1, true).orElseThrow().sourceDocument());
        assertThat(versions.find("card-fee", 3, false).orElseThrow().copiedFrom()).isEqualTo(1);

        // And one CREATED history row naming v1 and one created event with copiedFrom 1
        List<VersionHistoryEntry> historyV3 = versions.history("card-fee", 3);
        assertThat(historyV3).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(LifecycleAction.CREATED);
            assertThat(row.actor()).isEqualTo("lan");
            assertThat(row.copiedFrom()).isEqualTo(1);
        });
        JsonNode event = EngineJson.JSON.readTree(stores.outbox.get(events).payload());
        assertThat(stores.outbox).hasSize(events + 1);
        assertThat(event.path("eventType").asString()).isEqualTo("rules.version.created.v1");
        assertThat(event.path("version").asInt()).isEqualTo(3);
        assertThat(event.path("copiedFrom").asInt()).isEqualTo(1);

        // And the repeat returns the same result and creates no v4
        assertThat(repeat).isEqualTo(v3);
        assertThat(versions.find("card-fee", 4, false)).isEmpty();

        // And v1 and v2 are unchanged
        assertThat(versions.find("card-fee", 1, false).orElseThrow()).isEqualTo(v1);
        assertThat(versions.find("card-fee", 2, false).orElseThrow()).isEqualTo(v2);
        assertThat(versions.history("card-fee", 1)).isEqualTo(historyV1);
        assertThat(versions.history("card-fee", 2)).isEqualTo(historyV2);

        // And lan cannot approve her own restore: whoever restores a version is its maker
        long reviewed = versions.requestPublish("card-fee", 3, WriteOptions.none()).lockVersion();
        assertThat(historyV1.getFirst().copiedFrom()).isNull();
        caller.become("bank-a", "lan", Set.of("rules:read", "rules:author", "rules:approve"));
        assertThatThrownBy(() -> versions.approve("card-fee", 3, new WriteOptions(null, reviewed, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        // And minh sees the change against v2 and none against v1
        caller.become("bank-a", "minh", APPROVER);
        VersionDiff againstV2 = versions.diff("card-fee", 3, 2);
        assertThat(againstV2.rows()).singleElement().satisfies(row -> {
            assertThat(row.ruleId()).isEqualTo("R2");
            assertThat(row.change()).isEqualTo(VersionDiff.Change.CHANGED);
            assertThat(row.fields()).singleElement().satisfies(f -> {
                assertThat(f.from()).isEqualTo("{\"currency\":\"VND\",\"amount\":30000}");
                assertThat(f.to()).isEqualTo("{\"currency\":\"VND\",\"amount\":10000}");
            });
        });
        VersionDiff againstV1 = versions.diff("card-fee", 3, 1);
        assertThat(againstV1.rows()).isEmpty();
        assertThat(againstV1.canonicalHash()).isEqualTo(againstV1.baseCanonicalHash());

        // And minh's approval publishes v3 and retires v2 as superseded; v3 serves 10000 VND
        RuleSetVersion approved = versions.approve("card-fee", 3, new WriteOptions(null, reviewed, null));
        assertThat(approved.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(approved.effectiveFrom()).isEqualTo(Instant.parse("2027-02-01T09:00:00Z"));
        assertThat(versions.find("card-fee", 2, false).orElseThrow().status()).isEqualTo(VersionStatus.RETIRED);
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
        var decision = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)));
        assertThat(decision.version()).isEqualTo(3);
        assertThat(decision.outputs()).containsEntry("fee", Money.ofMinor("VND", 10000));
        caller.become("bank-a", "dan", READER);
        assertThat(List.of(1, 2, 3)).allSatisfy(v -> assertThat(versions.history("card-fee", v)).isNotEmpty());
        assertThat(versions.history("card-fee", 3).getFirst().copiedFrom()).isEqualTo(1);
    }

    @Test
    void RLE_062_a_pending_or_published_version_can_be_restored_too() {
        // Given v1 PUBLISHED and v2 PENDING_APPROVAL
        publish("bank-a", cardFee("10000 VND"), Instant.parse("2027-01-11T00:00:00Z"));
        caller.become("bank-a", "lan", AUTHOR);
        versions.importVersion("card-fee", yaml(cardFee("30000 VND")));
        versions.requestPublish("card-fee", 2, WriteOptions.none());

        // When lan restores each of them
        RuleSetVersion fromPublished = versions.copyVersion("card-fee", 1);
        RuleSetVersion fromPending = versions.copyVersion("card-fee", 2);

        // Then both are drafts with no approval of their own
        assertThat(fromPublished).extracting(RuleSetVersion::version, RuleSetVersion::status, RuleSetVersion::copiedFrom,
                RuleSetVersion::approvedBy).containsExactly(3, VersionStatus.DRAFT, 1, null);
        assertThat(fromPending).extracting(RuleSetVersion::version, RuleSetVersion::status, RuleSetVersion::copiedFrom,
                RuleSetVersion::requestedBy).containsExactly(4, VersionStatus.DRAFT, 2, null);
    }

    @Test
    void RLE_063_a_restore_passes_todays_import_checks_and_refuses_what_it_cannot_copy() {
        // Given bank-b in strict mode whose kyc-routing v1, approved before strict mode, has nationalId with no mask,
        // and bank-a's card-fee v1 and v2
        // (the approval of a warning needs no acknowledgement on the host that published it before the warning existed)
        VersionService lenient = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withAcknowledgeWarnings(List.of()), policy), stores, stores, stores)
                .versionService();
        publish(lenient, "bank-b", "kyc-routing", UnmaskedPersonalDataTest.KYC_ROUTING,
                Instant.parse("2027-01-11T00:00:00Z"));
        policy.strict.add("bank-b");
        v1RetiredAndV2InEffect();
        int rowsBefore = stores.history.size();
        int eventsBefore = stores.outbox.size();
        int versionsBefore = stores.versions.size();

        // When an author in bank-b restores kyc-routing v1
        caller.become("bank-b", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.copyVersion("kyc-routing", 1, new WriteOptions("k-602", null, null)))
                // Then it is VALIDATION_FAILED naming nationalId and the setting
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .anySatisfy(v -> {
                            assertThat(v.column()).isEqualTo("nationalId");
                            assertThat(v.message()).contains("decision-log.require-explicit-mask");
                        }));

        // And in bank-a: version 9 is not found, 0 is invalid, dan is refused
        caller.become("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 9)).isInstanceOf(VersionNotFoundException.class);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 0)).isInstanceOf(InputInvalidException.class);
        assertThatThrownBy(() -> versions.copyVersion("no-such-set", 1)).isInstanceOf(RuleSetNotFoundException.class);
        caller.become("bank-a", "dan", READER);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 1)).isInstanceOf(PermissionDeniedException.class);
        // and a bank-b caller cannot see bank-a's card-fee at all
        caller.become("bank-b", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 1)).isInstanceOf(RuleSetNotFoundException.class);

        // And no failure wrote a version, a history row, an event or an idempotency record
        assertThat(stores.history).hasSize(rowsBefore);
        assertThat(stores.outbox).hasSize(eventsBefore);
        assertThat(stores.versions.size()).isEqualTo(versionsBefore);
        // while the same restore passes once strict mode is off (the key k-602 was not consumed by the failure)
        policy.strict.clear();
        assertThat(versions.copyVersion("kyc-routing", 1, new WriteOptions("k-602", null, null)).version()).isEqualTo(2);
    }

    @Test
    void RLE_063_a_restore_is_checked_against_the_hosts_step_budget_of_today() {
        // Given v1 approved under the default budget, then a host that lowers evaluation.step-budget to 5
        publish("bank-a", cardFee("10000 VND"), Instant.parse("2027-01-11T00:00:00Z"));
        RuleEngines tight = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores,
                EngineSettings.defaults().withEvaluationLimits(
                        new io.github.dhluong90.bpm.expr.internal.EvaluationLimits(5, 1000)), policy), stores, stores,
                stores);

        // When lan restores v1 on that host
        caller.become("bank-a", "lan", AUTHOR);

        // Then it is refused as an import of the same document would be
        assertThatThrownBy(() -> tight.versionService().copyVersion("card-fee", 1))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .anySatisfy(v -> assertThat(v.code()).isEqualTo("EVALUATION_BUDGET_EXCEEDED")));
    }
}
