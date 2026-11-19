package io.github.dhluong90.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Properties of the parser and evaluator (DESIGN section 3.2, PD-058). The truth tables below are
 * written out from DESIGN 3.2 and checked for every operand combination; the evaluator is never its
 * own oracle. Inputs: {@code a}, {@code b} (any value), {@code income} (money VND), {@code tier}
 * (string), {@code txCount} (number). Properties run seeded tries through {@link Property}: 1,000
 * each unless the test says otherwise, every combination first where the generators are finite.
 */
class ExpressionPropertiesTest {

    static final Map<String, Integer> SLOTS = Map.of("a", 0, "b", 1, "income", 2, "tier", 3, "txCount", 4, "c", 5);
    static final Evaluator EVALUATOR = new Evaluator(RoundingMode.HALF_EVEN);

    /** Stands for an operand that is not a boolean: FEEL treats it like null in and, or, not. */
    static final String NOT_BOOLEAN = "not-a-boolean";

    static Expr parse(String source) {
        return Parser.parse(source, name -> SLOTS.getOrDefault(name, -1));
    }

    static Object eval(String source, Object... slots) {
        Object[] values = new Object[SLOTS.size()];
        System.arraycopy(slots, 0, values, 0, slots.length);
        return EVALUATOR.evaluate(parse(source), values);
    }

    // ---------------------------------------------------------------- truth tables (Kleene, FEEL)

    static final Gen<Object> THREE_VALUED = Gen.<Object>of(Boolean.TRUE, Boolean.FALSE, NOT_BOOLEAN).orNull(0.3);

