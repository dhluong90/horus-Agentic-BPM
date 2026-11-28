package io.github.dhluong90.bpm.rules.internal.eval;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.internal.Expr;
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
 * (a {@code money} output written as {@code "30000"} has no currency). {@link #operandProblem} checks the operands of
 * every operator in the tree, in conditions and in output cells computed from inputs (RLE-004).
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
     * A type error at one node of a tree.
     *
     * @param message the problem, without the rule id, column or position
     * @param node the operator or call at fault
     */
    record TypeProblem(String message, Expr node) {
    }

    /**
     * The declared inputs and what is known of the tree being checked: the kind and the currency of each node, kept by
     * identity and filled bottom-up on first use, so a node's type is worked out once however many operators above it
     * ask, and the check costs time in proportion to the size of the cell (RLE-004, #359). One scope belongs to one
     * cell and one thread.
     */
    static final class Scope {
        private final List<InputClause> inputs;
        private final java.util.IdentityHashMap<Expr, Kind> kinds = new java.util.IdentityHashMap<>();
        private final java.util.IdentityHashMap<Expr, String> currencies = new java.util.IdentityHashMap<>();
        private int kindEvaluations;

        /**
         * Creates the scope of one cell.
         *
         * @param inputs the declared inputs, by slot
         */
        Scope(List<InputClause> inputs) {
            this.inputs = inputs;
        }

        InputClause get(int slot) {
            return inputs.get(slot);
        }

        /**
         * Counts the nodes whose kind was worked out, for the test of RLE-004 that the check is linear.
         *
         * @return the number of kind evaluations so far
         */
        int kindEvaluations() {
            return kindEvaluations;
        }
    }

    /**
     * RLE-004: as {@link #operandProblem(Expr, Scope)} with a scope of its own.
     *
     * @param expr the tree
     * @param inputs the declared inputs, by slot
     * @return the first problem in reading order, or null
     */
    static TypeProblem operandProblem(Expr expr, List<InputClause> inputs) {
        return operandProblem(expr, new Scope(inputs));
    }

    /**
     * Checks that a condition produces true or false, with a scope of its own.
     *
     * @return the problem, or null
     */
    static String conditionProblem(Expr expr, List<InputClause> inputs) {
        return conditionProblem(expr, new Scope(inputs));
    }

    /**
     * Checks an output cell against the output's declared type, with a scope of its own.
     *
     * @return the problem, or null
     */
    static String outputProblem(Expr expr, OutputClause output, List<InputClause> inputs) {
        return outputProblem(expr, output, new Scope(inputs));
    }

    /**
     * RLE-004: checks the operands of every operator, function and {@code matches} in the tree, innermost first,
     * so {@code balance + 100 > 0} with a money {@code balance} names the {@code +}. An operand whose type is not
     * known statically ({@code null}) passes; at run time it gives null, as in FEEL.
     *
     * @param expr the tree
     * @param inputs the declared inputs, by slot
     * @return the first problem in reading order, or null
     */
    static TypeProblem operandProblem(Expr expr, Scope inputs) {
        for (Expr child : children(expr)) {
            TypeProblem problem = operandProblem(child, inputs);
            if (problem != null) {
                return problem;
            }
        }
        String own = switch (expr) {
            case Expr.Unary unary -> {
                Kind operand = kindOf(unary.operand(), inputs);
                if (!known(operand)) {
                    yield null;
                }
                yield switch (unary.op()) {
                    case NOT -> operand == Kind.BOOLEAN ? null : "not() takes true or false, not a " + name(operand);
                    case NEGATE -> operand == Kind.NUMBER || operand == Kind.MONEY ? null
                            : "- negates a number or money, not a " + name(operand);
                };
            }
            case Expr.Binary binary -> binaryProblem(binary, inputs);
            case Expr.InList inList -> {
                String problem = null;
                for (Expr element : inList.elements()) {
                    problem = firstOf(problem, comparableProblem(inList.target(), element, inputs));
                }
                yield problem;
            }
            case Expr.Between between -> firstOf(orderedProblem(between.target(), between.low(), inputs),
                    orderedProblem(between.target(), between.high(), inputs));
            case Expr.Matches matches -> {
                Kind text = kindOf(matches.target(), inputs);
                yield !known(text) || text == Kind.STRING ? null : "matches() tests text, but it is given a " + name(text);
            }
            case Expr.HostCall call -> hostArgumentProblem(call, inputs);
            default -> null;
        };
        return own == null ? null : new TypeProblem(own, expr);
    }

    /** RLE-056: each argument of a host function call has the type of its parameter; an unknown type passes. */
    private static String hostArgumentProblem(Expr.HostCall call, Scope inputs) {
        List<io.github.dhluong90.bpm.expr.spi.ValueType> parameters = call.function().parameterTypes();
        for (int i = 0; i < parameters.size(); i++) {
            Kind expected = kindOf(parameters.get(i));
            Kind actual = kindOf(call.args().get(i), inputs);
            if (known(actual) && actual != expected) {
                return call.function().name() + ": parameter " + (i + 1) + " is a " + name(expected)
                        + ", but the argument is a " + name(actual);
            }
        }
        return null;
    }

    private static Kind kindOf(io.github.dhluong90.bpm.expr.spi.ValueType type) {
        return switch (type) {
            case STRING -> Kind.STRING;
            case BOOLEAN -> Kind.BOOLEAN;
            case NUMBER -> Kind.NUMBER;
            case DATE -> Kind.DATE;
            case DATETIME -> Kind.DATETIME;
            case MONEY -> Kind.MONEY;
            case LIST -> Kind.LIST;
            case ANY -> Kind.UNKNOWN;
        };
    }

    private static String binaryProblem(Expr.Binary binary, Scope inputs) {
        Kind a = kindOf(binary.left(), inputs);
        Kind b = kindOf(binary.right(), inputs);
        return switch (binary.op()) {
            case AND, OR -> {
                Kind wrong = known(a) && a != Kind.BOOLEAN ? a : known(b) && b != Kind.BOOLEAN ? b : null;
                yield wrong == null ? null : binary.op().name().toLowerCase(Locale.ROOT)
                        + " joins true or false, not a " + name(wrong);
            }
            case EQ, NE -> comparableProblem(binary.left(), binary.right(), inputs);
            case LT, LE, GT, GE -> orderedProblem(binary.left(), binary.right(), inputs);
            case ADD, SUB, MUL, DIV -> !known(a) || !known(b) || arithmeticKind(binary.op(), a, b) != null ? null
                    : arithmeticMessage(binary, a, b, inputs);
        };
    }

    /** The result of arithmetic on two known kinds, or null when the language does not define it. */
    private static Kind arithmeticKind(Expr.BinaryOp op, Kind a, Kind b) {
        return switch (op) {
            case ADD -> a == b && (a == Kind.NUMBER || a == Kind.MONEY || a == Kind.STRING) ? a : null;
            case SUB -> a == b && (a == Kind.NUMBER || a == Kind.MONEY) ? a : null;
            case MUL -> a == Kind.NUMBER && b == Kind.NUMBER ? Kind.NUMBER
                    : (a == Kind.MONEY && b == Kind.NUMBER) || (a == Kind.NUMBER && b == Kind.MONEY) ? Kind.MONEY : null;
            case DIV -> a == Kind.NUMBER && b == Kind.NUMBER ? Kind.NUMBER
                    : a == Kind.MONEY && b == Kind.NUMBER ? Kind.MONEY : null;
            default -> null;
        };
    }

    private static String arithmeticMessage(Expr.Binary binary, Kind a, Kind b, Scope scope) {
        String symbol = switch (binary.op()) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            default -> "/";
        };
        String problem = symbol + " is not defined for " + name(a) + " and " + name(b);
        boolean addOrSubtract = binary.op() == Expr.BinaryOp.ADD || binary.op() == Expr.BinaryOp.SUB;
        if (addOrSubtract && (a == Kind.MONEY && b == Kind.NUMBER || a == Kind.NUMBER && b == Kind.MONEY)) {
            Expr number = a == Kind.NUMBER ? binary.left() : binary.right();
            Expr money = a == Kind.MONEY ? binary.left() : binary.right();
            String currency = currencyOf(money, scope);
            return problem + ": write the number with a currency, for example " + example(number) + " "
                    + (currency == null ? "VND" : currency);
        }
        if (a == Kind.MONEY && b == Kind.MONEY) {
            return problem + ": money is multiplied or divided by a number";
        }
        if (binary.op() == Expr.BinaryOp.DIV && b == Kind.MONEY) {
            return problem + ": a number cannot be divided by money";
        }
        return problem;
    }

    /** {@code <}, {@code >} and {@code between}: the same type, and one that has an order. */
    private static String orderedProblem(Expr left, Expr right, Scope inputs) {
        String problem = comparableProblem(left, right, inputs);
        if (problem != null) {
            return problem;
        }
        Kind a = kindOf(left, inputs);
        return a == Kind.BOOLEAN || a == Kind.LIST
                ? "orders a " + name(a) + ": < and > compare numbers, money, text, dates and datetimes" : null;
    }

    private static boolean known(Kind kind) {
        return kind != Kind.UNKNOWN && kind != Kind.NULL;
    }

    /**
     * Checks that a condition produces true or false.
     *
     * @return the problem, or null
     */
    static String conditionProblem(Expr expr, Scope inputs) {
        String problem = firstOf(argumentProblem(expr, inputs), currencyProblem(expr, inputs));
        return problem != null ? problem : booleanProblem(expr, inputs);
    }

    private static String booleanProblem(Expr expr, Scope inputs) {
        return switch (expr) {
            case Expr.Literal literal -> literal.value() instanceof Boolean ? null : NOT_BOOLEAN;
            case Expr.Var var -> kindOf(var, inputs) == Kind.BOOLEAN ? null : NOT_BOOLEAN;
            case Expr.Clock ignored -> NOT_BOOLEAN;
            case Expr.Unary unary -> unary.op() == Expr.UnaryOp.NOT ? booleanProblem(unary.operand(), inputs) : NOT_BOOLEAN;
            case Expr.Binary binary -> switch (binary.op()) {
                case AND, OR -> firstOf(booleanProblem(binary.left(), inputs), booleanProblem(binary.right(), inputs));
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
            case Expr.Call ignored -> NOT_BOOLEAN;
            case Expr.HostCall call -> call.function().resultType() == io.github.dhluong90.bpm.expr.spi.ValueType.BOOLEAN
                    ? null : NOT_BOOLEAN;
            case Expr.Matches matches -> {
                Kind text = kindOf(matches.target(), inputs);
                yield text == Kind.STRING || text == Kind.UNKNOWN || text == Kind.NULL ? null
                        : "matches() tests text, but it is given a " + name(text);
            }
        };
    }

    /**
     * Checks the argument types of every function call in the tree: {@code round} takes numbers and
     * {@code daysBetween} dates. An argument whose type is not known statically passes.
     */
    private static String argumentProblem(Expr expr, Scope inputs) {
        String own = switch (expr) {
            case Expr.Call call -> {
                Kind expected = call.function() == Expr.Function.ROUND ? Kind.NUMBER : Kind.DATE;
                String problem = null;
                for (Expr arg : call.args()) {
                    problem = firstOf(problem, argumentKindProblem(call.function().text(), arg, expected, inputs));
                }
                yield problem;
            }
            default -> null;
        };
        if (own != null) {
            return own;
        }
        for (Expr child : children(expr)) {
            String problem = argumentProblem(child, inputs);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String argumentKindProblem(String function, Expr arg, Kind expected, Scope inputs) {
        Kind actual = kindOf(arg, inputs);
        if (actual == expected || actual == Kind.UNKNOWN || actual == Kind.NULL) {
            return null;
        }
        return function + " takes a " + name(expected) + ", not a " + name(actual);
    }

    /**
     * RLE-005: two money operands of a comparison, {@code +} or {@code -} whose currencies are both known statically
     * (from literals) must have the same currency; at run time a mismatch is {@code EVALUATION_ERROR} anyway.
     */
    private static String currencyProblem(Expr expr, Scope scope) {
        String own = switch (expr) {
            case Expr.Binary binary -> switch (binary.op()) {
                case EQ, NE, LT, LE, GT, GE, ADD, SUB ->
                        mismatch(currencyOf(binary.left(), scope), currencyOf(binary.right(), scope));
                default -> null;
            };
            case Expr.InList inList -> {
                String known = currencyOf(inList.target(), scope);
                String problem = null;
                for (Expr element : inList.elements()) {
                    String currency = currencyOf(element, scope);
                    problem = firstOf(problem, mismatch(known, currency));
                    known = known != null ? known : currency;
                }
                yield problem;
            }
            case Expr.Between between -> firstOf(
                    mismatch(currencyOf(between.target(), scope), currencyOf(between.low(), scope)),
                    firstOf(mismatch(currencyOf(between.target(), scope), currencyOf(between.high(), scope)),
                            mismatch(currencyOf(between.low(), scope), currencyOf(between.high(), scope))));
            default -> null;
        };
        if (own != null) {
            return own;
        }
        for (Expr child : children(expr)) {
            String problem = currencyProblem(child, scope);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String mismatch(String a, String b) {
        return a != null && b != null && !a.equals(b) ? "mixes the currencies " + a + " and " + b : null;
    }

    /** The currency of a money expression when a literal fixes it, else null. */
    private static String currencyOf(Expr expr, Scope scope) {
        if (scope.currencies.containsKey(expr)) {
            return scope.currencies.get(expr);
        }
        String currency = computeCurrency(expr, scope);
        scope.currencies.put(expr, currency);
        return currency;
    }

    private static String computeCurrency(Expr expr, Scope scope) {
        return switch (expr) {
            case Expr.Literal literal -> literal.value() instanceof Money money ? money.currency() : null;
            case Expr.Unary unary -> unary.op() == Expr.UnaryOp.NEGATE ? currencyOf(unary.operand(), scope) : null;
            case Expr.Binary binary -> switch (binary.op()) {
                case ADD, SUB, MUL, DIV -> {
                    String left = currencyOf(binary.left(), scope);
                    yield left != null ? left : currencyOf(binary.right(), scope);
                }
                default -> null;
            };
            default -> null;
        };
    }

    /** The direct subexpressions of a node. */
    static List<Expr> childrenOf(Expr expr) {
        return children(expr);
    }

    private static List<Expr> children(Expr expr) {
        return switch (expr) {
            case Expr.Literal ignored -> List.of();
            case Expr.Var ignored -> List.of();
            case Expr.Unary unary -> List.of(unary.operand());
            case Expr.Binary binary -> List.of(binary.left(), binary.right());
            case Expr.InList inList -> {
                List<Expr> all = new java.util.ArrayList<>(inList.elements());
                all.addFirst(inList.target());
                yield all;
            }
            case Expr.Between between -> List.of(between.target(), between.low(), between.high());
            case Expr.IsNull isNull -> List.of(isNull.target());
            case Expr.Call call -> call.args();
            case Expr.HostCall call -> call.args();
            case Expr.Clock ignored -> List.of();
            case Expr.Matches matches -> List.of(matches.target());
        };
    }

    /**
     * Checks an output cell against the output's declared type, where the cell's type is known without inputs.
     * A null cell is allowed (the output has no value). List outputs belong to the collect hit policy and are not
     * checked here, nor are the cells of a {@code count}, which counts rules and never reads their values.
     *
     * @return the problem, or null
     */
    static String outputProblem(Expr expr, OutputClause output, Scope inputs) {
        String problem = firstOf(argumentProblem(expr, inputs), currencyProblem(expr, inputs));
        if (problem != null) {
            return "output " + output.name() + " " + problem;
        }
        ValueType type = output.type();
        if (type.list() || output.aggregate() == OutputClause.Aggregate.COUNT) {
            // count counts matched rules whatever their cells hold, as at evaluation
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

    private static String comparableProblem(Expr left, Expr right, Scope inputs) {
        Kind a = kindOf(left, inputs);
        Kind b = kindOf(right, inputs);
        if (a == Kind.UNKNOWN || b == Kind.UNKNOWN || a == Kind.NULL || b == Kind.NULL || a == b) {
            return null;
        }
        return "compares a " + name(a) + " with a " + name(b);
    }

    /** The kind of a node, worked out once per node of the tree being checked (RLE-004, #359). */
    private static Kind kindOf(Expr expr, Scope inputs) {
        Kind known = inputs.kinds.get(expr);
        if (known != null) {
            return known;
        }
        Kind computed = computeKind(expr, inputs);
        inputs.kinds.put(expr, computed);
        inputs.kindEvaluations++;
        return computed;
    }

    private static Kind computeKind(Expr expr, Scope inputs) {
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
            case Expr.Clock clock -> clock.function() == Expr.ClockFunction.TODAY ? Kind.DATE : Kind.DATETIME;
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
                case ADD, SUB, MUL, DIV -> {
                    Kind result = arithmeticKind(binary.op(), kindOf(binary.left(), inputs), kindOf(binary.right(), inputs));
                    yield result == null ? Kind.UNKNOWN : result;
                }
            };
            case Expr.InList ignored -> Kind.BOOLEAN;
            case Expr.Between ignored -> Kind.BOOLEAN;
            case Expr.IsNull ignored -> Kind.BOOLEAN;
            case Expr.Call ignored -> Kind.NUMBER;
            case Expr.HostCall call -> kindOf(call.function().resultType());
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
