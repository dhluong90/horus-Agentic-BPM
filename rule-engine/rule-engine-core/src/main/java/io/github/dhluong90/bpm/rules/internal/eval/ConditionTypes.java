package io.github.dhluong90.bpm.rules.internal.eval;

import io.github.dhluong90.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.internal.expr.Expr;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.OutputClause;
import io.github.dhluong90.bpm.rules.internal.model.ValueType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * The type floor checked at import, so a mistyped cell fails closed at import instead of never matching or
 * failing at evaluation: a condition must produce a boolean, a comparison of an input with a literal must use the
 * input's declared type, and an output cell whose type is known statically must have the output's declared type
 * (a {@code money} output written as {@code "30000"} has no currency). Full type checking is RLE-004.
 *
 * <p>Each check returns a problem message without the rule id or column, or {@code null} when the cell passes;
 * the caller places the message on its cell.
 */
final class ConditionTypes {

    private enum Kind { STRING, NUMBER, BOOLEAN, MONEY, DATE, DATETIME, LIST, UNKNOWN, NULL }

    private static final String NOT_BOOLEAN = "the condition does not produce true or false";

    private ConditionTypes() {
    }

    /**
     * Checks that a condition produces true or false.
     *
     * @return the problem, or null
     */
    static String conditionProblem(Expr expr, List<InputClause> inputs) {
        return switch (expr) {
            case Expr.Literal literal -> literal.value() instanceof Boolean ? null : NOT_BOOLEAN;
            case Expr.Var var -> kindOf(var, inputs) == Kind.BOOLEAN ? null : NOT_BOOLEAN;
            case Expr.Unary unary -> unary.op() == Expr.UnaryOp.NOT ? conditionProblem(unary.operand(), inputs) : NOT_BOOLEAN;
            case Expr.Binary binary -> switch (binary.op()) {
                case AND, OR -> firstOf(conditionProblem(binary.left(), inputs), conditionProblem(binary.right(), inputs));
                case EQ, NE, LT, LE, GT, GE -> comparableProblem(binary.left(), binary.right(), inputs);
                case ADD, SUB, MUL, DIV -> NOT_BOOLEAN;
            };
            case Expr.InList inList -> {
                String problem = null;
                for (Expr element : inList.elements()) {
                    problem = firstOf(problem, comparableProblem(inList.target(), element, inputs));
                }
                yield problem;
            }
            case Expr.Between between -> firstOf(comparableProblem(between.target(), between.low(), inputs),
                    comparableProblem(between.target(), between.high(), inputs));
            case Expr.IsNull ignored -> null;
            case Expr.Matches matches -> {
                Kind text = kindOf(matches.text(), inputs);
                yield text == Kind.STRING || text == Kind.UNKNOWN || text == Kind.NULL ? null
                        : "matches() tests text, but it is given a " + name(text);
            }
        };
    }

    /**
     * Checks an output cell against the output's declared type, where the cell's type is known without inputs.
     * A null cell is allowed (the output has no value). List outputs belong to the collect hit policy and are not
     * checked here.
     *
     * @return the problem, or null
     */
    static String outputProblem(Expr expr, OutputClause output, List<InputClause> inputs) {
        ValueType type = output.type();
        if (type.list()) {
            return null;
        }
        Kind actual = kindOf(expr, inputs);
        if (actual == Kind.UNKNOWN || actual == Kind.NULL) {
            return null;
        }
        Kind expected = kindOf(type);
        if (expected == Kind.MONEY && actual == Kind.NUMBER) {
            return "output " + output.name() + " is money and needs a currency: write " + example(expr)
                    + " followed by its ISO 4217 currency code";
        }
        if (actual != expected) {
            return "output " + output.name() + " is " + typeName(type) + ", but the cell gives a " + name(actual);
        }
        if (type.kind() == ValueType.Kind.INTEGER && expr instanceof Expr.Literal literal
                && literal.value() instanceof BigDecimal number && !isIntegral(number)) {
            return "output " + output.name() + " is integer, but the cell gives " + number.toPlainString();
        }
        return null;
    }

    private static String comparableProblem(Expr left, Expr right, List<InputClause> inputs) {
        Kind a = kindOf(left, inputs);
        Kind b = kindOf(right, inputs);
        if (a == Kind.UNKNOWN || b == Kind.UNKNOWN || a == Kind.NULL || b == Kind.NULL || a == b) {
            return null;
        }
        return "compares a " + name(a) + " with a " + name(b);
    }

    private static Kind kindOf(Expr expr, List<InputClause> inputs) {
        return switch (expr) {
            case Expr.Literal literal -> switch (literal.value()) {
                case null -> Kind.NULL;
                case String ignored -> Kind.STRING;
                case BigDecimal ignored -> Kind.NUMBER;
                case Boolean ignored -> Kind.BOOLEAN;
                case Money ignored -> Kind.MONEY;
                case java.time.LocalDate ignored -> Kind.DATE;
                case java.time.Instant ignored -> Kind.DATETIME;
                default -> Kind.UNKNOWN;
            };
            case Expr.Var var -> {
                ValueType type = inputs.get(var.slot()).type();
                yield type.list() ? Kind.LIST : kindOf(type);
            }
            case Expr.Unary unary -> switch (unary.op()) {
                case NOT -> Kind.BOOLEAN;
                case NEGATE -> {
                    Kind operand = kindOf(unary.operand(), inputs);
                    yield operand == Kind.NUMBER || operand == Kind.MONEY ? operand : Kind.UNKNOWN;
                }
            };
            case Expr.Binary binary -> switch (binary.op()) {
                case OR, AND, EQ, NE, LT, LE, GT, GE -> Kind.BOOLEAN;
                case ADD, SUB -> {
                    Kind a = kindOf(binary.left(), inputs);
                    Kind b = kindOf(binary.right(), inputs);
                    yield a == b && (a == Kind.NUMBER || a == Kind.MONEY) ? a : Kind.UNKNOWN;
                }
                case MUL, DIV -> {
                    Kind a = kindOf(binary.left(), inputs);
                    Kind b = kindOf(binary.right(), inputs);
                    if (a == Kind.NUMBER && b == Kind.NUMBER) {
                        yield Kind.NUMBER;
                    }
                    yield a == Kind.MONEY && b == Kind.NUMBER ? Kind.MONEY : Kind.UNKNOWN;
                }
            };
            case Expr.InList ignored -> Kind.BOOLEAN;
            case Expr.Between ignored -> Kind.BOOLEAN;
            case Expr.IsNull ignored -> Kind.BOOLEAN;
            case Expr.Matches ignored -> Kind.BOOLEAN;
        };
    }

    private static Kind kindOf(ValueType type) {
        return switch (type.kind()) {
            case STRING -> Kind.STRING;
            case INTEGER, DECIMAL -> Kind.NUMBER;
            case BOOLEAN -> Kind.BOOLEAN;
            case MONEY -> Kind.MONEY;
            case DATE -> Kind.DATE;
            case DATETIME -> Kind.DATETIME;
        };
    }

    private static String example(Expr expr) {
        return expr instanceof Expr.Literal literal && literal.value() instanceof BigDecimal number
                ? number.toPlainString() : "the amount";
    }

    private static boolean isIntegral(BigDecimal number) {
        return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
    }

    private static String typeName(ValueType type) {
        return type.kind().name().toLowerCase(Locale.ROOT);
    }

    private static String name(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    private static String firstOf(String a, String b) {
        return a != null ? a : b;
    }
}
