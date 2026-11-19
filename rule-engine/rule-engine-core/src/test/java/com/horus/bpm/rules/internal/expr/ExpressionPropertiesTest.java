package com.horus.bpm.rules.internal.expr;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * Properties of the parser and evaluator (DESIGN section 3.2, PD-058). The truth tables below are
 * written out from DESIGN 3.2 and checked for every operand combination; the evaluator is never its
 * own oracle. Inputs: {@code a}, {@code b} (any value), {@code income} (money VND), {@code tier}
 * (string), {@code txCount} (number).
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

    @Provide
    Arbitrary<Object> threeValued() {
        return Arbitraries.<Object>of(Boolean.TRUE, Boolean.FALSE, NOT_BOOLEAN).injectNull(0.3);
    }

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

    @Property
    void RLE_006_and_follows_the_feel_truth_table(@ForAll("threeValued") Object a, @ForAll("threeValued") Object b) {
        assertThat(eval("a and b", a, b)).isEqualTo(andTable(a, b));
    }

    @Property
    void RLE_006_or_follows_the_feel_truth_table(@ForAll("threeValued") Object a, @ForAll("threeValued") Object b) {
        assertThat(eval("a or b", a, b)).isEqualTo(orTable(a, b));
    }

    @Property
    void RLE_006_not_follows_the_feel_truth_table(@ForAll("threeValued") Object a) {
        Boolean expected = asBool(a) == null ? null : !asBool(a);

        assertThat(eval("not(a)", a)).isEqualTo(expected);
        assertThat(eval("not a", a)).isEqualTo(expected);
    }

    @Property
    void RLE_006_and_or_are_commutative_and_de_morgan_holds(
            @ForAll("threeValued") Object a, @ForAll("threeValued") Object b) {
        assertThat(eval("a and b", a, b)).isEqualTo(eval("b and a", a, b));
        assertThat(eval("a or b", a, b)).isEqualTo(eval("b or a", a, b));
        assertThat(eval("not(a and b)", a, b)).isEqualTo(eval("not(a) or not(b)", a, b));
        assertThat(eval("not(a or b)", a, b)).isEqualTo(eval("not(a) and not(b)", a, b));
    }

    @Property
    void RLE_006_a_condition_matches_only_when_it_is_true(@ForAll("threeValued") Object a) {
        assertThat(EVALUATOR.matches(parse("a"), new Object[] {a, null, null, null, null, null}))
                .isEqualTo(Boolean.TRUE.equals(a));
    }

    // ---------------------------------------------------------------- null operands

    /** A non-null value of one of the comparable kinds, with a second value of the same kind. */
    @Provide
    Arbitrary<Object> comparable() {
        return Arbitraries.oneOf(
                Arbitraries.bigDecimals().between(new BigDecimal("-1000000"), new BigDecimal("1000000")).ofScale(2)
                        .map(Object.class::cast),
                Arbitraries.longs().between(-1_000_000_000L, 1_000_000_000L).map(m -> (Object) Money.ofMinor("VND", m)),
                Arbitraries.strings().alpha().ofMaxLength(6).map(Object.class::cast),
                Arbitraries.longs().between(0, 50_000).map(d -> (Object) LocalDate.ofEpochDay(d)));
    }

    @Provide
    Arbitrary<String> ordering() {
        return Arbitraries.of("<", "<=", ">", ">=");
    }

    @Property
    void RLE_006_ordering_with_a_null_operand_is_null(@ForAll("comparable") Object value, @ForAll("ordering") String op) {
        assertThat(eval("a " + op + " b", value, null)).isNull();
        assertThat(eval("a " + op + " b", null, value)).isNull();
        assertThat(eval("a " + op + " b", null, null)).isNull();
    }

    @Property
    void RLE_006_equality_with_null_is_two_valued(@ForAll("comparable") Object value) {
        assertThat(eval("a == b", value, null)).isEqualTo(false);
        assertThat(eval("a == b", null, value)).isEqualTo(false);
        assertThat(eval("a == b", null, null)).isEqualTo(true);
        assertThat(eval("a != b", value, null)).isEqualTo(true);
        assertThat(eval("a != b", null, value)).isEqualTo(true);
        assertThat(eval("a != b", null, null)).isEqualTo(false);
    }

    @Property
    void RLE_006_in_is_false_and_not_in_is_true_for_a_null_target(@ForAll("comparable") Object element) {
        assertThat(eval("a in [b]", null, element)).isEqualTo(false);
        assertThat(eval("a not in [b]", null, element)).isEqualTo(true);
        assertThat(eval("a in [b, c]", null, element, null, null, null, null)).isEqualTo(false);
    }

    @Property
    void RLE_006_is_null_is_always_true_or_false(@ForAll("comparable") Object value) {
        assertThat(eval("a is null", value)).isEqualTo(false);
        assertThat(eval("a is not null", value)).isEqualTo(true);
        assertThat(eval("a is null", (Object) null)).isEqualTo(true);
        assertThat(eval("a is not null", (Object) null)).isEqualTo(false);
    }

    @Property
    void RLE_006_arithmetic_with_a_null_operand_is_null(
            @ForAll @LongRange(min = -1_000_000_000L, max = 1_000_000_000L) long minor) {
        Money money = Money.ofMinor("VND", minor);
        BigDecimal number = BigDecimal.valueOf(minor);

        assertThat(eval("income + b", null, null, money)).isNull();
        assertThat(eval("b - income", null, null, money)).isNull();
        assertThat(eval("txCount * b", null, null, null, null, number)).isNull();
        assertThat(eval("txCount / b", null, null, null, null, number)).isNull();
        assertThat(eval("-b")).isNull();
    }

    /** DESIGN 3.2: "between ... with a null operand give null", for the target and for either bound. */
    @Property
    void RLE_006_between_with_any_null_operand_is_null(
            @ForAll @LongRange(min = -1000, max = 1000) long x, @ForAll @LongRange(min = -1000, max = 1000) long bound) {
        BigDecimal v = BigDecimal.valueOf(x);
        BigDecimal other = BigDecimal.valueOf(bound);

        assertThat(eval("a between b and c", null, v, null, null, null, other)).isNull();
        assertThat(eval("a between b and c", v, null, null, null, null, other)).isNull();
        assertThat(eval("a between b and c", v, other, null, null, null, null)).isNull();
    }

    // ---------------------------------------------------------------- determinism

    /** Random well-formed expressions over the inputs; the generator is the grammar of Parser. */
    @Provide
    Arbitrary<String> expressions() {
        return expression(4);
    }

    Arbitrary<String> expression(int depth) {
        Arbitrary<String> atom = Arbitraries.oneOf(
                Arbitraries.of("income", "tier", "txCount", "a", "b", "null", "true", "false"),
                Arbitraries.longs().between(0, 99_999_999).map(n -> n + " VND"),
                Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("1000")).ofScale(2).map(BigDecimal::toPlainString),
                Arbitraries.of("'GOLD'", "'SILVER'", "''", "'Vàng'"));
        if (depth == 0) {
            return atom;
        }
        Arbitrary<String> sub = expression(depth - 1);
        return Arbitraries.oneOf(
                atom,
                Combinators.combine(sub, Arbitraries.of("and", "or", "==", "!=", "<", "<=", ">", ">=", "+", "-", "*", "/"), sub)
                        .as((l, op, r) -> "(" + l + ") " + op + " (" + r + ")"),
                sub.map(s -> "not(" + s + ")"),
                sub.map(s -> "-(" + s + ")"),
                sub.map(s -> "(" + s + ") is null"),
                Combinators.combine(sub, sub).as((l, r) -> "(" + l + ") in [" + r + ", 'GOLD']"),
                Combinators.combine(sub, sub).as((l, r) -> "(" + l + ") not in [" + r + "]"),
                Combinators.combine(sub, sub, sub).as((t, l, h) -> "(" + t + ") between (" + l + ") and (" + h + ")"));
    }

    @Provide
    Arbitrary<Object[]> slotValues() {
        return Combinators.combine(
                threeValued(), threeValued(),
                Arbitraries.longs().between(-1_000_000_000L, 1_000_000_000L).map(m -> (Object) Money.ofMinor("VND", m)).injectNull(0.2),
                Arbitraries.of("GOLD", "SILVER", "", "Vàng").map(Object.class::cast).injectNull(0.2),
                Arbitraries.longs().between(-100, 100).map(n -> (Object) BigDecimal.valueOf(n)).injectNull(0.2))
                .as((a, b, income, tier, tx) -> new Object[] {a, b, income, tier, tx, null});
    }

    @Property
    void RLE_001_parse_and_evaluate_are_deterministic(
            @ForAll("expressions") String source, @ForAll("slotValues") Object[] slots) {
        // Given the same text parsed twice
        Expr first = parse(source);
        Expr second = parse(source);

        // When both trees are evaluated over copies of the same inputs
        Object one = outcome(first, slots.clone());
        Object two = outcome(second, slots.clone());

        // Then the trees and the outcomes are equal, failures included
        assertThat(second).isEqualTo(first);
        assertThat(two).isEqualTo(one);
    }

    private static Object outcome(Expr expr, Object[] slots) {
        try {
            return Objects.requireNonNullElse(EVALUATOR.evaluate(expr, slots), "<null>");
        } catch (EvaluationException e) {
            return "EvaluationException: " + e.getMessage();
        }
    }

    // ---------------------------------------------------------------- parser total failure safety

    @Provide
    Arbitrary<String> garbage() {
        Arbitrary<String> fragments = Arbitraries.of("(", ")", "[", "]", ",", "'", "\\", "==", "!=", "<", ">=", "+", "-",
                "*", "/", "not", "in", "is", "null", "between", "and", "or", "income", "tier", "VND", "USD", "XXX",
                "1", "0.5", "99999999999999999999999", " ", "T(java.lang.Runtime)", "é", "٣", "😀", "\t");
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(80),
                fragments.list().ofMaxSize(40).map(parts -> String.join("", parts)),
                fragments.list().ofMaxSize(40).map(parts -> String.join(" ", parts)));
    }

    /** TR-SEC-01: any text gives a tree or a structured {@link ExpressionException}, never another error. */
    @Property(tries = 2000)
    void TR_SEC_01_parser_answers_any_text_with_a_tree_or_an_expression_exception(@ForAll("garbage") String source) {
        try {
            assertThat(parse(source)).isNotNull();
        } catch (ExpressionException expected) {
            assertThat(expected.getMessage()).isNotBlank();
        }
    }

    @Property(tries = 50)
    void TR_SEC_01_deep_nesting_is_refused_without_a_stack_overflow(
            @ForAll @net.jqwik.api.constraints.IntRange(min = 65, max = 20_000) int depth) {
        String parens = "(".repeat(depth) + "1" + ")".repeat(depth);
        String nots = "not ".repeat(depth) + "true";
        String negations = "-".repeat(depth) + "1";

        for (String source : new String[] {parens, nots, negations}) {
            try {
                parse(source);
                assertThat(source.length()).as("accepted only within MAX_LENGTH").isLessThanOrEqualTo(Parser.MAX_LENGTH);
            } catch (ExpressionException expected) {
                assertThat(expected.getMessage()).containsAnyOf("nested deeper", "longer than");
            }
        }
    }
}
