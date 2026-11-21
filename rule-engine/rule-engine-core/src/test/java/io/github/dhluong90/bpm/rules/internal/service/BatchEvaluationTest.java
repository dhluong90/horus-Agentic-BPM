package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import io.github.dhluong90.bpm.rules.api.BatchRequest;
import io.github.dhluong90.bpm.rules.api.BatchResult;
import io.github.dhluong90.bpm.rules.api.BatchTooLargeException;
import io.github.dhluong90.bpm.rules.api.ClockNotAllowedException;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Batch evaluation (RLE-067 to RLE-069): the end-of-day fee run of bank-a. Service {@code svc-eod} prices card fees
 * and loan rates for many items in one call, and one bad item must not stop the rest.
 */
class BatchEvaluationTest {

    static final Instant NOW = Instant.parse("2027-01-31T22:00:00Z");
    static final Set<String> EVALUATE = Set.of("rules:evaluate");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines() {
        return engines(EngineSettings.defaults().withRequireContentHash(false));
    }

    /** Rule sets are published through an engine with the default limits, then served by one with these settings. */
    RuleEngines engines(EngineSettings settings) {
        RuleEngines authoring = RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                stores, stores, EngineSettings.defaults().withRequireContentHash(false)), stores, stores, stores);
        publish(authoring, "bank-a", CardFee.yaml(), "card-fee");
        publish(authoring, "bank-a", CardFee.resource("/rules/loan-pricing.yaml"), "loan-pricing");
        caller.become("bank-a", "svc-eod", EVALUATE);
        return RuleEngines.create(new EngineContext(caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                stores, stores, settings), stores, stores, stores);
    }

    void publish(RuleEngines engines, String tenant, byte[] yaml, String name) {
        caller.become(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become(tenant, "bob");
        engines.versionService().approve(name, 1, new WriteOptions(null, 1L, null));
    }

    static BatchRequest.Item item(Map<String, Object> inputs, String correlationId) {
        return new BatchRequest.Item(DecisionInput.of(inputs), correlationId);
    }

    /** The four card-fee items of RLE-067. */
    static List<BatchRequest.Item> cardFeeItems() {
        return List.of(
                item(Map.of("tier", "GOLD", "txCount", 5), "eod-2027-01-31-0000"),
                item(Map.of("tier", "SILVER", "txCount", 4), "eod-2027-01-31-0001"),
                item(Map.of("tier", "SILVER", "txCount", "four"), "eod-2027-01-31-0002"),
                item(Map.of("tier", "STANDARD", "txCount", 0), "eod-2027-01-31-0003"));
    }

    static DecisionResult decision(BatchResult.Item item) {
        assertThat(item.decision()).as("item %d has a decision", item.index()).isNotNull();
        return item.decision();
    }

    @Test
    void RLE_067_batch_returns_one_result_per_item_and_a_failing_item_does_not_stop_the_rest() {
        // Given card-fee v1 and loan-pricing v1 published, and svc-eod holding rules:evaluate
        RuleEngines engines = engines();

        // When svc-eod posts the four card-fee items
        BatchResult cardFee = engines.ruleEngine().evaluateBatch(BatchRequest.of("card-fee", cardFeeItems()));

        // Then results are in index order, R1/R2/problem/R3, with one version and one clock
        assertThat(cardFee.items()).extracting(BatchResult.Item::index).containsExactly(0, 1, 2, 3);
        assertThat(decision(cardFee.items().get(0)).matchedRuleIds()).containsExactly("R1");
        assertThat(decision(cardFee.items().get(0)).outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(decision(cardFee.items().get(1)).matchedRuleIds()).containsExactly("R2");
        assertThat(decision(cardFee.items().get(1)).outputs()).containsEntry("fee", Money.ofMinor("VND", 10_000));
        assertThat(cardFee.items().get(2).decision()).isNull();
        assertThat(cardFee.items().get(2).code()).isEqualTo("INPUT_INVALID");
        assertThat(cardFee.items().get(2).detail()).contains("txCount");
        assertThat(decision(cardFee.items().get(3)).matchedRuleIds()).containsExactly("R3");
        assertThat(decision(cardFee.items().get(3)).outputs()).containsEntry("fee", Money.ofMinor("VND", 50_000));
        assertThat(cardFee.version()).isEqualTo(1);
        assertThat(cardFee.clock()).isEqualTo(NOW);
        assertThat(List.of(0, 1, 3)).allSatisfy(i -> {
            assertThat(decision(cardFee.items().get(i)).version()).isEqualTo(1);
            assertThat(decision(cardFee.items().get(i)).clock()).isEqualTo(NOW);
        });
        assertThat(cardFee.succeeded()).isEqualTo(3);
        assertThat(cardFee.failed()).isEqualTo(1);

        // And items 0, 1 and 3 each have their own id and one row carrying their correlation id; item 2 has none
        List<String> ids = List.of(0, 1, 3).stream().map(i -> decision(cardFee.items().get(i)).decisionId()).toList();
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(stores.decisions).extracting(DecisionRecord::id).containsExactlyElementsOf(ids);
        assertThat(stores.decisions).extracting(DecisionRecord::correlationId)
                .containsExactly("eod-2027-01-31-0000", "eod-2027-01-31-0001", "eod-2027-01-31-0003");
        assertThat(stores.decisions).extracting(DecisionRecord::callerId).containsOnly("svc-eod");

        // When it posts two loan-pricing items: one matching R1 only, one matching R1 and R2
        BatchResult loans = engines.ruleEngine().evaluateBatch("loan-pricing", List.of(
                DecisionInput.of(Map.of("score", 750, "income", Money.ofMinor("VND", 10_000_000))),
                DecisionInput.of(Map.of("score", 750, "income", Money.ofMinor("VND", 25_000_000)))));

        // Then the first is a match, the second UNIQUE_HIT_VIOLATION with both rules, logged as a decision row
        assertThat(decision(loans.items().get(0)).matchedRuleIds()).containsExactly("R1");
        assertThat(decision(loans.items().get(1)).outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                failed -> {
                    assertThat(failed.code()).isEqualTo("UNIQUE_HIT_VIOLATION");
                    assertThat(failed.matchedRuleIds()).containsExactly("R1", "R2");
                });
        assertThat(loans.succeeded()).isEqualTo(1);
        assertThat(loans.failed()).isEqualTo(1);
        assertThat(stores.decisions).hasSize(5);
        assertThat(stores.decisions.getLast().outcome()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(stores.decisions.getLast().id()).isEqualTo(decision(loans.items().get(1)).decisionId());
    }

    @Test
    void RLE_067_a_malformed_correlation_id_fails_its_item_only() {
        RuleEngines engines = engines();

        BatchResult result = engines.ruleEngine().evaluateBatch(BatchRequest.of("card-fee", List.of(
                item(Map.of("tier", "GOLD", "txCount", 5), "LA 2027/123"),
                item(Map.of("tier", "GOLD", "txCount", 5), "LA-2027-000123"))));

        assertThat(result.items().get(0).code()).isEqualTo("INPUT_INVALID");
        assertThat(result.items().get(0).detail()).contains("correlationId");
        assertThat(result.items().get(1).decision()).isNotNull();
        assertThat(stores.decisions).hasSize(1);
    }

    @Test
    void RLE_068_a_batch_is_bounded_as_a_whole_and_per_item() {
        // Given the default evaluation.max-batch of 1,000
        RuleEngines engines = engines();
        DecisionInput gold = DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5));

        // When 0 items and 1,001 items are posted
        // Then 0 items is INPUT_INVALID and 1,001 is BATCH_TOO_LARGE, and nothing is evaluated or written
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch("card-fee", List.of()))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("1 to 1000");
        List<DecisionInput> tooMany = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            tooMany.add(gold);
        }
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch("card-fee", tooMany))
                .isInstanceOfSatisfying(BatchTooLargeException.class, e -> {
                    assertThat(e.code()).isEqualTo("BATCH_TOO_LARGE");
                    assertThat(e.getMessage()).contains("evaluation.max-batch", "1001");
                });
        assertThat(stores.decisions).isEmpty();

        // And exactly 1,000 items are accepted
        assertThat(engines.ruleEngine().evaluateBatch("card-fee", tooMany.subList(0, 1000)).succeeded())
                .isEqualTo(1000);
        assertThat(stores.decisions).hasSize(1000);
    }

    @Test
    void RLE_068_max_batch_may_be_lowered_and_never_raised() {
        RuleEngines lowered = engines(EngineSettings.defaults().withRequireContentHash(false).withMaxBatch(3));
        DecisionInput gold = DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5));

        assertThatThrownBy(() -> lowered.ruleEngine().evaluateBatch("card-fee", List.of(gold, gold, gold, gold)))
                .isInstanceOf(BatchTooLargeException.class).hasMessageContaining("at most 3 items");
        assertThat(lowered.ruleEngine().evaluateBatch("card-fee", List.of(gold, gold, gold)).succeeded()).isEqualTo(3);
        assertThatThrownBy(() -> EngineSettings.defaults().withRequireContentHash(false).withMaxBatch(1001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evaluation.max-batch");
        assertThatThrownBy(() -> EngineSettings.defaults().withRequireContentHash(false).withMaxBatch(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("evaluation.max-batch");
    }

    @Test
    void RLE_068_a_rule_set_with_no_version_in_effect_or_none_at_all_writes_nothing() {
        // Given kyc-risk imported but never approved, and no rule set named mortgage-fee
        RuleEngines engines = engines();
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(
                new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("ruleSet: card-fee", "ruleSet: kyc-risk")
                        .getBytes(StandardCharsets.UTF_8)));
        caller.become("bank-a", "svc-eod", EVALUATE);
        List<DecisionInput> items = List.of(DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

        // Then
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch("kyc-risk", items))
                .isInstanceOf(NoEffectiveVersionException.class);
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch("mortgage-fee", items))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_068_the_request_regex_budget_stops_the_batch_at_the_item_that_spends_it() {
        // Given a screening table whose two rows each search a long reference, and a request budget of 6.5 rows of
        // reads, so three long references fit and the fourth runs out
        long perRow = RegexBoundsTest.readsOf("[0-9]*K1", RegexBoundsTest.LONG_REFERENCE);
        long budget = 6 * perRow + perRow / 2;
        RegexLimits limits = new RegexLimits(1000, 4096, RegexLimits.MAX_READS, RegexLimits.MAX_READS_PER_DECISION,
                budget);
        RuleEngines engines = engines(EngineSettings.defaults().withRequireContentHash(false).withRegexLimits(limits));
        publish(engines, "bank-a", RegexBoundsTest.screening(2).getBytes(StandardCharsets.UTF_8),
                "remittance-screening");
        caller.become("bank-a", "svc-eod", EVALUATE);
        List<DecisionInput> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(DecisionInput.of(Map.of("reference", RegexBoundsTest.LONG_REFERENCE)));
        }

        // When
        BatchResult result = engines.ruleEngine().evaluateBatch("remittance-screening", five);

        // Then items 0 to 2 keep their results, item 3 fails with REGEX_TIMEOUT naming the setting and is logged,
        // and item 4 is failed and not evaluated, with no row
        assertThat(List.of(0, 1, 2)).allSatisfy(i -> assertThat(decision(result.items().get(i)).matchedRuleIds())
                .containsExactly("CLEAR"));
        assertThat(decision(result.items().get(3)).outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                failed -> {
                    assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
                    assertThat(failed.detail()).startsWith("REGEX_TIMEOUT")
                            .contains("evaluation.regex-max-reads-per-request");
                });
        assertThat(result.items().get(4).decision()).isNull();
        assertThat(result.items().get(4).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(result.items().get(4).detail()).startsWith("REGEX_TIMEOUT: not evaluated");
        assertThat(result.succeeded()).isEqualTo(3);
        assertThat(result.failed()).isEqualTo(2);
        assertThat(stores.decisions).hasSize(4);
        assertThat(stores.decisions.get(3).errorCode()).isEqualTo("REGEX_TIMEOUT");
    }

    @Test
    void RLE_068_the_step_budget_is_per_item_so_one_heavy_item_fails_alone() {
        // Given a budget that a GOLD customer (first row) fits and a STANDARD customer (every row) does not
        RuleEngines engines = engines(EngineSettings.defaults().withRequireContentHash(false).withEvaluationLimits(new EvaluationLimits(6, 10_000)));
        List<DecisionInput> items = List.of(
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)),
                DecisionInput.of(Map.of("tier", "STANDARD", "txCount", 0)),
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 9)));

        // When
        BatchResult result = engines.ruleEngine().evaluateBatch("card-fee", items);

        // Then item 1 fails with EVALUATION_BUDGET_EXCEEDED on STEPS and the others succeed
        assertThat(decision(result.items().get(1)).outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                failed -> {
                    assertThat(failed.detail()).startsWith("EVALUATION_BUDGET_EXCEEDED");
                    assertThat(failed.limit()).isEqualTo("STEPS");
                });
        assertThat(decision(result.items().get(0)).matchedRuleIds()).containsExactly("R1");
        assertThat(decision(result.items().get(2)).matchedRuleIds()).containsExactly("R1");
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(stores.decisions).hasSize(3);
    }

    @Test
    void RLE_069_batch_is_idempotent_transactional_and_tenant_scoped() {
        // Given the batch of RLE-067 sent with Idempotency-Key k-701
        RuleEngines engines = engines();
        BatchRequest request = BatchRequest.of("card-fee", cardFeeItems()).withIdempotencyKey("k-701");
        BatchResult first = engines.ruleEngine().evaluateBatch(request);
        int outbox = stores.outbox.size();

        // When it is repeated with the same key and body
        BatchResult repeat = engines.ruleEngine().evaluateBatch(request);

        // Then the same body comes back, decision ids included, and no row is written
        assertThat(repeat).isEqualTo(first);
        assertThat(stores.decisions).hasSize(3);
        assertThat(stores.outbox).hasSize(outbox);

        // And the same key with a changed item is IDEMPOTENCY_KEY_REUSE_MISMATCH, with nothing written
        List<BatchRequest.Item> changed = new ArrayList<>(cardFeeItems());
        changed.set(0, item(Map.of("tier", "GOLD", "txCount", 6), "eod-2027-01-31-0000"));
        assertThatThrownBy(() -> engines.ruleEngine()
                .evaluateBatch(BatchRequest.of("card-fee", changed).withIdempotencyKey("k-701")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(stores.decisions).hasSize(3);

        // And a bank-b caller is served from bank-b, which has no card-fee: nothing of bank-a is touched
        caller.become("bank-b", "svc-eod", EVALUATE);
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch(BatchRequest.of("card-fee", cardFeeItems())
                .withIdempotencyKey("k-701"))).isInstanceOf(RuleSetNotFoundException.class);
        assertThat(stores.decisions).hasSize(3);
        assertThat(stores.decisions).extracting(DecisionRecord::tenantId).containsOnly("bank-a");
    }

    @Test
    void RLE_069_a_replayed_batch_gives_a_failed_decision_and_an_invalid_item_as_they_were() {
        // Given loan-pricing items with a failed decision, and a card-fee batch with an invalid item, each keyed
        RuleEngines engines = engines();
        BatchRequest loans = BatchRequest.of("loan-pricing", List.of(
                item(Map.of("score", 750, "income", Money.ofMinor("VND", 25_000_000)), "LA-2027-000123")))
                .withIdempotencyKey("k-702");
        BatchRequest cards = BatchRequest.of("card-fee", cardFeeItems()).withIdempotencyKey("k-703");
        BatchResult firstLoans = engines.ruleEngine().evaluateBatch(loans);
        BatchResult firstCards = engines.ruleEngine().evaluateBatch(cards);

        // When both are replayed
        // Then each comes back equal, the failed decision keeps its id and rule ids, the invalid item its code
        assertThat(engines.ruleEngine().evaluateBatch(loans)).isEqualTo(firstLoans);
        assertThat(engines.ruleEngine().evaluateBatch(cards)).isEqualTo(firstCards);
        assertThat(firstLoans.items().getFirst().decision().matchedRuleIds()).containsExactly("R1", "R2");
        assertThat(engines.ruleEngine().evaluateBatch(cards).items().get(2).code()).isEqualTo("INPUT_INVALID");
    }

    @Test
    void RLE_069_a_clock_needs_rules_simulate_and_a_batch_needs_rules_evaluate() {
        RuleEngines engines = engines();
        BatchRequest chosen = BatchRequest.of("card-fee", cardFeeItems()).withClock(NOW.plusSeconds(86_400));

        // Without rules:simulate the chosen clock is CLOCK_NOT_ALLOWED; nothing is written
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch(chosen)).isInstanceOf(ClockNotAllowedException.class);
        assertThat(stores.decisions).isEmpty();

        // With it, every decision reasons about the chosen clock
        caller.become("bank-a", "svc-eod", Set.of("rules:evaluate", "rules:simulate"));
        BatchResult result = engines.ruleEngine().evaluateBatch(chosen);
        assertThat(result.clock()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(decision(result.items().getFirst()).clockChosen()).isTrue();

        // Without rules:evaluate the call is refused before anything is read
        caller.become("bank-a", "dan", Set.of("rules:log:read"));
        assertThatThrownBy(() -> engines.ruleEngine().evaluateBatch(BatchRequest.of("card-fee", cardFeeItems())))
                .isInstanceOf(PermissionDeniedException.class);
        assertThat(stores.decisions).hasSize(3);
    }
}
