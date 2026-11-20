package io.github.dhluong90.bpm.rules.internal.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.internal.EvaluationException;
import io.github.dhluong90.bpm.expr.internal.Evaluator;
import io.github.dhluong90.bpm.expr.internal.ExpressionError;
import io.github.dhluong90.bpm.expr.internal.ExpressionException;
import io.github.dhluong90.bpm.expr.internal.Parser;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.ValueType;
import io.github.dhluong90.bpm.testing.property.Gen;
import io.github.dhluong90.bpm.testing.property.Property;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * RLE-004, differential: the static type check agrees with the evaluator on generated expressions over a fee table's
 * inputs ({@code txCount} number, {@code balance} money in VND, {@code tier} string, {@code vip} boolean). A
 * generator that knows the type of what it builds is the oracle: every well-typed expression passes the check and
 * evaluates to a value of that type; every operator applied to operands the language does not define for it is
 * refused as {@code TYPE_MISMATCH} at the operator, and the evaluator could only have given null or failed for it.
 * Seeded with the project's generator (PD-090), so every build draws the same expressions.
 */
class ExpressionTypeCheckPropertiesTest {

    enum Kind { NUMBER, MONEY, STRING, BOOLEAN }

    /** An expression text and the type the generator built it to have. */
    record Typed(String text, Kind kind) {
    }

    static final List<InputClause> INPUTS = List.of(
            new InputClause("txCount", ValueType.parse("integer"), false),
            new InputClause("balance", ValueType.parse("money"), false),
            new InputClause("tier", ValueType.parse("string"), false),
            new InputClause("vip", ValueType.parse("boolean"), false));

    static final Map<String, Integer> SLOTS = Map.of("txCount", 0, "balance", 1, "tier", 2, "vip", 3);

    /** Non-null inputs, so a null result can only come from the operation itself. */
    static final Object[] FRAME = {new BigDecimal("12"), Money.ofMinor("VND", 2_500_000), "GOLD", Boolean.TRUE};

    static final Evaluator EVALUATOR = new Evaluator(RoundingMode.HALF_EVEN);

    static Typed build(RandomGenerator random, Kind kind, int depth) {
        boolean leaf = depth <= 0 || random.nextInt(3) == 0;
        return switch (kind) {
            case NUMBER -> leaf
                    ? pick(random, new Typed("txCount", kind), new Typed(random.nextInt(1, 500) + "", kind),
                            new Typed(random.nextInt(0, 99) + ".5", kind))
                    : switch (random.nextInt(5)) {
                        case 0 -> join(build(random, Kind.NUMBER, depth - 1), pick(random, "+", "-", "*"),
                                build(random, Kind.NUMBER, depth - 1), kind);
                        case 1 -> new Typed("(" + build(random, Kind.NUMBER, depth - 1).text() + ") / "
                                + random.nextInt(1, 9), kind);
                        case 2 -> new Typed("round(" + build(random, Kind.NUMBER, depth - 1).text() + ", 2)", kind);
                        case 3 -> new Typed("-(" + build(random, Kind.NUMBER, depth - 1).text() + ")", kind);
                        default -> new Typed("daysBetween(date('2026-12-01'), date('2027-01-15'))", kind);
                    };
            case MONEY -> leaf
                    ? pick(random, new Typed("balance", kind), new Typed(random.nextInt(0, 90_000) + " VND", kind))
                    : switch (random.nextInt(4)) {
                        case 0 -> join(build(random, Kind.MONEY, depth - 1), pick(random, "+", "-"),
                                build(random, Kind.MONEY, depth - 1), kind);
                        case 1 -> new Typed("(" + build(random, Kind.MONEY, depth - 1).text() + ") * 0.015", kind);
                        case 2 -> new Typed("2 * (" + build(random, Kind.MONEY, depth - 1).text() + ")", kind);
                        default -> new Typed("(" + build(random, Kind.MONEY, depth - 1).text() + ") / 4", kind);
                    };
            case STRING -> leaf
                    ? pick(random, new Typed("tier", kind), new Typed("'SILVER'", kind))
                    : join(build(random, Kind.STRING, depth - 1), "+", build(random, Kind.STRING, depth - 1), kind);
            case BOOLEAN -> leaf
                    ? pick(random, new Typed("vip", kind), new Typed("true", kind), new Typed("tier is null", kind))
                    : switch (random.nextInt(6)) {
                        case 0 -> join(build(random, Kind.BOOLEAN, depth - 1), pick(random, "and", "or"),
                                build(random, Kind.BOOLEAN, depth - 1), kind);
                        case 1 -> new Typed("not(" + build(random, Kind.BOOLEAN, depth - 1).text() + ")", kind);
                        case 2 -> {
                            Kind ordered = pick(random, Kind.NUMBER, Kind.MONEY, Kind.STRING);
                            yield join(build(random, ordered, depth - 1), pick(random, "<", "<=", ">", ">=", "==", "!="),
                                    build(random, ordered, depth - 1), kind);
                        }
                        case 3 -> new Typed("(" + build(random, Kind.NUMBER, depth - 1).text() + ") between 1 and ("
                                + build(random, Kind.NUMBER, depth - 1).text() + ")", kind);
                        case 4 -> new Typed("(" + build(random, Kind.STRING, depth - 1).text()
                                + ") in ['GOLD', tier]", kind);
                        default -> join(build(random, Kind.BOOLEAN, depth - 1), "==",
                                build(random, Kind.BOOLEAN, depth - 1), kind);
                    };
        };
    }

