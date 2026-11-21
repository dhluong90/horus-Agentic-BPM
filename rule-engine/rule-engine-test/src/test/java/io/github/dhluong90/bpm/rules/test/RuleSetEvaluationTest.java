package io.github.dhluong90.bpm.rules.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * RLE-059: a rule set file is evaluated in JUnit with the engine's own reader, compiler and evaluator, at a fixed
 * clock. The card fee table of bank-a: GOLD pays nothing, three or more transactions pay 10,000 VND, the rest 50,000
 * VND.
 */
@RuleSetTest("rules/card-fee.yaml")
class RuleSetEvaluationTest {

    @Test
    void RLE_059_rule_set_is_evaluated_in_junit_like_the_engine(RuleSetUnderTest rules) throws IOException {
        Map<String, Object> silver = Map.of("tier", "SILVER", "txCount", 5);

        DecisionResult result = rules.evaluate(silver);

        assertThat(result.matchedRuleIds()).containsExactly("R2");
        assertThat(result.outputs()).containsExactly(Map.entry("fee", Money.ofMinor("VND", 10_000)));
        assertThat(result.output("fee", Money.class)).isEqualTo(Money.ofMinor("VND", 10_000));
        assertThatThrownBy(() -> result.output("fee", String.class)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Output fee is a Money, not a String");
        assertThat(result.version()).isEqualTo(1);
        assertThat(result.clock()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        // the outcome is what the engine's evaluator gives for the same file, inputs and clock
        CompiledRuleSet engine = CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.YAML,
                Files.readAllBytes(Path.of("src/test/resources/rules/card-fee.yaml"))).model());
        assertThat(engine.evaluate(DecisionInput.of(silver), result.clock())).isEqualTo(result.outcome());
        // an undeclared input is INPUT_INVALID naming it, as evaluation.reject-undeclared-inputs does by default
        assertThatThrownBy(() -> rules.evaluate(Map.of("tier", "GOLD", "extra", 1)))
                .isInstanceOf(InputInvalidException.class).hasMessageContaining("extra");
        // two evaluations of the same inputs give the same result
        assertThat(rules.evaluate(silver).outcome()).isEqualTo(result.outcome());
        assertThat(rules.canonicalHash()).hasSize(64);
        assertThat(RuleSetUnderTest.TENANT).isEqualTo("test");
    }

    @Test
    void RLE_059_the_default_clock_is_fixed_and_a_class_or_test_may_change_it(RuleSetUnderTest rules) {
        assertThat(rules.clock()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(rules.evaluate(Map.of("tier", "SILVER", "txCount", 1), Instant.parse("2027-06-30T17:00:00Z")).clock())
                .isEqualTo(Instant.parse("2027-06-30T17:00:00Z"));
    }

    @Test
    void RLE_059_a_test_with_a_clock_of_its_own_reads_today_in_the_rule_sets_time_zone() {
        // the table reads today() in Asia/Ho_Chi_Minh: 17:00Z on 30 June is 00:00 on 1 July there
        RuleSetUnderTest cutoff = new RuleSetUnderTest(LoadedRuleSet.load("rules/same-day-cutoff.yaml",
                getClass().getClassLoader()), Instant.parse("2027-06-30T17:00:00Z"), "RuleSetEvaluationTest#clock",
                new CoverageRun(Path.of("target/horus-rules-coverage-unused"), null, System.out));

        assertThat(cutoff.evaluate(Map.of("txDate", java.time.LocalDate.parse("2027-07-01"))).outputs())
                .containsEntry("sameDay", true);
        assertThat(cutoff.evaluate(Map.of("txDate", java.time.LocalDate.parse("2027-06-30"))).outputs())
                .containsEntry("sameDay", false);
    }

    @Test
    @RuleSetClock("2027-06-30T17:00:00Z")
    void RLE_059_the_clock_annotation_overrides_the_default_for_one_test(RuleSetUnderTest rules) {
        assertThat(rules.clock()).isEqualTo(Instant.parse("2027-06-30T17:00:00Z"));
        assertThat(rules.evaluate(Map.of("tier", "GOLD", "txCount", 0)).clock())
                .isEqualTo(Instant.parse("2027-06-30T17:00:00Z"));
    }

    @Test
    void RLE_059_a_clock_that_is_not_an_instant_is_refused_naming_where() {
        assertThatThrownBy(() -> RuleSetExtension.parseClock("30/06/2027", "@RuleSetClock"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@RuleSetClock").hasMessageContaining("30/06/2027");
    }
}
