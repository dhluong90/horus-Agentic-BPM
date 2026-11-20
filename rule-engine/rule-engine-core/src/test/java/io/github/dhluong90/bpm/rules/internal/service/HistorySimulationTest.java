package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.expr.RegexLimits;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Replaying logged decisions through a draft (RLE-015, TR-OBS-01). The card fee v1 charges every customer but GOLD
 * 50,000 VND and bills on {@code today()}; in November 2026 the tellers of {@code bank-a} made 100 decisions, 18 of
 * them for STANDARD customers. Draft v2 lowers the fee to 30,000 VND. Users: lan authors, minh approves, teller
 * evaluates, and the pricing approver simulates and reads the log.
 */
class HistorySimulationTest {

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final Set<String> REPLAYER = Set.of("rules:simulate", "rules:log:read");

    static final Instant NOV_01 = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_15 = Instant.parse("2026-12-15T02:00:00Z");

    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(Instant.parse("2026-10-20T00:00:00Z"));
    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "lan");
    final RuleEngines engines = engines(EngineSettings.defaults());
    final VersionService versions = engines.versionService();
    final RuleEngine engine = engines.ruleEngine();

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings,
                // A masked input is logged as its keyed fingerprint, so the tenant needs a key (RLE-012).
                MaskingPolicy.withKey(MaskingKey.of("history-test-key-of-32-bytes-lng".getBytes(StandardCharsets.UTF_8)))),
                stores, stores, stores);
    }

    void publish(String tenant, String ruleSet, byte[] document) {
        caller.become(tenant, "lan", AUTHOR);
        int number = versions.findRuleSet(ruleSet).isPresent()
                ? versions.importVersion(ruleSet, ImportRequest.yaml(document)).version()
                : versions.importRuleSet(ImportRequest.yaml(document)).version();
        long lock = versions.requestPublish(ruleSet, number, WriteOptions.none()).lockVersion();
        caller.become(tenant, "minh", APPROVER);
        versions.approve(ruleSet, number, new WriteOptions(null, lock, null));
    }

    void draft(String tenant, String ruleSet, byte[] document) {
        caller.become(tenant, "lan", AUTHOR);
        versions.importVersion(ruleSet, ImportRequest.yaml(document));
    }

    void cardFeeWithNovemberHistory() {
        publish("bank-a", "card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "50000 VND"));
        caller.become("bank-a", "teller", TELLER);
        for (int i = 0; i < 100; i++) {
            clock.set(NOV_01.plus(Duration.ofHours(7 * i + 1)));
            // 18 STANDARD customers: i = 0, 6, ..., 96 (17 of them) and the last one, i = 99.
            String tier = (i % 6 == 0 || i == 99) ? "STANDARD" : "GOLD";
            engine.evaluate(DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", tier, "txCount", 1))));
        }
        clock.set(DEC_15);
        draft("bank-a", "card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "30000 VND"));
    }

    static Money vnd(long amount) {
        return Money.ofMinor("VND", amount);
    }

    @Test
    void RLE_015_simulation_leaves_decision_log_untouched() {
        // Given card-fee v1 with 100 logged November decisions and v2 in DRAFT
        cardFeeWithNovemberHistory();
        int decisions = stores.decisions.size();
        int outbox = stores.outbox.size();
        int history = stores.history.size();
        int idempotency = stores.idempotency.size();
        caller.become("bank-a", "pricing-approver", REPLAYER);

        // When the approver replays November through v2
        HistorySimulationResult result =
                engine.simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01));

        // Then 100 replayed, 18 changed, and nothing written anywhere
        assertThat(decisions).isEqualTo(100);
        assertThat(result.version()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(result.items()).hasSize(100);
        assertThat(result.replayedCount()).isEqualTo(100);
        assertThat(result.changedCount()).isEqualTo(18);
        assertThat(result.notReplayableCount()).isZero();
        assertThat(result.truncated()).isFalse();
        assertThat(stores.decisions).hasSize(decisions);
        assertThat(stores.outbox).hasSize(outbox);
        assertThat(stores.history).hasSize(history);
        assertThat(stores.idempotency).hasSize(idempotency);
        // Newest first, each side at the decision's own clock: billedOn is the decision's day, not today
        HistorySimulationResult.Item newest = result.items().getFirst();
        assertThat(newest.evalClock()).isEqualTo(NOV_01.plus(Duration.ofHours(7 * 99 + 1)));
        assertThat(newest.loggedVersion()).isEqualTo(1);
        assertThat(newest.changed()).isTrue();
        assertThat(newest.logged()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", vnd(50_000), "billedOn", LocalDate.of(2026, 11, 29))));
        assertThat(newest.simulated()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", vnd(30_000), "billedOn", LocalDate.of(2026, 11, 29))));
        assertThat(result.items()).filteredOn(item -> !item.changed()).allSatisfy(item -> assertThat(item.simulated())
                .isEqualTo(item.logged()));
    }

    @Test
    void RLE_015_the_logged_side_is_what_was_decided() {
        // Given
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "pricing-approver", REPLAYER);

        // When
        HistorySimulationResult result =
                engine.simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01));

        // Then every logged side equals a replay of the same decision (TR-DET-05)
        caller.become("bank-a", "auditor", Set.of("rules:log:read"));
        for (HistorySimulationResult.Item item : result.items().subList(0, 10)) {
            assertThat(item.logged()).isEqualTo(engine.replay(item.decisionId()).outcome());
        }
    }

    @Test
    void RLE_015_needs_rules_simulate_and_rules_log_read() {
        // Given
        cardFeeWithNovemberHistory();
        HistorySimulation november = HistorySimulation.between(NOV_01, DEC_01);

        // When / Then: either permission alone is refused
        caller.become("bank-a", "simulator", Set.of("rules:simulate"));
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2, november))
                .isInstanceOf(PermissionDeniedException.class);
        caller.become("bank-a", "auditor", Set.of("rules:log:read"));
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2, november))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(stores.decisions).hasSize(100);
    }

    @Test
    void RLE_015_another_tenant_gets_404_and_never_replays_bank_a_decisions() {
        // Given bank-a's history, and bank-b with its own card-fee v1 and draft v2 but no decision
        cardFeeWithNovemberHistory();
        HistorySimulation november = HistorySimulation.between(NOV_01, DEC_01);

        // When bank-b, which has no card-fee yet, asks for version 2
        caller.become("bank-b", "carol", REPLAYER);
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2, november))
                .isInstanceOf(VersionNotFoundException.class)
                .extracting(e -> ((VersionNotFoundException) e).code()).isEqualTo("VERSION_NOT_FOUND");

        // When bank-b replays its own draft over the same period
        publish("bank-b", "card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "50000 VND"));
        draft("bank-b", "card-fee", EffectiveDatesAndReplayTest.cardFee("2026-11-01T00:00:00Z", "30000 VND"));
        caller.become("bank-b", "carol", REPLAYER);
        HistorySimulationResult result = engine.simulateHistory("card-fee", 2, november);

        // Then it sees none of bank-a's 100 decisions, and nothing is written
        assertThat(result.items()).isEmpty();
        assertThat(stores.decisions).hasSize(100).allSatisfy(d -> assertThat(d.tenantId()).isEqualTo("bank-a"));
    }

    @Test
    void RLE_015_the_period_and_the_number_of_decisions_are_bounded() {
        // Given
        cardFeeWithNovemberHistory();
        caller.become("bank-a", "pricing-approver", REPLAYER);

        // When / Then: the period is required, ordered and at most query.max-range (31 days)
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2, new HistorySimulation(null, DEC_01, null)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("from and to are required");
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2, HistorySimulation.between(DEC_01, NOV_01)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("to must be after from");
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, NOV_01.plus(Duration.ofDays(32)))))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("query.max-range");
        // The limit is 1 to simulation.max-inputs (1,000)
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, DEC_01).withLimit(0)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("limit must be 1 to 1000");
        assertThatThrownBy(() -> engine.simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, DEC_01).withLimit(1001)))
                .isInstanceOf(QueryInvalidException.class);
        // A run of 10 replays the 10 newest and says the period held more
        HistorySimulationResult ten =
                engine.simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01).withLimit(10));
        assertThat(ten.items()).hasSize(10);
        assertThat(ten.truncated()).isTrue();
        assertThat(ten.items().getFirst().evalClock()).isEqualTo(NOV_01.plus(Duration.ofHours(7 * 99 + 1)));

        // A host that lowers simulation.max-inputs to 3 gets that bound, and no more than 3 by default
        RuleEngines lowered = engines(EngineSettings.defaults().withSimulationMaxInputs(3));
        caller.become("bank-a", "pricing-approver", REPLAYER);
        HistorySimulationResult three =
                lowered.ruleEngine().simulateHistory("card-fee", 2, HistorySimulation.between(NOV_01, DEC_01));
        assertThat(three.items()).hasSize(3);
        assertThat(three.truncated()).isTrue();
        assertThatThrownBy(() -> lowered.ruleEngine().simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, DEC_01).withLimit(4)))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("limit must be 1 to 3");
        assertThat(stores.decisions).hasSize(100);
    }

    @Test
    void RLE_015_a_decision_with_a_masked_value_is_not_replayable_and_named_without_its_value() {
        // Given a fee whose national id is masked in the log, one decision with a value and one with null
        String masked = """
                schemaVersion: 1
                ruleSet: id-check-fee
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: string, mask: true }
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "%s" } }
                """;
        publish("bank-a", "id-check-fee", masked.formatted("20000 VND").getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "teller", TELLER);
        clock.set(Instant.parse("2026-11-10T03:00:00Z"));
        String withValue = engine.evaluate(DecisionRequest.of("id-check-fee",
                DecisionInput.of(Map.of("nationalId", "079201001234", "tier", "STANDARD")))).decisionId();
        clock.set(Instant.parse("2026-11-11T03:00:00Z"));
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("nationalId", null);
        nullId.put("tier", "STANDARD");
        engine.evaluate(DecisionRequest.of("id-check-fee", DecisionInput.of(nullId)));
        clock.set(DEC_15);
        draft("bank-a", "id-check-fee", masked.formatted("15000 VND").getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "pricing-approver", REPLAYER);

        // When
        HistorySimulationResult result =
                engine.simulateHistory("id-check-fee", 2, HistorySimulation.between(NOV_01, DEC_01));

        // Then the run goes on: the null one replays and changes, the other is named by its input only
        assertThat(result.items()).hasSize(2);
        assertThat(result.replayedCount()).isEqualTo(1);
        assertThat(result.notReplayableCount()).isEqualTo(1);
        assertThat(result.changedCount()).isEqualTo(1);
        HistorySimulationResult.Item notReplayed = result.items().get(1);
        assertThat(notReplayed.decisionId()).isEqualTo(withValue);
        assertThat(notReplayed.maskedInputs()).containsExactly("nationalId");
        assertThat(notReplayed.simulated()).isNull();
        assertThat(notReplayed.logged()).isNull();
        assertThat(notReplayed.changed()).isFalse();
        assertThat(String.valueOf(result)).doesNotContain("079201001234");
    }

    @Test
    void RLE_015_one_regex_budget_covers_every_decision_of_the_run() {
        // Given five logged screenings of a long payment reference, each reading two rows of the expression
        publish("bank-a", "remittance-screening", RegexBoundsTest.screening(2).getBytes(StandardCharsets.UTF_8));
        caller.become("bank-a", "teller", TELLER);
        for (int i = 0; i < 5; i++) {
            clock.set(Instant.parse("2026-11-0" + (i + 2) + "T03:00:00Z"));
            engine.evaluate(DecisionRequest.of("remittance-screening",
                    DecisionInput.of(Map.of("reference", RegexBoundsTest.LONG_REFERENCE))));
        }
        clock.set(DEC_15);
        long perRow = RegexBoundsTest.readsOf("[0-9]*K1", RegexBoundsTest.LONG_REFERENCE);
        // A request budget of five rows: the first decision reads four (both sides), the second runs out
        RuleEngines bounded = engines(EngineSettings.defaults().withRegexLimits(new RegexLimits(1000, 4096,
                RegexLimits.MAX_READS, RegexLimits.MAX_READS_PER_DECISION, 5 * perRow)));
        caller.become("bank-a", "pricing-approver", REPLAYER);

        // When the published v1 is replayed over its own history
        HistorySimulationResult result = bounded.ruleEngine().simulateHistory("remittance-screening", 1,
                HistorySimulation.between(NOV_01, DEC_01));

        // Then the first decision replays unchanged, and the run stops at the same item every time
        assertThat(result.items()).hasSize(5);
        assertThat(result.items().getFirst().changed()).isFalse();
        assertThat(result.items().getFirst().logged()).isInstanceOf(DecisionResult.Matched.class);
        assertThat(result.items().get(1).logged()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                failed -> assertThat(failed.detail()).startsWith("REGEX_TIMEOUT")
                        .contains("evaluation.regex-max-reads-per-request"));
        assertThat(result.items().subList(2, 5)).allSatisfy(item -> assertThat(item.simulated())
                .isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> assertThat(failed.detail())
                        .startsWith("REGEX_TIMEOUT: not evaluated")));
        assertThat(bounded.ruleEngine().simulateHistory("remittance-screening", 1,
                HistorySimulation.between(NOV_01, DEC_01))).isEqualTo(result);
        assertThat(stores.decisions).hasSize(5);
    }
}
