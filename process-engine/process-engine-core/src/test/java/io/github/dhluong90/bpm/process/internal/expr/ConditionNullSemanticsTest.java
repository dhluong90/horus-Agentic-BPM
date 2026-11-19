package io.github.dhluong90.bpm.process.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Gateway conditions follow the Rule Engine's null, decimal and money rules (RLE-006, PD-058). The first test is
 * the RLE-006 example with {@code income = null} and {@code tier = null}: a condition holds only when it is true.
 */
class ConditionNullSemanticsTest {

    private static final Map<String, Object> NULLS = new HashMap<>();

    static {
        NULLS.put("income", null);
        NULLS.put("tier", null);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
        "income < 5000000 VND                     | false",   // R1: null, does not hold
        "income is null                           | true",    // R2
        "tier != 'GOLD'                           | true",    // R3: null != 'GOLD' is true
        "not(income < 5000000 VND)                | false",   // R4: not(null) is null
        "tier == 'GOLD'                           | false",   // R6
        "income < 5000000 VND or tier is null     | true",    // R7: null or true
        "income < 5000000 VND and tier is null    | false",   // R8: null and true is null
        "income is not null                       | false",
        "tier == null                             | true"
    })
    void null_follows_feel_three_valued_logic_and_only_true_holds(String condition, boolean holds) {
        assertThat(Condition.parse(condition).test(NULLS)).isEqualTo(holds);
    }

    @ParameterizedTest(name = "{0} and {1}")
    @CsvSource({"true,true,true", "true,false,false", "true,,", "false,,false", ",,", "false,false,false"})
    void and_follows_the_feel_truth_table(Boolean a, Boolean b, Boolean expected) {
        assertThat(eval("a and b", a, b)).isEqualTo(expected == null ? Boolean.FALSE : expected);
        assertThat(eval("b and a", a, b)).isEqualTo(expected == null ? Boolean.FALSE : expected);
    }

    @ParameterizedTest(name = "{0} or {1}")
    @CsvSource({"true,true,true", "true,false,true", "true,,true", "false,,", ",,", "false,false,false"})
    void or_follows_the_feel_truth_table(Boolean a, Boolean b, Boolean expected) {
        assertThat(eval("a or b", a, b)).isEqualTo(expected == null ? Boolean.FALSE : expected);
        // De Morgan holds in three-valued logic: not(a or b) == not a and not b, including null
        assertThat(eval("not(a or b)", a, b)).isEqualTo(eval("not a and not b", a, b));
    }

    @Test
    void numbers_are_decimal128_and_money_compares_within_one_currency() {
        Map<String, Object> vars = Map.of("amount", new BigDecimal("0.1"), "fee", Money.ofMinor("VND", 150_000_000));
        assertThat(Condition.parse("amount == 0.10").test(vars)).isTrue();
        assertThat(Condition.parse("fee >= 100000000 VND").test(vars)).isTrue();
        assertThatThrownBy(() -> Condition.parse("fee > 0 USD").test(vars)).isInstanceOf(ConditionException.class);
        assertThatThrownBy(() -> Condition.parse("fee > 1").test(vars)).isInstanceOf(ConditionException.class);
        assertThatThrownBy(() -> Condition.parse("amount == 'x'").test(vars)).isInstanceOf(ConditionException.class);
    }

    @Test
    void an_out_of_range_money_literal_is_a_condition_error_not_a_crash() {
        assertThatThrownBy(() -> Condition.parse("fee > 99999999999999999999999 USD"))
                .isInstanceOf(ConditionException.class);
    }

    private static Boolean eval(String condition, Boolean a, Boolean b) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("a", a);
        vars.put("b", b);
        return Condition.parse(condition).test(vars);
    }
}
