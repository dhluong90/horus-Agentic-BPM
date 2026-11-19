package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The FEEL-aligned semantics of #19 through import, approval and evaluation: the literal currency check, the
 * function argument checks and a published table that uses {@code round} and {@code daysBetween}.
 */
class FeelSemanticsLifecycleTest {

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final RuleEngines engines = RuleEngines.create(
            new EngineContext(caller, caller, Clock.fixed(Instant.parse("2027-03-01T02:00:00Z"), ZoneOffset.UTC),
                    stores, stores, EngineSettings.defaults()),
            stores, stores, stores);

    void importYaml(String yaml) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    void publish(String yaml, String name) {
        importYaml(yaml);
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
        caller.become("bank-a", "teller");
    }

    static String table(String inputType, String when, String outputType, String then) {
        return """
                schemaVersion: 1
                ruleSet: check
                hitPolicy: first
                inputs:
                  - { name: x, type: %s }
                outputs:
                  - { name: o, type: %s }
                rules:
                  - { id: R1, when: { x: "%s" }, then: { o: "%s" } }
                """.formatted(inputType, outputType, when, then);
    }

    @ParameterizedTest(name = "{0} / {2}")
    @CsvSource(delimiter = '|', value = {
        "money   | x + 5 USD >= 50000000 VND      | true                | mixes the currencies USD and VND",
        "money   | x in [5 USD, 10 VND]           | true                | mixes the currencies",
        "money   | x between 1 USD and 2 VND      | true                | mixes the currencies USD and VND",
        "money   | -(1 USD) < 1 VND               | true                | mixes the currencies USD and VND",
        "money   | >= 0 VND                       | 10000 VND + 1 USD   | mixes the currencies VND and USD"
    })
    void RLE_005_a_currency_mismatch_between_literals_is_a_validation_error(String type, String when, String then,
            String message) {
        // Given a cell whose two money literals have different currencies
        String outputType = then.strip().equals("true") ? "boolean" : "money";
        String yaml = table(type.strip(), when.strip(), outputType, then.strip());

        // When / Then: refused at import with the rule and column, nothing stored
        assertThatThrownBy(() -> importYaml(yaml))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .anySatisfy(v -> {
                            assertThat(v.ruleId()).isEqualTo("R1");
                            assertThat(v.message()).contains(message.strip());
                        }));
        assertThat(stores.decisions).isEmpty();
    }

    @Test
    void RLE_005_the_balance_example_fails_at_evaluation_with_no_partial_outputs() {
        // Given balance >= 50000000 VND, whose input currency is known only at run time
        publish(table("money", ">= 50000000 VND", "boolean", "true"), "check");

        // When balance is 3000.00 USD
        DecisionResult result = engines.ruleEngine().evaluate("check",
                DecisionInput.of(Map.of("x", Money.ofMinor("USD", 300000))));

        // Then
        assertThat(result.isError()).isTrue();
        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                failed -> assertThat(failed.code()).isEqualTo("EVALUATION_ERROR"));
        assertThat(result.outputs()).isEmpty();
        assertThat(stores.decisions).singleElement()
                .satisfies(row -> assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR"));
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource(delimiter = '|', value = {
        "string  | round(x, 0) > 1                     | round takes a number, not a string",
        "string  | daysBetween(x, date('2027-01-01')) > 1 | daysBetween takes a date, not a string",
        "integer | round(x, 0)                         | the condition does not produce true or false"
    })
    void RLE_021_a_function_with_an_argument_of_the_wrong_type_is_refused_at_import(String type, String when,
            String message) {
        String yaml = table(type.strip(), when.strip(), "boolean", "true");

        assertThatThrownBy(() -> importYaml(yaml))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .anySatisfy(v -> assertThat(v.message()).contains(message.strip())));
    }

    @Test
    void RLE_021_a_published_table_rounds_half_even_and_counts_days() {
        // Given a dormancy fee table: a VIP code waives the fee; a year without activity costs 0.15% of the balance, rounded half-even
        publish("""
                schemaVersion: 1
                ruleSet: dormancy-fee
                hitPolicy: first
                inputs:
                  - { name: code,         type: string }
                  - { name: balance,      type: decimal }
                  - { name: lastActivity, type: date }
                outputs:
                  - { name: fee, type: decimal }
                rules:
                  - { id: R1, when: { code: "== 'VIP'" }, then: { fee: "0" } }
                  - { id: R2, when: { lastActivity: "daysBetween(lastActivity, date('2027-03-01')) >= 365" },
                      then: { fee: "round(balance * 0.0015, 0)" } }
                  - { id: R3, when: {}, then: { fee: "0" } }
                """, "dormancy-fee");

        // When: a balance of 1 230 000 gives 1 845 exactly; 1 233 000 gives 1 849.5, which rounds to the even 1 850;
        // 1 231 000 gives 1 846.5, which rounds to the even 1 846
        DecisionResult vip = evaluate(Map.of("code", "VIP", "balance", new BigDecimal("1230000"),
                "lastActivity", LocalDate.parse("2025-01-01")));
        DecisionResult up = evaluate(Map.of("code", "STD", "balance", new BigDecimal("1233000"),
                "lastActivity", LocalDate.parse("2026-03-01")));
        DecisionResult down = evaluate(Map.of("code", "STD", "balance", new BigDecimal("1231000"),
                "lastActivity", LocalDate.parse("2026-03-01")));
        Map<String, Object> missing = new HashMap<>();
        missing.put("code", null);
        missing.put("balance", new BigDecimal("1231000"));
        missing.put("lastActivity", null);
        DecisionResult unknown = evaluate(missing);

        // Then
        assertThat(vip.matchedRuleIds()).containsExactly("R1");
        assertThat(up.matchedRuleIds()).containsExactly("R2");
        assertThat((BigDecimal) up.outputs().get("fee")).isEqualByComparingTo("1850");
        assertThat(down.matchedRuleIds()).containsExactly("R2");
        assertThat((BigDecimal) down.outputs().get("fee")).isEqualByComparingTo("1846");
        assertThat(unknown.matchedRuleIds()).as("null arguments give null, which never matches").containsExactly("R3");
    }

    DecisionResult evaluate(Map<String, ?> inputs) {
        return engines.ruleEngine().evaluate("dormancy-fee", DecisionInput.of(inputs));
    }
}
