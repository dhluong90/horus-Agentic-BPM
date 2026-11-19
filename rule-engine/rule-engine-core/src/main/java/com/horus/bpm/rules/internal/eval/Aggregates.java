package com.horus.bpm.rules.internal.eval;

import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.internal.expr.EvaluationException;
import com.horus.bpm.rules.internal.model.OutputClause;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Aggregates of a {@code collect} output over the non-null values of the matched rules, in rule order.
 * Sums are exact: money in minor units of one currency, numbers without rounding. {@code count} counts every matched
 * rule, nulls included; the caller refuses a null under {@code sum}, {@code min} and {@code max} (errorMode strict). A number sum that needs more
 * than 34 significant digits ({@link MathContext#DECIMAL128}) fails with {@code EVALUATION_ERROR} instead of being
 * rounded silently. Strings order by Unicode code point, as {@code <} does.
 */
final class Aggregates {

    private Aggregates() {
    }

    /**
     * A streaming aggregate of one {@code collect} output: each matched rule's value is folded in as it is read, so
     * {@code sum}, {@code min}, {@code max} and {@code count} hold one accumulator, never a column of values.
     */
    static final class Accumulator {

        private final OutputClause.Aggregate aggregate;
        private final String output;
        private long count;
        private Object best;
        private String currency;
        private long minorTotal;
        private BigDecimal numberTotal;

        Accumulator(OutputClause.Aggregate aggregate, String output) {
            if (aggregate == OutputClause.Aggregate.NONE) {
                throw new IllegalArgumentException("a list output is not aggregated");
            }
            this.aggregate = aggregate;
            this.output = output;
        }

        /** Folds in one value; null is accepted only by {@code count}, which counts every matched rule. */
        void add(Object value) {
            count++;
            if (value == null) {
                if (aggregate != OutputClause.Aggregate.COUNT) {
                    throw new IllegalArgumentException("null reaches " + aggregate + " of output " + output);
                }
                return;
            }
            switch (aggregate) {
                case COUNT, NONE -> {
                    // count only
                }
                case MIN -> best = best == null || compare(value, best, output) < 0 ? value : best;
                case MAX -> best = best == null || compare(value, best, output) > 0 ? value : best;
                case SUM -> addToSum(value);
            }
        }

        private void addToSum(Object value) {
            if (value instanceof Money money) {
                if (numberTotal != null || (currency != null && !currency.equals(money.currency()))) {
                    throw new EvaluationException("Output " + output + ": currency mismatch: "
                            + (currency == null ? "number" : currency) + " and " + money.currency());
                }
                currency = money.currency();
                try {
                    minorTotal = Math.addExact(minorTotal, money.minorUnits());
                } catch (ArithmeticException e) {
                    throw new EvaluationException("Output " + output + ": money amount out of range");
                }
                return;
            }
            if (currency != null) {
                throw new EvaluationException("Output " + output + ": a sum cannot mix money and numbers");
            }
            numberTotal = (numberTotal == null ? BigDecimal.ZERO : numberTotal).add((BigDecimal) value);
        }

        /**
         * Returns the aggregate; the caller never asks without a matched rule, so {@code count} is at least 1.
         *
         * @return the result
         */
        Object result() {
            return switch (aggregate) {
                case COUNT -> BigDecimal.valueOf(count);
                case MIN, MAX -> best;
                case SUM -> sumResult();
                case NONE -> throw new IllegalStateException("a list output is not aggregated");
            };
        }

        private Object sumResult() {
            if (count == 0) {
                return null;
            }
            if (currency != null) {
                if (Math.abs(minorTotal) > Money.MAX_JSON_SAFE) {
                    throw new EvaluationException("Output " + output
                            + ": money amount beyond what JSON clients read exactly");
                }
                return Money.ofMinor(currency, minorTotal);
            }
            if (numberTotal.signum() != 0
                    && numberTotal.stripTrailingZeros().precision() > MathContext.DECIMAL128.getPrecision()) {
                throw new EvaluationException("Output " + output + ": the sum needs more than "
                        + MathContext.DECIMAL128.getPrecision() + " significant digits");
            }
            return numberTotal;
        }
    }

    private static int compare(Object a, Object b, String output) {
        return switch (a) {
            case BigDecimal x -> x.compareTo((BigDecimal) b);
            case Money x -> {
                Money y = (Money) b;
                if (!x.currency().equals(y.currency())) {
                    throw new EvaluationException("Output " + output + ": currency mismatch: "
                            + x.currency() + " and " + y.currency());
                }
                yield Long.compare(x.minorUnits(), y.minorUnits());
            }
            case String x -> com.horus.bpm.rules.internal.expr.Evaluator.compareCodePoints(x, (String) b);
            case LocalDate x -> x.compareTo((LocalDate) b);
            case Instant x -> x.compareTo((Instant) b);
            default -> throw new EvaluationException("Output " + output + ": values cannot be ordered");
        };
    }
}
