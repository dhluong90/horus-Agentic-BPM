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
    void unique_loan_pricing_fixture_fails_on_overlap_and_is_no_match_on_zero() {
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
        // Zero matches is a no-match with every output null, logged as OK with [] as under first (PD-088)
        assertThat(none.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(none.outputs()).containsOnlyKeys("rate").containsEntry("rate", null);
        assertThat(none.matchedRuleIds()).isEmpty();
        assertThat(stores.decisions).extracting(r -> r.outcome() + " " + r.errorCode() + " " + r.matchedRuleIdsJson()
                        + " " + r.outputsJson())
                .containsExactly("UNIQUE_HIT_VIOLATION UNIQUE_HIT_VIOLATION [\"R1\",\"R2\"] null",
                        "OK null [] {\"rate\":null}");
    }

    /** RLE-002 as the SPEC states it (PD-088). */
    @Test
    void RLE_002_unique_hit_policy_returns_one_match_no_match_on_zero_and_fails_on_several() {
        // Given loan-pricing v1: R1 (score >= 700 -> 0.08) and R2 (income >= 20000000 VND -> 0.09) overlap
        publish(yaml("""
                schemaVersion: 1
                ruleSet: loan-pricing
                hitPolicy: unique
                inputs:
                  - { name: score,  type: integer }
                  - { name: income, type: money }
                outputs:
                  - { name: rate, type: decimal }
                rules:
                  - { id: R1, when: { score: ">= 700" }, then: { rate: 0.08 } }
                  - { id: R2, when: { income: ">= 20000000 VND" }, then: { rate: 0.09 } }
                """), "loan-pricing");

        // When (a) one match, (b) zero matches, (c) two matches
        DecisionResult one = evaluate("loan-pricing", Map.of("score", 750, "income", Money.ofMinor("VND", 10_000_000)));
        DecisionResult zero = evaluate("loan-pricing", Map.of("score", 500, "income", Money.ofMinor("VND", 5_000_000)));
        DecisionResult two = evaluate("loan-pricing", Map.of("score", 750, "income", Money.ofMinor("VND", 25_000_000)));

        // Then (a)
        assertThat(one.matchedRuleIds()).containsExactly("R1");
        assertThat(one.outputs()).containsEntry("rate", new BigDecimal("0.08"));
        // (b) NoMatch, every output null, logged as OK with [] exactly as for first; never UNIQUE_HIT_VIOLATION
        assertThat(zero.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(zero.outputs()).containsOnlyKeys("rate").containsEntry("rate", null);
        assertThat(zero.matchedRuleIds()).isEmpty();
        // (c) UNIQUE_HIT_VIOLATION, no partial outputs, both rule ids in rule order
        assertThat(((DecisionResult.Failed) two.outcome()).code()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(two.outputs()).isEmpty();
        assertThat(two.matchedRuleIds()).containsExactly("R1", "R2");
        assertThat(stores.decisions).extracting(r -> r.outcome() + " " + r.matchedRuleIdsJson())
                .containsExactly("OK [\"R1\"]", "OK []", "UNIQUE_HIT_VIOLATION [\"R1\",\"R2\"]");
    }

    /** RLE-003 as the SPEC states it (PD-088). */
    @Test
    void RLE_003_collect_hit_policy_keeps_null_entries_and_aggregates_non_null_values() {
        // Given risk-flags v1 with a list, sum, max and count; R2 gives null for every output
        publish(yaml("""
                schemaVersion: 1
                ruleSet: risk-flags
                hitPolicy: collect
                inputs:
                  - { name: velocity,  type: boolean }
                  - { name: unknownIp, type: boolean }
                  - { name: newDevice, type: boolean }
                outputs:
                  - { name: flags,   type: list<string> }
                  - { name: penalty, type: integer, collect: sum }
                  - { name: worst,   type: integer, collect: max }
                  - { name: hits,    type: integer, collect: count }
                rules:
                  - { id: R1, when: { velocity: "== true" }, then: { flags: "'HIGH_VELOCITY'", penalty: 10, worst: 10, hits: 1 } }
                  - { id: R2, when: { unknownIp: "== true" }, then: { flags: null, penalty: null, worst: null, hits: null } }
                  - { id: R3, when: { newDevice: "== true" }, then: { flags: "'NEW_DEVICE'", penalty: 5, worst: 5, hits: 1 } }
                """), "risk-flags");

        // When (a) all three match, (b) only R2, (c) none
        DecisionResult all = evaluate("risk-flags", Map.of("velocity", true, "unknownIp", true, "newDevice", true));
        DecisionResult onlyNull = evaluate("risk-flags", Map.of("velocity", false, "unknownIp", true, "newDevice", false));
        DecisionResult none = evaluate("risk-flags", Map.of("velocity", false, "unknownIp", false, "newDevice", false));

        // Then (a) lists keep the null entry, aligned with matchedRuleIds; aggregates skip it
        assertThat(all.matchedRuleIds()).containsExactly("R1", "R2", "R3");
        assertThat(all.outputs().get("flags")).isEqualTo(java.util.Arrays.asList("HIGH_VELOCITY", null, "NEW_DEVICE"));
        assertThat(all.outputs()).containsEntry("penalty", BigDecimal.valueOf(15))
                .containsEntry("worst", BigDecimal.valueOf(10)).containsEntry("hits", BigDecimal.valueOf(2));
        // (b) every value null: list [null], sum and max null, count 0
        assertThat(onlyNull.outputs().get("flags")).isEqualTo(java.util.Arrays.asList((Object) null));
        assertThat(onlyNull.outputs()).containsEntry("penalty", null).containsEntry("worst", null)
                .containsEntry("hits", BigDecimal.ZERO);
        // (c) NoMatch, every output null (count included), logged as OK with []
        assertThat(none.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        assertThat(none.outputs()).containsEntry("flags", null).containsEntry("penalty", null)
                .containsEntry("worst", null).containsEntry("hits", null);
        assertThat(stores.decisions).extracting(r -> r.outcome() + " " + r.matchedRuleIdsJson())
                .containsExactly("OK [\"R1\",\"R2\",\"R3\"]", "OK [\"R2\"]", "OK []");
    }

    @Test
    void a_unique_table_returns_the_one_matching_row() {
        publish(CardFee.resource("/rules/loan-pricing.yaml"), "loan-pricing");

        DecisionResult result = evaluate("loan-pricing", Map.of("score", 750, "income", Money.ofMinor("VND", 10_000_000)));

        assertThat(result.matchedRuleIds()).containsExactly("R1");
        assertThat(result.outputs()).containsEntry("rate", new BigDecimal("0.085"));
    }

    @Test
    void collect_risk_flags_fixture_sums_two_flags_to_fifteen() {
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
        // RLE-003 (c): every output null, lists and aggregates included; logged as OK with []
        assertThat(result.outputs()).containsEntry("flags", null).containsEntry("penalty", null);
        assertThat(stores.decisions.getFirst().outcome()).isEqualTo("OK");
        assertThat(stores.decisions.getFirst().matchedRuleIdsJson()).isEqualTo("[]");
        assertThat(stores.decisions.getFirst().outputsJson()).isEqualTo("{\"flags\":null,\"penalty\":null}");
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
