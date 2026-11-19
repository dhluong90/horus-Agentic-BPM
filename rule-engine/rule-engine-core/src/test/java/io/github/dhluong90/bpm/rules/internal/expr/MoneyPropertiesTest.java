package io.github.dhluong90.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Money in expressions is exact in minor units (DESIGN section 3.4): no double anywhere, rounding only
 * where the rule set's {@code moneyRounding} says so, overflow refused rather than wrapped. The oracle
 * is {@code BigDecimal} arithmetic written out here, not the evaluator.
 */
class MoneyPropertiesTest {

    static final Map<String, Integer> SLOTS = Map.of("x", 0, "y", 1, "rate", 2);

    static Object eval(RoundingMode rounding, String source, Object... slots) {
        Object[] values = new Object[3];
        System.arraycopy(slots, 0, values, 0, slots.length);
        return new Evaluator(rounding).evaluate(Parser.parse(source, n -> SLOTS.getOrDefault(n, -1)), values);
    }

    @Provide
    Arbitrary<String> currency() {
        return Arbitraries.of("VND", "USD", "JPY", "BHD", "EUR");
    }

    @Property
    void RLE_001_money_addition_and_subtraction_are_exact_in_minor_units(
            @ForAll("currency") String currency,
            @ForAll @LongRange(min = -Money.MAX_JSON_SAFE, max = Money.MAX_JSON_SAFE) long a,
            @ForAll @LongRange(min = -Money.MAX_JSON_SAFE, max = Money.MAX_JSON_SAFE) long b) {
        Money x = Money.ofMinor(currency, a);
        Money y = Money.ofMinor(currency, b);

        assertThat(eval(RoundingMode.HALF_EVEN, "x + y", x, y)).isEqualTo(Money.ofMinor(currency, a + b));
        assertThat(eval(RoundingMode.HALF_EVEN, "x - y", x, y)).isEqualTo(Money.ofMinor(currency, a - b));
        assertThat(eval(RoundingMode.HALF_EVEN, "(x + y) - y", x, y)).isEqualTo(x);
    }

    @Property
    void RLE_001_money_times_a_rate_rounds_once_to_the_currency_scale(
            @ForAll("currency") String currency,
            @ForAll @LongRange(min = -1_000_000_000_000L, max = 1_000_000_000_000L) long minor,
            @ForAll("rates") BigDecimal rate,
            @ForAll("roundings") RoundingMode rounding) {
        Money money = Money.ofMinor(currency, minor);
        int scale = Money.scaleOf(currency);
        long expected = money.toMajor().multiply(rate).setScale(scale, rounding).movePointRight(scale).longValueExact();

        assertThat(eval(rounding, "x * rate", money, null, rate)).isEqualTo(Money.ofMinor(currency, expected));
        assertThat(eval(rounding, "rate * x", money, null, rate)).isEqualTo(Money.ofMinor(currency, expected));
    }

    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.bigDecimals().between(new BigDecimal("-10"), new BigDecimal("10")).ofScale(6);
    }

    @Provide
    Arbitrary<RoundingMode> roundings() {
        return Arbitraries.of(RoundingMode.HALF_EVEN, RoundingMode.HALF_UP, RoundingMode.DOWN, RoundingMode.UP,
                RoundingMode.FLOOR, RoundingMode.CEILING);
    }

    @Property
    void RLE_001_money_round_trips_through_major_units_without_loss(
            @ForAll("currency") String currency,
            @ForAll @LongRange(min = -Money.MAX_JSON_SAFE, max = Money.MAX_JSON_SAFE) long minor) {
        Money money = Money.ofMinor(currency, minor);

        assertThat(Money.ofMajor(currency, money.toMajor().toPlainString())).isEqualTo(money);
        assertThat(eval(RoundingMode.HALF_EVEN, money.toMajor().abs().toPlainString() + " " + currency))
                .isEqualTo(Money.ofMinor(currency, Math.abs(minor)));
    }

    @Example
    void RLE_001_half_even_and_half_up_differ_on_the_tie() {
        // 0.25 USD * 0.5 = 0.125 USD: half-even gives 0.12, half-up 0.13
        Money quarter = Money.ofMinor("USD", 25);
        BigDecimal half = new BigDecimal("0.5");

        assertThat(eval(RoundingMode.HALF_EVEN, "x * rate", quarter, null, half)).isEqualTo(Money.ofMinor("USD", 12));
        assertThat(eval(RoundingMode.HALF_UP, "x * rate", quarter, null, half)).isEqualTo(Money.ofMinor("USD", 13));
    }

    @Example
    void RLE_001_money_overflow_fails_instead_of_wrapping() {
        Money max = Money.ofMinor("VND", Long.MAX_VALUE);
        Money min = Money.ofMinor("VND", Long.MIN_VALUE);

        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "x + 1 VND", max)).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "x - 1 VND", min)).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "-x", min)).isInstanceOf(EvaluationException.class);
        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "x * 2", max)).isInstanceOf(EvaluationException.class);
    }

    @Example
    void RLE_001_money_literals_never_round() {
        for (String literal : new String[] {"0.5 VND", "1.005 USD", "0.0001 BHD", "1.5 JPY"}) {
            assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, literal))
                    .as(literal).isInstanceOf(ExpressionException.class).hasMessageContaining("Invalid money literal");
        }
        assertThat(eval(RoundingMode.HALF_EVEN, "1.000 BHD")).isEqualTo(Money.ofMinor("BHD", 1000));
        assertThat(eval(RoundingMode.HALF_EVEN, "12.50 USD")).isEqualTo(Money.ofMinor("USD", 1250));
    }

    @Example
    void RLE_001_money_of_a_different_currency_or_kind_never_compares_silently() {
        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "x == y", Money.ofMinor("VND", 0), Money.ofMinor("USD", 0)))
                .isInstanceOf(EvaluationException.class).hasMessageContaining("Currency mismatch");
        assertThatThrownBy(() -> eval(RoundingMode.HALF_EVEN, "x + y", Money.ofMinor("VND", 1), Money.ofMinor("USD", 1)))
                .isInstanceOf(EvaluationException.class);
        assertThat(eval(RoundingMode.HALF_EVEN, "x < 5", Money.ofMinor("VND", 1))).isNull();
    }
}
