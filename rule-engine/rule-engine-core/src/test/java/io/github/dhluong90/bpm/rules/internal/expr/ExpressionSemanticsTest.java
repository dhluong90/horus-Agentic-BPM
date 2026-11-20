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
        "income between 1 VND and 2 VND          | NULL",
        "tier in ['GOLD', null]                  | true",
        "tier not in ['GOLD', null]              | false"
    })
    void null_follows_feel_three_valued_logic(String condition, Boolean expected) {
        assertThat(eval(condition.strip(), null, null)).isEqualTo(expected);
    }

    @Test
    void RLE_006_in_and_between_follow_dmn_1_5_with_a_null_operand() {
        // in: true when an element equals the target, so a missing tier is in a list only when the list holds null
        assertThat(eval("tier in ['GOLD', null]", null, null)).isEqualTo(true);
        assertThat(eval("tier not in ['GOLD', null]", null, null)).isEqualTo(false);
        assertThat(eval("tier in ['GOLD', 'PLATINUM']", null, null)).isEqualTo(false);

        // between: x >= a and x <= b, here with an unknown upper limit (balance used as the limit)
        assertThat(eval("txCount between 1 and balance", null, null, new BigDecimal(5), null)).isNull();
        assertThat(eval("txCount between 1 and balance", null, null, BigDecimal.ZERO, null)).isEqualTo(false);
        assertThat(eval("txCount between balance and 3", null, null, new BigDecimal(5), null)).isEqualTo(false);
        assertThat(eval("txCount between 1 and 3", null, null, null, null)).isNull();
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
}
