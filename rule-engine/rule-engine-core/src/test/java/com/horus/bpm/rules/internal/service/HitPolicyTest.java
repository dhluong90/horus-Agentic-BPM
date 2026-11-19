package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HitPolicyTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    void publish(byte[] yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
        caller.become("bank-a", "teller");
    }

    DecisionResult evaluate(String ruleSet, Map<String, ?> inputs) {
        return engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
    }

    @Test
    void RLE_002_unique_hit_policy_rejects_zero_or_multiple_matches() {
        // Given
        publish(CardFee.resource("/rules/loan-pricing.yaml"), "loan-pricing");

        // When two rows match, and when none does
        DecisionResult overlap = evaluate("loan-pricing", Map.of("score", 750, "income", Money.ofMinor("VND", 25_000_000)));
        DecisionResult none = evaluate("loan-pricing", Map.of("score", 500, "income", Money.ofMinor("VND", 5_000_000)));

        // Then
        assertThat(overlap.outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThat(((DecisionResult.Failed) overlap.outcome()).code()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(overlap.outputs()).isEmpty();
        assertThat(overlap.matchedRuleIds()).containsExactly("R1", "R2");
        // Zero matches is NO_MATCH, as under first (PD-088)
        assertThat(none.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(none.outputs()).isEmpty();
        assertThat(none.matchedRuleIds()).isEmpty();
        assertThat(stores.decisions).extracting(r -> r.outcome() + " " + r.errorCode() + " " + r.matchedRuleIdsJson()
                        + " " + r.outputsJson())
                .containsExactly("UNIQUE_HIT_VIOLATION UNIQUE_HIT_VIOLATION [\"R1\",\"R2\"] null",
                        "NO_MATCH null [] {}");
    }

    @Test
    void a_unique_table_returns_the_one_matching_row() {
        publish(CardFee.resource("/rules/loan-pricing.yaml"), "loan-pricing");

        DecisionResult result = evaluate("loan-pricing", Map.of("score", 750, "income", Money.ofMinor("VND", 10_000_000)));

        assertThat(result.matchedRuleIds()).containsExactly("R1");
        assertThat(result.outputs()).containsEntry("rate", new BigDecimal("0.085"));
    }

    @Test
    void RLE_003_collect_hit_policy_aggregates_matched_rules() {
        // Given
        publish(CardFee.resource("/rules/risk-flags.yaml"), "risk-flags");

        // When R1 (velocity) and R3 (new device) match
        DecisionResult result = evaluate("risk-flags", Map.of("txPerHour", 12, "country", "VN", "newDevice", true));

        // Then
        assertThat(result.matchedRuleIds()).containsExactly("R1", "R3");
        assertThat(result.outputs()).containsEntry("flags", List.of("HIGH_VELOCITY", "NEW_DEVICE"));
        assertThat(result.outputs()).containsEntry("penalty", BigDecimal.valueOf(15));
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("OK");
            assertThat(row.matchedRuleIdsJson()).isEqualTo("[\"R1\",\"R3\"]");
            assertThat(row.outputsJson()).isEqualTo("{\"flags\":[\"HIGH_VELOCITY\",\"NEW_DEVICE\"],\"penalty\":15}");
        });
    }

    @Test
    void a_collect_table_with_no_match_is_no_match() {
        publish(CardFee.resource("/rules/risk-flags.yaml"), "risk-flags");

        DecisionResult result = evaluate("risk-flags", Map.of("txPerHour", 1, "country", "VN", "newDevice", false));

        assertThat(result.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(result.matchedRuleIds()).isEmpty();
        // DMN 1.5 (N2 of the QA review): an empty list per list output, null per aggregate
        assertThat(result.outputs()).containsEntry("flags", List.of()).containsEntry("penalty", null);
        assertThat(stores.decisions.getFirst().outcome()).isEqualTo("NO_MATCH");
        assertThat(stores.decisions.getFirst().outputsJson()).isEqualTo("{\"flags\":[],\"penalty\":null}");
    }

    @Test
    void collect_min_max_count_and_money_sum() {
        publish(yaml("""
                schemaVersion: 1
                ruleSet: fees-collected
                hitPolicy: collect
                inputs:
                  - { name: amount, type: integer }
                outputs:
                  - { name: total,   type: money,   collect: sum }
                  - { name: lowest,  type: money,   collect: min }
                  - { name: highest, type: integer, collect: max }
                  - { name: hits,    type: integer, collect: count }
                rules:
                  - { id: F1, when: { amount: ">= 0" },    then: { total: "1000 VND", lowest: "1000 VND", highest: 1, hits: 1 } }
                  - { id: F2, when: { amount: ">= 100" },  then: { total: "2500 VND", lowest: "2500 VND", highest: 3, hits: 1 } }
                  - { id: F3, when: { amount: ">= 1000" }, then: { total: "500 VND",  lowest: "500 VND",  highest: 2 } }
                """), "fees-collected");

        DecisionResult result = evaluate("fees-collected", Map.of("amount", 5000));

        assertThat(result.matchedRuleIds()).containsExactly("F1", "F2", "F3");
        assertThat(result.outputs())
                .containsEntry("total", Money.ofMinor("VND", 4000))
                .containsEntry("lowest", Money.ofMinor("VND", 500))
                .containsEntry("highest", BigDecimal.valueOf(3))
                .containsEntry("hits", BigDecimal.valueOf(2));
    }

    @Test
    void summing_money_of_two_currencies_is_an_evaluation_error_with_no_outputs() {
        publish(yaml("""
                schemaVersion: 1
                ruleSet: mixed-fees
                hitPolicy: collect
                inputs:
                  - { name: amount, type: integer }
                outputs:
                  - { name: total, type: money, collect: sum }
                rules:
                  - { id: F1, when: {}, then: { total: "1000 VND" } }
                  - { id: F2, when: {}, then: { total: "1.50 USD" } }
                """), "mixed-fees");

        DecisionResult result = evaluate("mixed-fees", Map.of("amount", 1));

        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(result.outputs()).isEmpty();
        assertThat(result.matchedRuleIds()).isEmpty();
        assertThat(stores.decisions.getFirst().outcome()).isEqualTo("EVALUATION_ERROR");
    }

    @Test
    void a_collected_money_sum_beyond_json_safe_range_is_an_evaluation_error() {
        publish(yaml("""
                schemaVersion: 1
                ruleSet: big-sum
                hitPolicy: collect
                inputs:
                  - { name: amount, type: integer }
                outputs:
                  - { name: total, type: money, collect: sum }
                rules:
                  - { id: B1, when: {}, then: { total: "5000000000000000 VND" } }
                  - { id: B2, when: {}, then: { total: "5000000000000000 VND" } }
                """), "big-sum");

        DecisionResult result = evaluate("big-sum", Map.of("amount", 1));

        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(result.outputs()).isEmpty();
    }

    @Test
    void outputs_that_do_not_fit_the_hit_policy_are_refused_at_import() {
        String base = """
                schemaVersion: 1
                ruleSet: bad-outputs
                hitPolicy: %s
                inputs:
                  - { name: amount, type: integer }
                outputs:
                  - { name: out, type: %s }
                rules:
                  - { id: B1, when: {}, then: { out: 1 } }
                """;
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(yaml(base.formatted("first", "integer, collect: sum")))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("only with hitPolicy collect");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(yaml(base.formatted("unique", "list<integer>")))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("needs hitPolicy collect");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(yaml(base.formatted("collect", "integer")))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("declare list<T>");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(yaml(base.formatted("collect", "string, collect: sum")))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("collect: sum");
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(yaml(base.formatted("collect", "integer, collect: avg")))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("sum, min, max or count");
        assertThat(stores.versions).isEmpty();
    }

    static byte[] yaml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
