package io.github.dhluong90.bpm.rules.internal.expr;

import io.github.dhluong90.bpm.rules.api.Money;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Evaluates an {@link Expr} over input slots with FEEL semantics (rule-engine/docs/DESIGN.md section
 * 3.2, PD-058): numbers are {@code BigDecimal} in {@link MathContext#DECIMAL128}; comparisons
 * and arithmetic with a null operand give null; {@code between} is {@code x >= a and x <= b}, as
 * DMN 1.5; {@code ==} and {@code !=} are two-valued; {@code in} is true when an element equals the
 * target, so a null target is in a list only when the list holds null; {@code and}, {@code or} and
 * {@code not} follow the FEEL truth tables. Mixed currencies and division by zero raise {@link EvaluationException}.
 * Stateless and thread-safe.
 */
public final class Evaluator {

    private final RoundingMode moneyRounding;
    private final int maxStringLength;

    /**
     * Creates an evaluator with the default string bound.
     *
     * @param moneyRounding rounding of money multiplication and division
     */
    public Evaluator(RoundingMode moneyRounding) {
        this(moneyRounding, io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.STRING_MAX_LENGTH);
    }

    /**
     * Creates an evaluator.
     *
     * @param moneyRounding rounding of money multiplication and division
     * @param maxStringLength the longest string {@code +} may build; longer is {@link EvaluationException}
     */
    public Evaluator(RoundingMode moneyRounding, int maxStringLength) {
        this.moneyRounding = moneyRounding;
        this.maxStringLength = maxStringLength;
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
        return switch (expr) {
            case Expr.Literal literal -> literal.value();
            case Expr.Var var -> slots[var.slot()];
            case Expr.Unary unary -> unary(unary.op(), evaluate(unary.operand(), slots));
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
        };
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

    private Object binary(Expr.Binary binary, Object[] slots) {
        Expr.BinaryOp op = binary.op();
        if (op == Expr.BinaryOp.AND) {
            Object left = evaluate(binary.left(), slots);
            if (Boolean.FALSE.equals(left)) {
                return false;
            }
            return and(left, evaluate(binary.right(), slots));
        }
        if (op == Expr.BinaryOp.OR) {
            Object left = evaluate(binary.left(), slots);
            if (Boolean.TRUE.equals(left)) {
                return true;
            }
            return or(left, evaluate(binary.right(), slots));
        }
        Object left = evaluate(binary.left(), slots);
        Object right = evaluate(binary.right(), slots);
        return switch (op) {
            case EQ -> equal(left, right);
            case NE -> {
                Boolean equal = equal(left, right);
                yield equal == null ? null : !equal;
            }
            case LT, LE, GT, GE -> compare(op, left, right);
            case ADD, SUB, MUL, DIV -> arithmetic(op, left, right);
            case AND, OR -> throw new IllegalStateException("handled above");
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
