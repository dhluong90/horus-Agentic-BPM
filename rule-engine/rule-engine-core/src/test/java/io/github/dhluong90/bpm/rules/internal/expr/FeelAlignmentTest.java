package io.github.dhluong90.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Number, rounding, regex and date semantics aligned with FEEL (rule-engine DESIGN 3.2 item 3, ADR-0019). The
 * fixed cases are those of RLE-021; the generated cases, against a reference FEEL implementation, are in
 * {@link FeelDifferentialTest}.
 */
class FeelAlignmentTest {

    static final Map<String, Integer> SLOTS = Map.of("amount", 0, "code", 1, "opened", 2, "closed", 3);
    final Evaluator evaluator = new Evaluator(RoundingMode.HALF_EVEN);

    Expr parse(String source) {
        return Parser.parse(source, name -> SLOTS.getOrDefault(name, -1));
    }

    Object eval(String source, Object... slots) {
        Object[] values = new Object[SLOTS.size()];
        System.arraycopy(slots, 0, values, 0, slots.length);
        return evaluator.evaluate(parse(source), values);
    }

    @Test
    void RLE_021_fixed_cases_give_the_feel_results() {
        // When / Then
        assertThat(eval("7 / 2")).isEqualTo(new BigDecimal("3.5"));
        assertThat((BigDecimal) eval("round(2.5, 0)")).isEqualByComparingTo("2");
        assertThat(eval("matches('ab12', '[0-9]+')")).isEqualTo(true);
        assertThat((BigDecimal) eval("daysBetween(date('2027-01-01'), date('2027-03-01'))")).isEqualByComparingTo("59");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "round(2.5, 0)       | 2",
        "round(3.5, 0)       | 4",
        "round(-2.5, 0)      | -2",
        "round(12.345, 2)    | 12.34",
        "round(12.355, 2)    | 12.36",
        "round(1250, -2)     | 1200",
        "round(1350, -2)     | 1400",
        "round(0.125, 2)     | 0.12",
        "round(7 / 3, 4)     | 2.3333"
    })
    void RLE_021_round_is_half_even(String expression, String expected) {
        // When
        Object value = eval(expression.strip());

        // Then
        assertThat((BigDecimal) value).isEqualByComparingTo(expected.strip());
    }

    @Test
    void RLE_021_round_keeps_the_requested_scale() {
        // Given a fee of 1 234.5 VND rounded to whole dong
        Object fee = eval("round(amount * 0.015, 0)", new BigDecimal("82300"));

        // Then: 1 234.5 rounds to the even 1 234
        assertThat(fee).isEqualTo(new BigDecimal("1234"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "matches('ab12', '[0-9]+')        | true",
        "matches('xaby', 'ab')            | true",
        "matches('AB', '^a')              | false",
        "matches('VN-001', '^VN-[0-9]{3}$') | true",
        "matches('a.b', 'a\\\\.b')        | true",
        "matches('axb', 'a\\\\.b')        | false",
        "matches('12 34', '\\\\d+\\\\s\\\\d+') | true",
        "matches('', 'a*')                | true"
    })
    void RLE_021_matches_is_an_unanchored_search(String expression, boolean expected) {
        assertThat(eval(expression.strip())).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "matches(code, '(a)\\\\1')",
        "matches(code, 'a(?=b)')",
        "matches(code, '(?<=a)b')",
        "matches(code, '(?i)abc')",
        "matches(code, 'a++')",
        "matches(code, 'a*+')",
        "matches(code, '\\\\bword')",
        "matches(code, '[a-z&&[^b]]')",
        "matches(code, '(')",
        "matches(code, '\\\\Qa\\\\E')",
        "matches(code, '\\\\p{IsBasicLatin}')"
    })
    void RLE_021_a_pattern_outside_the_xpath_regex_dialect_is_refused_at_import(String expression) {
        assertThatThrownBy(() -> parse(expression)).isInstanceOf(ExpressionException.class)
                .hasMessageContaining("pattern");
    }

    @Test
    void RLE_021_the_pattern_of_matches_is_a_string_literal() {
        assertThatThrownBy(() -> parse("matches(code, code)")).isInstanceOf(ExpressionException.class)
                .hasMessageContaining("string literal");
    }

    @Test
    void RLE_021_days_between_counts_calendar_days_and_may_be_negative() {
        // Given an account opened on 2028-02-01 (a leap year) and closed on 2028-03-01
        LocalDate opened = LocalDate.parse("2028-02-01");
        LocalDate closed = LocalDate.parse("2028-03-01");

        // When / Then
        assertThat((BigDecimal) eval("daysBetween(opened, closed)", null, null, opened, closed)).isEqualByComparingTo("29");
        assertThat((BigDecimal) eval("daysBetween(closed, opened)", null, null, opened, closed)).isEqualByComparingTo("-29");
        assertThat(eval("daysBetween(opened, closed) >= 30", null, null, opened, closed)).isEqualTo(false);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "round(amount, 0)",
        "round(2.5, amount)",
        "matches(code, 'a')",
        "daysBetween(opened, date('2027-01-01'))",
        "daysBetween(date('2027-01-01'), closed)"
    })
    void RLE_006_a_function_with_a_null_argument_gives_null(String expression) {
        assertThat(eval(expression)).isNull();
    }

    @Test
    void RLE_006_a_condition_on_a_null_function_result_does_not_match() {
        // Given a missing customer code
        Expr condition = parse("matches(code, '^VIP') or round(amount, 0) > 10");

        // Then: null or null is null, which never matches
        assertThat(evaluator.matches(condition, new Object[SLOTS.size()])).isFalse();
        assertThat(evaluator.evaluate(parse("not (matches(code, '^VIP'))"), new Object[SLOTS.size()])).isNull();
    }

    @Test
    void RLE_006_in_is_true_for_a_null_target_only_when_the_list_holds_null() {
        // FEEL: list contains([1, null], null) is true; v1 x in [...] is true when an element equals x
        assertThat(eval("amount in [1, null]")).isEqualTo(true);
        assertThat(eval("amount in [1, 2]")).isEqualTo(false);
        assertThat(eval("amount not in [1, null]")).isEqualTo(false);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "round(2.5)                      | round takes 2 arguments",
        "round(2.5, 0, 1)                | round takes 2 arguments",
        "daysBetween(date('2027-01-01')) | daysBetween takes 2 arguments",
        "roundUp(2.5, 0)                 | Unknown function 'roundUp'",
        "round(2.5, 0.5)                 | scale"
    })
    void RLE_021_a_wrong_call_is_refused_at_import(String expression, String message) {
        assertThatThrownBy(() -> parse(expression.strip())).isInstanceOf(ExpressionException.class)
                .hasMessageContaining(message.strip());
    }

    @Test
    void a_runaway_regex_search_fails_deterministically_at_its_step_budget() {
        // Given a pattern that backtracks without end in Java and a short adversarial code
        String adversarial = "a".repeat(40);

        // When / Then: an evaluation error, never a hung thread; the time limit is RLE-007
        assertThatThrownBy(() -> eval("matches(code, '(.*){1,20}b')", null, adversarial))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("step budget");
    }
}
