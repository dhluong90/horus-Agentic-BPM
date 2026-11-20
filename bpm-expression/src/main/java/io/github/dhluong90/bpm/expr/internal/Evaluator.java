package io.github.dhluong90.bpm.expr.internal;

import io.github.dhluong90.bpm.expr.api.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Evaluates an {@link Expr} over input slots with FEEL semantics (rule-engine/docs/DESIGN.md section
 * 3.2, PD-058): numbers are {@code BigDecimal} in {@link MathContext#DECIMAL128}; comparisons
 * and arithmetic with a null operand give null; {@code between} is {@code x >= a and x <= b}, as
 * DMN 1.5; {@code ==} and {@code !=} are two-valued; {@code in} is true when an element equals the
 * target, so a null target is in a list only when the list holds null; {@code and}, {@code or} and
 * {@code not} follow the FEEL truth tables; a function with a null argument gives null; {@code round}
 * is half-even. Mixed currencies and division by zero raise {@link EvaluationException}, as does
 * a {@code matches} past one of its bounds ({@link BoundedRegex}).
 * An evaluator made by a constructor is stateless and thread-safe, and gives each {@code matches} a decision budget
 * of its own. An evaluator made by {@link #forDecision()} charges every {@code matches} it runs to one budget of
 * {@code evaluation.regex-max-reads-per-decision} and belongs to the thread evaluating that decision; it also charges
 * every node it visits to the decision's step budget ({@link EvaluationLimits}, {@code EVALUATION_BUDGET_EXCEEDED}).
 */
public final class Evaluator {

    /**
     * Default longest string {@code +} may build, in UTF-16 code units; the Rule Engine's
     * {@code evaluation.string-max-length} default.
     */
    public static final int STRING_MAX_LENGTH = 65_536;

    private final RoundingMode moneyRounding;
    private final int maxStringLength;
    private final RegexLimits regexLimits;
    private final EvaluationLimits evaluationLimits;
    /** The read budget of the decision this evaluator is scoped to, or null for a shared evaluator. */
    private final DecisionReads decision;
    /** The step budget of the decision this evaluator is scoped to, or null for a shared evaluator. */
    private final EvaluationLimits.DecisionSteps steps;
    /**
     * Whether values of the wrong type for an operator fail instead of giving null. Off for rule sets, whose types are
     * checked at publication; on for names typed only at evaluation, such as process variables (ADR-0027).
     */
    private final boolean strict;

    /**
     * Creates an evaluator with the default string bound.
     *
     * @param moneyRounding rounding of money multiplication and division
     */
    public Evaluator(RoundingMode moneyRounding) {
        this(moneyRounding, STRING_MAX_LENGTH);
    }

    /**
     * Creates an evaluator.
     *
     * @param moneyRounding rounding of money multiplication and division
     * @param maxStringLength the longest string {@code +} may build; longer is {@link EvaluationException}
     */
    public Evaluator(RoundingMode moneyRounding, int maxStringLength) {
        this(moneyRounding, maxStringLength, RegexLimits.defaults());
    }

    /**
     * Creates an evaluator with regular expression bounds.
     *
     * @param moneyRounding rounding of money multiplication and division
     * @param maxStringLength the longest string {@code +} may build; longer is {@link EvaluationException}
     * @param regexLimits the length and time bounds of {@code matches}
     */
    public Evaluator(RoundingMode moneyRounding, int maxStringLength, RegexLimits regexLimits) {
        this(moneyRounding, maxStringLength, regexLimits, EvaluationLimits.defaults());
    }

    /**
     * Creates an evaluator with regular expression bounds and the step budget of a decision.
     *
     * @param moneyRounding rounding of money multiplication and division
     * @param maxStringLength the longest string {@code +} may build; longer is {@link EvaluationException}
     * @param regexLimits the length and time bounds of {@code matches}
     * @param evaluationLimits the step budget and time backstop of one decision, applied by {@link #forDecision()}
     */
    public Evaluator(RoundingMode moneyRounding, int maxStringLength, RegexLimits regexLimits,
            EvaluationLimits evaluationLimits) {
        this.moneyRounding = moneyRounding;
        this.maxStringLength = maxStringLength;
        this.regexLimits = java.util.Objects.requireNonNull(regexLimits, "regexLimits must not be null");
        this.evaluationLimits = java.util.Objects.requireNonNull(evaluationLimits, "evaluationLimits must not be null");
        this.decision = null;
        this.steps = null;
        this.strict = false;
    }

    private Evaluator(Evaluator shared, DecisionReads decision, EvaluationLimits.DecisionSteps steps, boolean strict) {
        this.moneyRounding = shared.moneyRounding;
        this.maxStringLength = shared.maxStringLength;
        this.regexLimits = shared.regexLimits;
        this.evaluationLimits = shared.evaluationLimits;
        this.decision = decision;
        this.steps = steps;
        this.strict = strict;
    }

    /**
     * Returns an evaluator for names typed only at evaluation: an operator given non-null values of types it does not
     * accept ({@code 'GOLD' == 5}, {@code fee > 1} with money {@code fee}, {@code 'x' and true}) fails with
     * {@link EvaluationException} instead of giving null, so a condition never silently does not hold.
     *
     * @return the strict evaluator
     */
    public Evaluator strict() {
        return new Evaluator(this, decision, steps, true);
    }

    /**
     * Returns an evaluator for one decision: every {@code matches} it runs reads from one budget of
     * {@code evaluation.regex-max-reads-per-decision} characters (RLE-007), and every node it visits is charged to
     * one step budget ({@link EvaluationLimits}). Use it on one thread, for one decision.
     *
     * @return an evaluator scoped to a fresh decision budget
     */
    public Evaluator forDecision() {
        return new Evaluator(this, regexLimits.newDecision(), evaluationLimits.newDecision(), strict);
    }


    /**
     * Returns an evaluator for one decision of a request of many decisions: every {@code matches} it runs reads from
     * one budget of {@code evaluation.regex-max-reads-per-decision} characters and also from the request's budget of
     * {@code evaluation.regex-max-reads-per-request} (RLE-007). Use it on one thread, for one decision.
     *
     * @param request the request's read budget, or null for a decision on its own
     * @return an evaluator scoped to a fresh decision budget that charges the request
     */
    public Evaluator forDecision(RequestReads request) {
        return new Evaluator(this, regexLimits.newDecision(request), evaluationLimits.newDecision());
    }

    /**
     * Evaluates an expression.
     *
     * @param expr the tree
     * @param slots input values by slot index
     * @return the value, possibly null
     * @throws EvaluationException on a deterministic failure
     */
    public Object evaluate(Expr expr, Object[] slots) {
        if (steps != null) {
            steps.charge();
        }
        return switch (expr) {
            case Expr.Literal literal -> literal.value();
            case Expr.Var var -> slots[var.slot()];
            case Expr.Clock clock -> clockValue(clock, slots);
            case Expr.Unary unary -> {
                Object operand = evaluate(unary.operand(), slots);
                yield typed(unary(unary.op(), operand), operand, operand, unary.op().name().toLowerCase(java.util.Locale.ROOT));
            }
            case Expr.Binary binary -> binary(binary, slots);
            case Expr.InList inList -> {
                boolean found = in(evaluate(inList.target(), slots), inList, slots);
                yield inList.negated() != found;
            }
            case Expr.Between between -> {
                // DMN 1.5: x between a and b is x >= a and x <= b, evaluated as and is: false and anything is false
                Object value = evaluate(between.target(), slots);
                Boolean low = compare(Expr.BinaryOp.GE, value, evaluate(between.low(), slots));
                if (Boolean.FALSE.equals(low)) {
                    yield false;
                }
                yield and(low, compare(Expr.BinaryOp.LE, value, evaluate(between.high(), slots)));
            }
            case Expr.IsNull isNull -> (evaluate(isNull.target(), slots) == null) != isNull.negated();
            case Expr.Call call -> call(call, slots);
            case Expr.Matches matches -> {
                // FEEL: a null or non-string text gives null, which never matches.
                Object text = evaluate(matches.target(), slots);
                yield text instanceof String string ? BoundedRegex.find(matches.compiled(), string, regexLimits,
                        decision == null ? regexLimits.newDecision() : decision) : null;
            }
        };
    }

    /**
     * Reads {@code today()} or {@code now()} from its frame slot. A tree parsed without a clock, or a frame bound
     * without one, fails deterministically instead of reading the system time or another slot.
     */
    private static Object clockValue(Expr.Clock clock, Object[] slots) {
        if (clock.slot() < 0 || clock.slot() >= slots.length || slots[clock.slot()] == null) {
            throw new EvaluationException(clock.function().text() + "() needs an evaluation clock");
        }
        return slots[clock.slot()];
    }

    /**
     * Tells whether a condition matches: only {@code true} matches, {@code null} never does.
     *
     * @param expr the condition
     * @param slots input values by slot index
     * @return true when the condition evaluates to {@code true}
     */
    public boolean matches(Expr expr, Object[] slots) {
        return Boolean.TRUE.equals(evaluate(expr, slots));
    }

    /** True when an element equals the target (FEEL list contains), so a null target only when the list holds null. */
    private boolean in(Object target, Expr.InList inList, Object[] slots) {
        for (Expr element : inList.elements()) {
            if (Boolean.TRUE.equals(equal(target, evaluate(element, slots)))) {
                return true;
            }
        }
        return false;
    }

    private Object call(Expr.Call call, Object[] slots) {
        Object first = evaluate(call.args().get(0), slots);
        Object second = evaluate(call.args().get(1), slots);
        return switch (call.function()) {
            case ROUND -> first instanceof BigDecimal number && second instanceof BigDecimal scale && Parser.isScale(scale)
                    ? number.setScale(scale.intValueExact(), RoundingMode.HALF_EVEN) : null;
            case DAYS_BETWEEN -> first instanceof LocalDate from && second instanceof LocalDate to
                    ? BigDecimal.valueOf(ChronoUnit.DAYS.between(from, to)) : null;
        };
    }

    private Object binary(Expr.Binary binary, Object[] slots) {
        Expr.BinaryOp op = binary.op();
        if (op == Expr.BinaryOp.AND) {
            Object left = evaluate(binary.left(), slots);
            typed(null, left, "and");
            if (Boolean.FALSE.equals(left)) {
                return false;
            }
            Object right = evaluate(binary.right(), slots);
            typed(null, right, "and");
            return and(left, right);
        }
        if (op == Expr.BinaryOp.OR) {
            Object left = evaluate(binary.left(), slots);
            typed(null, left, "or");
            if (Boolean.TRUE.equals(left)) {
                return true;
            }
            Object right = evaluate(binary.right(), slots);
            typed(null, right, "or");
            return or(left, right);
        }
        Object left = evaluate(binary.left(), slots);
        Object right = evaluate(binary.right(), slots);
        Object result = switch (op) {
            case EQ -> equal(left, right);
            case NE -> {
                Boolean equal = equal(left, right);
                yield equal == null ? null : !equal;
            }
            case LT, LE, GT, GE -> compare(op, left, right);
            case ADD, SUB, MUL, DIV -> arithmetic(op, left, right);
            case AND, OR -> throw new IllegalStateException("handled above");
        };
        return typed(result, left, right, op.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * In strict mode, a null result from two non-null operands means the operator does not accept their types: fail.
     */
    private Object typed(Object result, Object left, Object right, String op) {
        if (strict && result == null && left != null && right != null) {
            throw new EvaluationException("Operator " + op + " does not accept " + kind(left) + " and "
                    + kind(right));
        }
        return result;
    }

    /** In strict mode, a logical operand must be a boolean or null. */
    private void typed(Object ignored, Object operand, String op) {
        if (strict && operand != null && !(operand instanceof Boolean)) {
            throw new EvaluationException("Operator " + op + " needs booleans, got " + kind(operand));
        }
    }

    private static String kind(Object value) {
        return switch (value) {
            case Money m -> "money";
            case BigDecimal d -> "number";
            case String s -> "text";
            case Boolean b -> "boolean";
            case LocalDate d -> "date";
            case Instant i -> "datetime";
            default -> "a " + value.getClass().getSimpleName();
        };
    }

    private static Object unary(Expr.UnaryOp op, Object value) {
        return switch (op) {
            case NOT -> value instanceof Boolean b ? !b : null;
            case NEGATE -> switch (value) {
                case BigDecimal number -> number.negate();
                case Money money -> Money.ofMinor(money.currency(), negate(money.minorUnits()));
                case null, default -> null;
            };
        };
    }

    private static Object and(Object left, Object right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return false;
        }
        if (Boolean.TRUE.equals(left) && Boolean.TRUE.equals(right)) {
            return true;
        }
        return null;
    }

    private static Object or(Object left, Object right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
            return true;
        }
        if (Boolean.FALSE.equals(left) && Boolean.FALSE.equals(right)) {
            return false;
        }
        return null;
    }

    /** Two-valued for null; null for values of different types (FEEL). */
    private static Boolean equal(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            return a.compareTo(b) == 0;
        }
        if (left instanceof Money a && right instanceof Money b) {
            requireSameCurrency(a, b);
            return a.minorUnits() == b.minorUnits();
        }
        if (left.getClass() != right.getClass()) {
            return null;
        }
        return left.equals(right);
    }

    private static Boolean compare(Expr.BinaryOp op, Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        int c;
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            c = a.compareTo(b);
        } else if (left instanceof Money a && right instanceof Money b) {
            requireSameCurrency(a, b);
            c = Long.compare(a.minorUnits(), b.minorUnits());
        } else if (left instanceof String a && right instanceof String b) {
            c = compareCodePoints(a, b);
        } else if (left instanceof LocalDate a && right instanceof LocalDate b) {
            c = a.compareTo(b);
        } else if (left instanceof Instant a && right instanceof Instant b) {
            c = a.compareTo(b);
        } else {
            return null;
        }
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            case GE -> c >= 0;
            default -> throw new IllegalStateException("not a comparison: " + op);
        };
    }

    private Object arithmetic(Expr.BinaryOp op, Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
            return switch (op) {
                case ADD -> a.add(b, MathContext.DECIMAL128);
                case SUB -> a.subtract(b, MathContext.DECIMAL128);
                case MUL -> a.multiply(b, MathContext.DECIMAL128);
                case DIV -> {
                    if (b.signum() == 0) {
                        throw new EvaluationException("Division by zero");
                    }
                    yield a.divide(b, MathContext.DECIMAL128);
                }
                default -> throw new IllegalStateException("not arithmetic: " + op);
            };
        }
        if (left instanceof Money a && right instanceof Money b) {
            requireSameCurrency(a, b);
            return switch (op) {
                case ADD -> Money.ofMinor(a.currency(), exact(() -> Math.addExact(a.minorUnits(), b.minorUnits())));
                case SUB -> Money.ofMinor(a.currency(), exact(() -> Math.subtractExact(a.minorUnits(), b.minorUnits())));
                default -> throw new EvaluationException("money " + op + " money is not defined");
            };
        }
        if (left instanceof Money a && right instanceof BigDecimal b && (op == Expr.BinaryOp.MUL || op == Expr.BinaryOp.DIV)) {
            return scale(a, b, op);
        }
        if (left instanceof BigDecimal a && right instanceof Money b && op == Expr.BinaryOp.MUL) {
            return scale(b, a, op);
        }
        if (left instanceof String a && right instanceof String b && op == Expr.BinaryOp.ADD) {
            // Checked before concatenating, so the oversized string is never built.
            if ((long) a.length() + b.length() > maxStringLength) {
                throw new EvaluationException("String longer than " + maxStringLength + " characters");
            }
            return a + b;
        }
        return null;
    }

    private Money scale(Money money, BigDecimal factor, Expr.BinaryOp op) {
        BigDecimal major = money.toMajor();
        BigDecimal result;
        if (op == Expr.BinaryOp.MUL) {
            result = major.multiply(factor, MathContext.DECIMAL128);
        } else {
            if (factor.signum() == 0) {
                throw new EvaluationException("Division by zero");
            }
            result = major.divide(factor, MathContext.DECIMAL128);
        }
        int scale = Money.scaleOf(money.currency());
        BigDecimal rounded = result.setScale(scale, moneyRounding);
        long minor = exact(() -> rounded.movePointRight(scale).longValueExact());
        return Money.ofMinor(money.currency(), minor);
    }

    private static void requireSameCurrency(Money a, Money b) {
        if (!a.currency().equals(b.currency())) {
            throw new EvaluationException("Currency mismatch: " + a.currency() + " and " + b.currency());
        }
    }

    /**
     * Compares strings by Unicode code point, the order of {@code <} and of {@code collect} min and max.
     *
     * @param a the first string
     * @param b the second string
     * @return negative, zero or positive
     */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static long negate(long value) {
        return exact(() -> Math.negateExact(value));
    }

    private static long exact(java.util.function.LongSupplier operation) {
        try {
            return operation.getAsLong();
        } catch (ArithmeticException e) {
            throw new EvaluationException("Money amount out of range");
        }
    }
}