    static Boolean asBool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    static Boolean andTable(Object l, Object r) {
        Boolean a = asBool(l);
        Boolean b = asBool(r);
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
            return false;
        }
        return Boolean.TRUE.equals(a) && Boolean.TRUE.equals(b) ? Boolean.TRUE : null;
    }

    static Boolean orTable(Object l, Object r) {
        Boolean a = asBool(l);
        Boolean b = asBool(r);
        if (Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b)) {
            return true;
        }
        return Boolean.FALSE.equals(a) && Boolean.FALSE.equals(b) ? Boolean.FALSE : null;
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_and_follows_the_feel_truth_table() {
        return Property.tries(1_000).forAll(THREE_VALUED, THREE_VALUED,
                (a, b) -> assertThat(eval("a and b", a, b)).isEqualTo(andTable(a, b)));
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_or_follows_the_feel_truth_table() {
        return Property.tries(1_000).forAll(THREE_VALUED, THREE_VALUED,
                (a, b) -> assertThat(eval("a or b", a, b)).isEqualTo(orTable(a, b)));
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_not_follows_the_feel_truth_table() {
        return Property.tries(1_000).forAll(THREE_VALUED, a -> {
            Boolean expected = asBool(a) == null ? null : !asBool(a);

            assertThat(eval("not(a)", a)).isEqualTo(expected);
            assertThat(eval("not a", a)).isEqualTo(expected);
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_and_or_are_commutative_and_de_morgan_holds() {
        return Property.tries(1_000).forAll(THREE_VALUED, THREE_VALUED, (a, b) -> {
            assertThat(eval("a and b", a, b)).isEqualTo(eval("b and a", a, b));
            assertThat(eval("a or b", a, b)).isEqualTo(eval("b or a", a, b));
            assertThat(eval("not(a and b)", a, b)).isEqualTo(eval("not(a) or not(b)", a, b));
            assertThat(eval("not(a or b)", a, b)).isEqualTo(eval("not(a) and not(b)", a, b));
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_a_condition_matches_only_when_it_is_true() {
        return Property.tries(1_000).forAll(THREE_VALUED,
                a -> assertThat(EVALUATOR.matches(parse("a"), new Object[] {a, null, null, null, null, null}))
                        .isEqualTo(Boolean.TRUE.equals(a)));
    }

    // ---------------------------------------------------------------- null operands

    /** A non-null value of one of the comparable kinds. */
    static final Gen<Object> COMPARABLE = Gen.oneOf(
            Gen.decimals("-1000000", "1000000", 2),
            Gen.longs(-1_000_000_000L, 1_000_000_000L).map(m -> Money.ofMinor("VND", m)),
            Gen.alphaStrings(6),
            Gen.longs(0, 50_000).map(LocalDate::ofEpochDay));

    static final Gen<String> ORDERING = Gen.of("<", "<=", ">", ">=");

    @TestFactory
    Stream<DynamicTest> RLE_006_ordering_with_a_null_operand_is_null() {
        return Property.tries(1_000).forAll(COMPARABLE, ORDERING, (value, op) -> {
            assertThat(eval("a " + op + " b", value, null)).isNull();
            assertThat(eval("a " + op + " b", null, value)).isNull();
            assertThat(eval("a " + op + " b", null, null)).isNull();
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_equality_with_null_is_two_valued() {
        return Property.tries(1_000).forAll(COMPARABLE, value -> {
            assertThat(eval("a == b", value, null)).isEqualTo(false);
            assertThat(eval("a == b", null, value)).isEqualTo(false);
            assertThat(eval("a == b", null, null)).isEqualTo(true);
            assertThat(eval("a != b", value, null)).isEqualTo(true);
            assertThat(eval("a != b", null, value)).isEqualTo(true);
            assertThat(eval("a != b", null, null)).isEqualTo(false);
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_in_is_false_and_not_in_is_true_for_a_null_target() {
        return Property.tries(1_000).forAll(COMPARABLE, element -> {
            assertThat(eval("a in [b]", null, element)).isEqualTo(false);
            assertThat(eval("a not in [b]", null, element)).isEqualTo(true);
            assertThat(eval("a in [b, c]", null, element, null, null, null, null)).isEqualTo(false);
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_is_null_is_always_true_or_false() {
        return Property.tries(1_000).forAll(COMPARABLE, value -> {
            assertThat(eval("a is null", value)).isEqualTo(false);
            assertThat(eval("a is not null", value)).isEqualTo(true);
            assertThat(eval("a is null", (Object) null)).isEqualTo(true);
            assertThat(eval("a is not null", (Object) null)).isEqualTo(false);
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_006_arithmetic_with_a_null_operand_is_null() {
        return Property.tries(1_000).forAll(Gen.longs(-1_000_000_000L, 1_000_000_000L), minor -> {
            Money money = Money.ofMinor("VND", minor);
            BigDecimal number = BigDecimal.valueOf(minor);

            assertThat(eval("income + b", null, null, money)).isNull();
            assertThat(eval("b - income", null, null, money)).isNull();
            assertThat(eval("txCount * b", null, null, null, null, number)).isNull();
            assertThat(eval("txCount / b", null, null, null, null, number)).isNull();
            assertThat(eval("-b")).isNull();
        });
    }

    /** DESIGN 3.2: "between ... with a null operand give null", for the target and for either bound. */
    @TestFactory
    Stream<DynamicTest> RLE_006_between_with_any_null_operand_is_null() {
        Gen<Long> small = Gen.longs(-1000, 1000);
        return Property.tries(1_000).forAll(small, small, (x, bound) -> {
            BigDecimal v = BigDecimal.valueOf(x);
            BigDecimal other = BigDecimal.valueOf(bound);

            assertThat(eval("a between b and c", null, v, null, null, null, other)).isNull();
            assertThat(eval("a between b and c", v, null, null, null, null, other)).isNull();
            assertThat(eval("a between b and c", v, other, null, null, null, null)).isNull();
        });
    }

    // ---------------------------------------------------------------- determinism

    /** Random well-formed expressions over the inputs; the generator is the grammar of Parser. */
    static final Gen<String> EXPRESSIONS = expression(4);

    static Gen<String> expression(int depth) {
        Gen<String> atom = Gen.oneOf(
                Gen.of("income", "tier", "txCount", "a", "b", "null", "true", "false"),
                Gen.longs(0, 99_999_999).map(n -> n + " VND"),
                Gen.decimals("0", "1000", 2).map(BigDecimal::toPlainString),
                Gen.of("'GOLD'", "'SILVER'", "''", "'Vàng'"));
        if (depth == 0) {
            return atom;
        }
        Gen<String> sub = expression(depth - 1);
        Gen<String> ops = Gen.of("and", "or", "==", "!=", "<", "<=", ">", ">=", "+", "-", "*", "/");
        return Gen.oneOf(
                atom,
                Gen.from(r -> "(" + sub.next(r) + ") " + ops.next(r) + " (" + sub.next(r) + ")"),
                sub.map(s -> "not(" + s + ")"),
                sub.map(s -> "-(" + s + ")"),
                sub.map(s -> "(" + s + ") is null"),
                Gen.from(r -> "(" + sub.next(r) + ") in [" + sub.next(r) + ", 'GOLD']"),
                Gen.from(r -> "(" + sub.next(r) + ") not in [" + sub.next(r) + "]"),
                Gen.from(r -> "(" + sub.next(r) + ") between (" + sub.next(r) + ") and (" + sub.next(r) + ")"));
    }

    static final Gen<Object[]> SLOT_VALUES = slotValues();

    static Gen<Object[]> slotValues() {
        Gen<Object> income = Gen.longs(-1_000_000_000L, 1_000_000_000L)
                .<Object>map(m -> Money.ofMinor("VND", m)).orNull(0.2);
        Gen<Object> tier = Gen.<Object>of("GOLD", "SILVER", "", "Vàng").orNull(0.2);
        Gen<Object> txCount = Gen.longs(-100, 100).<Object>map(BigDecimal::valueOf).orNull(0.2);
        return Gen.from(r -> new Object[] {
            THREE_VALUED.next(r), THREE_VALUED.next(r), income.next(r), tier.next(r), txCount.next(r), null});
    }

    @TestFactory
    Stream<DynamicTest> RLE_001_parse_and_evaluate_are_deterministic() {
        return Property.tries(1_000).forAll(EXPRESSIONS, SLOT_VALUES, (source, slots) -> {
            // Given the same text parsed twice
            Expr first = parse(source);
            Expr second = parse(source);

            // When both trees are evaluated over copies of the same inputs
            Object one = outcome(first, slots.clone());
            Object two = outcome(second, slots.clone());

            // Then the trees and the outcomes are equal, failures included
            assertThat(second).isEqualTo(first);
            assertThat(two).isEqualTo(one);
        });
    }

    private static Object outcome(Expr expr, Object[] slots) {
        try {
            return Objects.requireNonNullElse(EVALUATOR.evaluate(expr, slots), "<null>");
        } catch (EvaluationException e) {
            return "EvaluationException: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- parser total failure safety

    static final Gen<String> GARBAGE = garbage();

    static Gen<String> garbage() {
        Gen<String> fragments = Gen.of("(", ")", "[", "]", ",", "'", "\\", "==", "!=", "<", ">=", "+", "-",
                "*", "/", "not", "in", "is", "null", "between", "and", "or", "income", "tier", "VND", "USD", "XXX",
                "1", "0.5", "99999999999999999999999", " ", "T(java.lang.Runtime)", "é", "٣", "😀", "\t");
        return Gen.oneOf(
                Gen.strings(80),
                fragments.list(40).map(parts -> String.join("", parts)),
                fragments.list(40).map(parts -> String.join(" ", parts)));
    }

    /** TR-SEC-01: any text gives a tree or a structured {@link ExpressionException}, never another error. */
    @TestFactory
    Stream<DynamicTest> TR_SEC_01_parser_answers_any_text_with_a_tree_or_an_expression_exception() {
        return Property.tries(2_000).forAll(GARBAGE, source -> {
            try {
                assertThat(parse(source)).isNotNull();
            } catch (ExpressionException expected) {
                assertThat(expected.getMessage()).isNotBlank();
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> TR_SEC_01_deep_nesting_is_refused_without_a_stack_overflow() {
        return Property.tries(50).forAll(Gen.ints(65, 20_000), depth -> {
            String parens = "(".repeat(depth) + "1" + ")".repeat(depth);
            String nots = "not ".repeat(depth) + "true";
            String negations = "-".repeat(depth) + "1";

            for (String source : new String[] {parens, nots, negations}) {
                try {
                    parse(source);
                    assertThat(source.length()).as("accepted only within MAX_LENGTH")
                            .isLessThanOrEqualTo(Parser.MAX_LENGTH);
                } catch (ExpressionException expected) {
                    assertThat(expected.getMessage()).containsAnyOf("nested deeper", "longer than");
                }
            }
        });
    }
}
