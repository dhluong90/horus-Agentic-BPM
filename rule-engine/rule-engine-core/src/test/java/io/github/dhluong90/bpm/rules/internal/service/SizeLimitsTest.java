package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Size limits that keep one decision from exhausting the heap every tenant of the host shares (security review
 * of #18): outputs while collected values accumulate, string {@code +}, string inputs and the rule count.
 */
class SizeLimitsTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller,
                Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC), stores, stores, settings),
                stores, stores, stores);
    }

    void publish(RuleEngines engines, String yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
    }

    /** A collect table of 20 rows that each copy the input {@code note} into the list output. */
    static String echoTable() {
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: echo
                hitPolicy: collect
                inputs:
                  - { name: note, type: string }
                outputs:
                  - { name: notes, type: list<string> }
                rules:
                """);
        for (int i = 0; i < 20; i++) {
            yaml.append("  - { id: E").append(i).append(", when: {}, then: { notes: \"note\" } }\n");
        }
        return yaml.toString();
    }

    @Test
    void collected_outputs_beyond_the_default_max_output_bytes_fail_with_a_logged_evaluation_error() {
        // Given 20 rows, each repeating a 60,000-character input: about 3.6 MB of output at the defaults
        RuleEngines engines = engines(EngineSettings.defaults());
        publish(engines, echoTable(), "echo");

        // When
        DecisionResult result = engines.ruleEngine().evaluate("echo", DecisionInput.of(Map.of("note", "x".repeat(60_000))));

        // Then
        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(((DecisionResult.Failed) result.outcome()).detail()).contains("Outputs larger than 1048576 bytes");
        assertThat(result.outputs()).isEmpty();
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR");
            assertThat(row.outputsJson()).isNull();
        });
    }

    @Test
    void a_lowered_max_output_bytes_applies_and_smaller_outputs_still_pass() {
        RuleEngines engines = engines(EngineSettings.defaults().withSizeLimits(500, 65_536, 5_000));
        publish(engines, echoTable(), "echo");

        DecisionResult small = engines.ruleEngine().evaluate("echo", DecisionInput.of(Map.of("note", "ok")));
        DecisionResult large = engines.ruleEngine().evaluate("echo", DecisionInput.of(Map.of("note", "x".repeat(20))));

        assertThat(small.matchedRuleIds()).hasSize(20);
        assertThat(((DecisionResult.Failed) large.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(large.outputs()).isEmpty();
    }

    @Test
    void string_plus_beyond_max_string_length_fails_before_the_string_is_built() {
        RuleEngines engines = engines(EngineSettings.defaults().withSizeLimits(1_048_576, 10, 5_000));
        publish(engines, """
                schemaVersion: 1
                ruleSet: concat
                hitPolicy: first
                inputs:
                  - { name: a, type: string }
                outputs:
                  - { name: joined, type: string }
                rules:
                  - { id: C1, when: {}, then: { joined: "a + a" } }
                """, "concat");

        DecisionResult fits = engines.ruleEngine().evaluate("concat", DecisionInput.of(Map.of("a", "abcde")));
        DecisionResult tooLong = engines.ruleEngine().evaluate("concat", DecisionInput.of(Map.of("a", "abcdef")));

        assertThat(fits.outputs()).containsEntry("joined", "abcdeabcde");
        assertThat(((DecisionResult.Failed) tooLong.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(((DecisionResult.Failed) tooLong.outcome()).detail()).contains("String longer than 10 characters");
        assertThat(tooLong.outputs()).isEmpty();
    }

    @Test
    void a_string_input_beyond_max_string_length_is_input_invalid_and_logs_nothing() {
        RuleEngines engines = engines(EngineSettings.defaults());
        publish(engines, echoTable(), "echo");

        assertThatThrownBy(() -> engines.ruleEngine().evaluate("echo", DecisionInput.of(Map.of("note", "x".repeat(65_537)))))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("longer than 65536");
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void aggregated_outputs_fold_each_value_in_and_never_hold_a_column_of_values() {
        // Given 5,000 rules whose min, max and count outputs each build a new 60,001-character string: holding them
        // as a column would retain about 600 MB for one request (security re-review of #18)
        StringBuilder yaml = new StringBuilder("""
                schemaVersion: 1
                ruleSet: big-aggregates
                hitPolicy: collect
                inputs:
                  - { name: note, type: string }
                outputs:
                  - { name: shortest, type: string,  collect: min }
                  - { name: longest,  type: string,  collect: max }
                  - { name: rows,     type: integer, collect: count }
                rules:
                """);
        for (int i = 0; i < 5_000; i++) {
            yaml.append("  - { id: B").append(i).append(", when: {}, then: { shortest: \"note + 'a'\", longest: \"note + 'b'\", rows: \"note + 'c'\" } }\n");
        }
        RuleEngines engines = engines(EngineSettings.defaults());
        publish(engines, yaml.toString(), "big-aggregates");

        // When
        DecisionResult result = engines.ruleEngine().evaluate("big-aggregates",
                DecisionInput.of(Map.of("note", "x".repeat(60_000))));

        // Then the aggregates are right and small: only the two winning strings are kept
        assertThat(result.matchedRuleIds()).hasSize(5_000);
        assertThat((String) result.outputs().get("shortest")).hasSize(60_001).endsWith("a");
        assertThat((String) result.outputs().get("longest")).hasSize(60_001).endsWith("b");
        assertThat(result.outputs()).containsEntry("rows", java.math.BigDecimal.valueOf(5_000));
    }

    @Test
    void aggregate_folding_keeps_the_strict_null_and_currency_rules() {
        RuleEngines engines = engines(EngineSettings.defaults());
        publish(engines, """
                schemaVersion: 1
                ruleSet: fold-rules
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: total, type: money,   collect: sum }
                  - { name: hits,  type: integer, collect: count }
                rules:
                  - { id: F1, when: {}, then: { total: "1000 VND", hits: 1 } }
                  - { id: F2, when: {}, then: { total: null, hits: null } }
                  - { id: F3, when: {}, then: { total: "2.50 USD", hits: 1 } }
                """, "fold-rules");

        DecisionResult result = engines.ruleEngine().evaluate("fold-rules", DecisionInput.of(Map.of("x", 1)));

        // F2's null under sum fails first (errorMode strict), naming the rule; no partial outputs
        assertThat(((DecisionResult.Failed) result.outcome()).code()).isEqualTo("EVALUATION_ERROR");
        assertThat(((DecisionResult.Failed) result.outcome()).detail()).contains("total").contains("F2");
        assertThat(result.matchedRuleIds()).containsExactly("F1", "F2", "F3");
        assertThat(result.outputs()).isEmpty();

        // And without the null, the two currencies still fail
        publish(engines, """
                schemaVersion: 1
                ruleSet: fold-currencies
                hitPolicy: collect
                inputs:
                  - { name: x, type: integer }
                outputs:
                  - { name: total, type: money, collect: sum }
                rules:
                  - { id: F1, when: {}, then: { total: "1000 VND" } }
                  - { id: F3, when: {}, then: { total: "2.50 USD" } }
                """, "fold-currencies");
        DecisionResult mixed = engines.ruleEngine().evaluate("fold-currencies", DecisionInput.of(Map.of("x", 1)));
        assertThat(((DecisionResult.Failed) mixed.outcome()).detail()).contains("currency mismatch");
    }

    @Test
    void TR_EXT_03_a_rule_set_with_more_rules_than_max_rows_is_refused_at_import() {
        RuleEngines engines = engines(EngineSettings.defaults().withSizeLimits(1_048_576, 65_536, 19));

        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                ImportRequest.yaml(echoTable().getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("20 rules; evaluation.max-rows allows 19");
        assertThat(stores.versions).isEmpty();
    }
}