    static Typed join(Typed left, String op, Typed right, Kind kind) {
        return new Typed("(" + left.text() + ") " + op + " (" + right.text() + ")", kind);
    }

    @SafeVarargs
    static <T> T pick(RandomGenerator random, T... choices) {
        return choices[random.nextInt(choices.length)];
    }

    static final Gen<Typed> WELL_TYPED = Gen.from(random -> build(random, pick(random, Kind.values()), 4));

    /** Two operands and an operator the language does not define for their types. */
    record IllTyped(Typed left, String op, Typed right) {
        String text() {
            return "(" + left.text() + ") " + op + " (" + right.text() + ")";
        }

        int operatorPosition() {
            return left.text().length() + 3;
        }
    }

    static boolean defined(String op, Kind a, Kind b) {
        return switch (op) {
            case "+" -> a == b && a != Kind.BOOLEAN;
            case "-" -> a == b && (a == Kind.NUMBER || a == Kind.MONEY);
            case "*" -> (a == Kind.NUMBER && (b == Kind.NUMBER || b == Kind.MONEY)) || (a == Kind.MONEY && b == Kind.NUMBER);
            case "/" -> b == Kind.NUMBER && (a == Kind.NUMBER || a == Kind.MONEY);
            case "<", ">" -> a == b && a != Kind.BOOLEAN;
            default -> a == b;   // == and !=
        };
    }

    static final Gen<IllTyped> ILL_TYPED = Gen.from(random -> {
        while (true) {
            Kind a = pick(random, Kind.values());
            Kind b = pick(random, Kind.values());
            String op = pick(random, "+", "-", "*", "/", "<", ">", "==", "!=");
            if (!defined(op, a, b)) {
                return new IllTyped(build(random, a, 2), op, build(random, b, 2));
            }
        }
    });

    static Parser.Located parse(String text) {
        return Parser.parseLocated(text, name -> SLOTS.getOrDefault(name, -1), null);
    }

    @TestFactory
    Stream<DynamicTest> RLE_004_well_typed_expressions_pass_the_check_and_evaluate_to_their_type() {
        return Property.tries(1_000).forAll(WELL_TYPED, typed -> {
            Parser.Located located = parse(typed.text());
            assertThat(ConditionTypes.operandProblem(located.expr(), INPUTS)).as(typed.text()).isNull();

            Object value;
            try {
                value = EVALUATOR.evaluate(located.expr(), FRAME);
            } catch (EvaluationException e) {
                // a money amount out of range is a deterministic failure of the values, not of the types
                assertThat(e.getMessage()).contains("out of range");
                return;
            }
            Class<?> expected = switch (typed.kind()) {
                case NUMBER -> BigDecimal.class;
                case MONEY -> Money.class;
                case STRING -> String.class;
                case BOOLEAN -> Boolean.class;
            };
            assertThat(value).as(typed.text()).isInstanceOf(expected);
        });
    }

    @TestFactory
    Stream<DynamicTest> RLE_004_undefined_operations_are_refused_at_the_operator() {
        return Property.tries(1_000).forAll(ILL_TYPED, ill -> {
            Parser.Located located = parse(ill.text());
            ConditionTypes.TypeProblem problem = ConditionTypes.operandProblem(located.expr(), INPUTS);

            assertThat(problem).as(ill.text()).isNotNull();
            assertThat(located.position(problem.node())).as(ill.text()).isEqualTo(ill.operatorPosition());
            // The evaluator agrees there is no value: FEEL null for mixed types, or a deterministic failure.
            Throwable failure = catchThrowable(() -> assertThat(EVALUATOR.evaluate(located.expr(), FRAME)).isNull());
            if (failure != null) {
                assertThat(failure).as(ill.text()).isInstanceOf(EvaluationException.class);
            }
        });
    }

    /** Code-like text: member access, calls of names the language does not have, statements. */
    static final Gen<String> CODE_LIKE = Gen.from(random -> {
        String name = pick(random, "T", "exec", "getRuntime", "eval", "Class", "forName", "load", "x");
        String target = pick(random, "java.lang.Runtime", "System", "tier", "txCount", "'rm -rf /'");
        return switch (random.nextInt(6)) {
            case 0 -> name + "(" + target + ")";
            case 1 -> "T(" + target + ")." + name + "()";
            case 2 -> "tier." + name + "()";
            case 3 -> "txCount > 1; " + name + "(" + target + ")";
            case 4 -> "{ " + name + " }";
            default -> pick(random, "new ", "class ", "import ") + target;
        };
    });

    @TestFactory
    Stream<DynamicTest> TR_SEC_01_code_like_text_is_a_forbidden_construct_and_binds_nothing() {
        return Property.tries(500).forAll(CODE_LIKE, source -> {
            List<String> looked = new ArrayList<>();
            assertThatThrownBy(() -> Parser.parse(source, name -> {
                looked.add(name);
                return SLOTS.getOrDefault(name, -1);
            })).as(source).isInstanceOfSatisfying(ExpressionException.class,
                    e -> assertThat(e.error()).isEqualTo(ExpressionError.FORBIDDEN_CONSTRUCT));
            // Nothing inside or after the forbidden construct is looked up as an input: the refusal comes first. Only
            // a leading word such as "new" is looked up, to tell an input of that name from code.
            assertThat(looked).as(source).allMatch(name -> SLOTS.containsKey(name) || List.of("new", "class", "import")
                    .contains(name));
        });
    }
}
