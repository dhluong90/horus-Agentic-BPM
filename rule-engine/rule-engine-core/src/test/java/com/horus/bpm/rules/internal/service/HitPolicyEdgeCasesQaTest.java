package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionRequest;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** QA review of #18: edge cases of the unique and collect hit policies. */
class HitPolicyEdgeCasesQaTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    void publish(String yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
        caller.become("bank-a", "teller");
    }

    DecisionResult evaluate(String ruleSet, Map<String, ?> inputs) {
        return engines.ruleEngine().evaluate(ruleSet, DecisionInput.of(inputs));
    }

    // ---- RLE-006: the SPEC statement needs collect, which this PR delivers ----

    @Test
    void RLE_006_null_follows_feel_three_valued_logic() {
        // Given null-check v1 exactly as SPEC RLE-006
        publish("""
                schemaVersion: 1
                ruleSet: null-check
                hitPolicy: collect
                inputs:
                  - { name: income, type: money }
                  - { name: tier,   type: string }
                outputs:
                  - { name: hit, type: list<string> }
                rules:
                  - { id: R1, when: { income: "< 5000000 VND" }, then: { hit: "'R1'" } }
                  - { id: R2, when: { income: "is null" }, then: { hit: "'R2'" } }
                  - { id: R3, when: { tier: "!= 'GOLD'" }, then: { hit: "'R3'" } }
                  - { id: R4, when: { income: "not (income < 5000000 VND)" }, then: { hit: "'R4'" } }
                  - { id: R5, when: { tier: "not in ['GOLD', 'PLATINUM']" }, then: { hit: "'R5'" } }
                  - { id: R6, when: { tier: "== 'GOLD'" }, then: { hit: "'R6'" } }
                  - { id: R7, when: { income: "income < 5000000 VND or tier is null" }, then: { hit: "'R7'" } }
                  - { id: R8, when: { income: "income < 5000000 VND and tier is null" }, then: { hit: "'R8'" } }
                """, "null-check");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("income", null);
        inputs.put("tier", null);

        // When
        DecisionResult result = evaluate("null-check", inputs);

        // Then
        assertThat(result.matchedRuleIds()).containsExactly("R2", "R3", "R5", "R7");
        assertThat(stores.decisions).singleElement().satisfies(row -> assertThat(row.outcome()).isEqualTo("OK"));
    }

    // ---- min and max over strings must order like the expression language ----

    @Test
    void RLE_003_collect_min_and_max_order_strings_like_the_lt_operator() {
        // Given U+FF21 (fullwidth A) and U+1F600 (outside the BMP): by code point FF21 < 1F600,
        // by UTF-16 code unit the surrogate D83D sorts first.
        publish("""
                schemaVersion: 1
                ruleSet: names
                hitPolicy: collect
                inputs:
                  - { name: a, type: string }
                  - { name: b, type: string }
                outputs:
                  - { name: lowest,  type: string, collect: min }
                  - { name: highest, type: string, collect: max }
                rules:
                  - { id: N1, when: {}, then: { lowest: "a", highest: "a" } }
                  - { id: N2, when: {}, then: { lowest: "b", highest: "b" } }
                  - { id: N3, when: { a: "a < b" }, then: { lowest: "a", highest: "b" } }
                """, "names");
        String a = "Ａ";
        String b = new String(Character.toChars(0x1F600));

        // When
        DecisionResult result = evaluate("names", Map.of("a", a, "b", b));

        // Then: the engine itself says a < b (N3 matched), so min must be a and max must be b
        assertThat(result.matchedRuleIds()).containsExactly("N1", "N2", "N3");
        assertThat(result.outputs()).containsEntry("lowest", a).containsEntry("highest", b);
    }

    // ---- null outputs ----

    @Test
    void RLE_003_collect_lists_keep_one_entry_per_matched_rule_so_columns_stay_aligned() {
        // Given two list outputs; R2 gives no reason
        publish("""
                schemaVersion: 1
                ruleSet: flags-and-reasons
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: flags,   type: list<string> }
                  - { name: reasons, type: list<string> }
                rules:
                  - { id: R1, when: {}, then: { flags: "'A'", reasons: "'reason A'" } }
                  - { id: R2, when: {}, then: { flags: "'B'" } }
                  - { id: R3, when: {}, then: { flags: "'C'", reasons: "'reason C'" } }
                """, "flags-and-reasons");

        // When
        DecisionResult result = evaluate("flags-and-reasons", Map.of("x", 1));

        // Then: reasons[i] belongs to flags[i] and to matchedRuleIds[i]
        assertThat(result.matchedRuleIds()).containsExactly("R1", "R2", "R3");
        assertThat(result.outputs().get("flags")).isEqualTo(List.of("A", "B", "C"));
        assertThat(result.outputs().get("reasons")).isEqualTo(Arrays.asList("reason A", null, "reason C"));
    }

    @Test
    void RLE_003_collect_where_every_matched_row_gives_null_fails_under_sum_and_min() {
        publish("""
                schemaVersion: 1
                ruleSet: all-null
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: s,  type: money,   collect: sum }
                  - { name: lo, type: integer, collect: min }
                  - { name: n,  type: integer, collect: count }
                  - { name: l,  type: list<string> }
                rules:
                  - { id: R1, when: {}, then: {} }
                  - { id: R2, when: {}, then: {} }
                """, "all-null");

        DecisionResult result = evaluate("all-null", Map.of("x", 1));

        // Corrected RLE-003 (DMN 1.5 check): a null under sum or min is EVALUATION_ERROR in errorMode strict, with
        // the matched rules logged and no partial outputs. The count and list case without sum or min is
        // RLE_003_count_and_lists_alone_keep_every_null below.
        assertThat(result.matchedRuleIds()).containsExactly("R1", "R2");
        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(result.outputs()).isEmpty();
        // One list entry per matched rule, null included (B1 of this review, PD-088), so l is [null, null].
    }

    @Test
    void RLE_003_count_and_lists_alone_keep_every_null() {
        // Given only count and a list, so no aggregate refuses null
        publish("""
                schemaVersion: 1
                ruleSet: count-and-list
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: n, type: integer, collect: count }
                  - { name: l, type: list<string> }
                rules:
                  - { id: R1, when: {}, then: { n: null, l: null } }
                  - { id: R2, when: {}, then: { n: null, l: null } }
                """, "count-and-list");

        // When
        DecisionResult result = evaluate("count-and-list", Map.of("x", 1));

        // Then count counts every matched rule, nulls included, and the list keeps one null per matched rule
        assertThat(result.matchedRuleIds()).containsExactly("R1", "R2");
        assertThat(result.outputs()).containsEntry("n", BigDecimal.valueOf(2))
                .containsEntry("l", Arrays.asList(null, null));
    }

    @Test
    void RLE_003_a_null_from_a_null_input_under_sum_fails_in_strict_mode() {
        publish("""
                schemaVersion: 1
                ruleSet: count-nulls
                hitPolicy: collect
                inputs:
                  - { name: v, type: integer }
                outputs:
                  - { name: n, type: integer, collect: count }
                  - { name: s, type: integer, collect: sum }
                rules:
                  - { id: R1, when: {}, then: { n: "v", s: "v + 1" } }
                  - { id: R2, when: {}, then: { n: 1, s: 2 } }
                """, "count-nulls");
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("v", null);

        DecisionResult result = evaluate("count-nulls", inputs);

        // Corrected RLE-003: the null from v + 1 under sum is EVALUATION_ERROR in errorMode strict, naming rule R1,
        // so no output is returned, n included
        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(((DecisionResult.Failed) result.outcome()).detail()).contains("R1");
        assertThat(result.outputs()).isEmpty();
    }

    // ---- exact sums ----

    @Test
    void RLE_003_decimal_sum_is_exact_in_cents() {
        StringBuilder rules = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            rules.append("  - { id: R").append(i).append(", when: {}, then: { s: 0.1 } }\n");
        }
        publish("""
                schemaVersion: 1
                ruleSet: tenths
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: s, type: decimal, collect: sum }
                rules:
                """ + rules, "tenths");

        DecisionResult result = evaluate("tenths", Map.of("x", 1));

        assertThat((BigDecimal) result.outputs().get("s")).isEqualByComparingTo("30");
    }

    @Test
    void RLE_003_decimal_sum_beyond_34_digits_is_exact_or_fails_never_silently_rounded() {
        publish("""
                schemaVersion: 1
                ruleSet: wide
                hitPolicy: collect
                inputs:
                  - { name: big, type: decimal }
                  - { name: small, type: decimal }
                outputs:
                  - { name: s, type: decimal, collect: sum }
                rules:
                  - { id: R1, when: {}, then: { s: "big" } }
                  - { id: R2, when: {}, then: { s: "small" } }
                """, "wide");

        DecisionResult result = evaluate("wide", Map.of("big", new BigDecimal("1E+33"), "small", new BigDecimal("0.5")));

        if (result.outcome() instanceof DecisionResult.Matched) {
            assertThat((BigDecimal) result.outputs().get("s"))
                    .isEqualByComparingTo(new BigDecimal("1E+33").add(new BigDecimal("0.5")));
        } else {
            assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        }
    }

    @Test
    void RLE_003_money_sum_exact_in_minor_units_and_mixed_currency_min_fails() {
        publish("""
                schemaVersion: 1
                ruleSet: usd-fees
                hitPolicy: collect
                inputs:
                  - { name: a, type: money }
                  - { name: b, type: money }
                outputs:
                  - { name: total, type: money, collect: sum }
                  - { name: lowest, type: money, collect: min }
                rules:
                  - { id: R1, when: {}, then: { total: "a", lowest: "a" } }
                  - { id: R2, when: {}, then: { total: "b", lowest: "b" } }
                """, "usd-fees");

        DecisionResult same = evaluate("usd-fees", Map.of("a", Money.ofMinor("USD", 10), "b", Money.ofMinor("USD", 20)));
        DecisionResult mixed = evaluate("usd-fees", Map.of("a", Money.ofMinor("USD", 10), "b", Money.ofMinor("EUR", 20)));

        assertThat(same.outputs()).containsEntry("total", Money.ofMinor("USD", 30))
                .containsEntry("lowest", Money.ofMinor("USD", 10));
        assertThat(((DecisionResult.Failed) mixed.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(mixed.outputs()).isEmpty();
    }

    // ---- dates, datetimes, ties ----

    @Test
    void RLE_003_min_and_max_over_dates_datetimes_and_decimal_ties() {
        publish("""
                schemaVersion: 1
                ruleSet: dates
                hitPolicy: collect
                inputs:
                  - { name: d1, type: date }
                  - { name: d2, type: date }
                  - { name: t1, type: datetime }
                  - { name: t2, type: datetime }
                  - { name: p1, type: decimal }
                  - { name: p2, type: decimal }
                outputs:
                  - { name: earliest, type: date, collect: min }
                  - { name: latest, type: datetime, collect: max }
                  - { name: low, type: decimal, collect: min }
                rules:
                  - { id: R1, when: {}, then: { earliest: "d1", latest: "t1", low: "p1" } }
                  - { id: R2, when: {}, then: { earliest: "d2", latest: "t2", low: "p2" } }
                """, "dates");

        DecisionResult result = evaluate("dates", Map.of(
                "d1", LocalDate.parse("2026-12-31"), "d2", LocalDate.parse("2024-02-29"),
                "t1", Instant.parse("2026-11-20T00:00:00Z"), "t2", Instant.parse("2026-11-20T00:00:00.001Z"),
                "p1", new BigDecimal("1.0"), "p2", new BigDecimal("1.00")));

        assertThat(result.outputs()).containsEntry("earliest", LocalDate.parse("2024-02-29"))
                .containsEntry("latest", Instant.parse("2026-11-20T00:00:00.001Z"));
        assertThat(result.outputs().get("low").toString()).isEqualTo("1.0"); // first in rule order wins a tie
    }

    // ---- idempotent replay of a unique failure ----

    @Test
    void RLE_002_idempotent_replay_of_a_unique_violation_returns_the_same_decision_and_matches() {
        publish("""
                schemaVersion: 1
                ruleSet: overlap
                hitPolicy: unique
                inputs:
                  - { name: score, type: integer }
                outputs:
                  - { name: rate, type: decimal }
                rules:
                  - { id: R1, when: { score: ">= 700" }, then: { rate: 0.08 } }
                  - { id: R2, when: { score: ">= 600" }, then: { rate: 0.09 } }
                """, "overlap");
        DecisionRequest request = DecisionRequest.of("overlap", DecisionInput.of(Map.of("score", 750)))
                .withIdempotencyKey("k-unique-1");

        DecisionResult first = engines.ruleEngine().evaluate(request);
        DecisionResult replay = engines.ruleEngine().evaluate(request);

        assertThat(replay.decisionId()).isEqualTo(first.decisionId());
        assertThat(replay.outcome()).isEqualTo(first.outcome());
        assertThat(replay.matchedRuleIds()).containsExactly("R1", "R2");
        assertThat(stores.decisions).hasSize(1);
    }
}
