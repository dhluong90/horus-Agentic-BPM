package io.github.dhluong90.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Parser and evaluator semantics. The null cases are the conditions of RLE-006 (PD-058); the full
 * statement, with the collect hit policy and replay, is proved by the RLE-006 test of a later slice.
 */
class ExpressionSemanticsTest {

    static final Map<String, Integer> SLOTS = Map.of("income", 0, "tier", 1, "txCount", 2, "balance", 3);
    final Evaluator evaluator = new Evaluator(RoundingMode.HALF_EVEN);

    Object eval(String source, Object... slots) {
        Object[] values = new Object[4];
        System.arraycopy(slots, 0, values, 0, slots.length);
        return evaluator.evaluate(Parser.parse(source, name -> SLOTS.getOrDefault(name, -1)), values);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "income < 5000000 VND                    | NULL",
        "income is null                          | true",
        "tier != 'GOLD'                          | true",
        "not(income < 5000000 VND)               | NULL",
        "tier not in ['GOLD', 'PLATINUM']        | true",
        "tier == 'GOLD'                          | false",
        "income < 5000000 VND or tier is null    | true",
        "income < 5000000 VND and tier is null   | NULL",
        "tier in ['GOLD', 'PLATINUM']            | false",
        "false and income > 0 VND                | false",
        "true or income > 0 VND                  | true",
        "income + 1 VND is null                  | true",
        "income between 1 VND and 2 VND          | NULL"
    })
    void null_follows_feel_three_valued_logic(String condition, Boolean expected) {
        assertThat(eval(condition.strip(), null, null)).isEqualTo(expected);
    }

    @Test
    void only_true_matches() {
        Expr unknown = Parser.parse("income < 5000000 VND", name -> SLOTS.getOrDefault(name, -1));

        assertThat(evaluator.matches(unknown, new Object[4])).isFalse();
    }

    @Test
    void numbers_are_decimal128_and_money_keeps_its_scale() {
        assertThat(eval("7 / 2")).isEqualTo(new BigDecimal("3.5"));
        assertThat(eval("txCount * 2 >= 10", null, null, new BigDecimal(5))).isEqualTo(true);
        assertThat(eval("balance * 0.015", null, null, null, Money.ofMinor("USD", 1250))).isEqualTo(Money.ofMinor("USD", 19));
        assertThat(eval("12.50 USD + 0.25 USD")).isEqualTo(Money.ofMinor("USD", 1275));
        assertThat(eval("-5 VND")).isEqualTo(Money.ofMinor("VND", -5));
    }

    @Test
    void mixed_currencies_and_division_by_zero_fail_deterministically() {
        assertThatThrownBy(() -> eval("balance >= 50000000 VND", null, null, null, Money.ofMinor("USD", 300000)))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> eval("1 / 0")).isInstanceOf(EvaluationException.class);
    }

    @Test
    void strings_compare_by_code_point_and_lists_test_membership() {
        assertThat(eval("tier < 'SILVER'", null, "GOLD")).isEqualTo(true);
        assertThat(eval("tier in ['GOLD', 'PLATINUM']", null, "GOLD")).isEqualTo(true);
        assertThat(eval("tier not in ['GOLD']", null, "GOLD")).isEqualTo(false);
        assertThat(eval("txCount between 1 and 3", null, null, BigDecimal.TWO)).isEqualTo(true);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "creditScoreX > 1          | Unknown input 'creditScoreX'",
        "T(java.lang.Runtime)      | Unexpected character '.'",
        "exec(1)                   | Unknown function 'exec'",
        "tier == 'GOLD             | Unterminated string",
        "tier ==                   | Unexpected end of expression",
        "[1, 2]                    | List values",
        "10.5 VND                  | Invalid money literal",
        "tier == 'GOLD' tier       | Unexpected 'tier'"
    })
    void invalid_expressions_fail_to_parse_with_a_position(String source, String message) {
        assertThatThrownBy(() -> Parser.parse(source.strip(), name -> SLOTS.getOrDefault(name, -1)))
                .isInstanceOf(ExpressionException.class).hasMessageContaining(message);
    }

    @Test
    void nesting_is_bounded() {
        String deep = "(".repeat(200) + "1" + ")".repeat(200);

        assertThatThrownBy(() -> Parser.parse(deep, name -> -1)).isInstanceOf(ExpressionException.class)
                .hasMessageContaining("nested deeper");
    }

    @Test
    void matches_is_an_unanchored_search_and_null_text_gives_null() {
        assertThat(eval("matches('ab12', '[0-9]+')")).isEqualTo(true);
        assertThat(eval("matches('ab12', '^[0-9]+$')")).isEqualTo(false);
        assertThat(eval("matches(tier, 'GOLD')", null, "PLATINUM-GOLD")).isEqualTo(true);
        assertThat(eval("matches(tier, 'GOLD')", null, null)).isNull();
        assertThat(eval("not(matches(tier, 'GOLD'))", null, null)).isNull();
        assertThat(Parser.parse("matches(tier, 'G')", name -> SLOTS.getOrDefault(name, -1)))
                .isEqualTo(Parser.parse("matches(tier, 'G')", name -> SLOTS.getOrDefault(name, -1)));
    }

    @Test
    void matches_with_one_argument_needs_a_condition_cell() {
        assertThatThrownBy(() -> Parser.parse("matches('GOLD')", name -> SLOTS.getOrDefault(name, -1)))
                .isInstanceOf(ExpressionException.class).hasMessageContaining("matches(input, 'pattern')");
        assertThat(evaluator.evaluate(Parser.parse("matches('^GO')", name -> SLOTS.getOrDefault(name, -1), "tier"),
                new Object[] {null, "GOLD", null, null})).isEqualTo(true);
    }

    /** The fixed cases of RLE-021 for {@code matches}, shared with the FEEL alignment slice (#19). */
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
    void matches_is_an_unanchored_search_in_the_fixed_cases_shared_with_the_feel_slice(String expression, boolean expected) {
        assertThat(eval(expression.strip())).isEqualTo(expected);
    }
}
