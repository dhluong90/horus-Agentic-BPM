package com.horus.bpm.rules.internal.eval;

import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.internal.expr.EvaluationException;
import com.horus.bpm.rules.internal.model.OutputClause;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Aggregates of a {@code collect} output over the non-null values of the matched rules, in rule order.
 * Sums are exact: numbers in {@link MathContext#DECIMAL128}, money in minor units of one currency.
 */
final class Aggregates {

    private Aggregates() {
    }

    static Object apply(OutputClause.Aggregate aggregate, List<Object> values, String output) {
        return switch (aggregate) {
            case NONE -> List.copyOf(values);
            case COUNT -> BigDecimal.valueOf(values.size());
            case SUM -> sum(values, output);
            case MIN -> extreme(values, output, -1);
            case MAX -> extreme(values, output, 1);
        };
    }

    private static Object sum(List<Object> values, String output) {
        if (values.isEmpty()) {
            return null;
        }
        if (values.getFirst() instanceof Money first) {
            long total = 0;
            for (Object value : values) {
                Money money = (Money) value;
                if (!money.currency().equals(first.currency())) {
                    throw new EvaluationException("Output " + output + ": currency mismatch: "
                            + first.currency() + " and " + money.currency());
                }
                try {
                    total = Math.addExact(total, money.minorUnits());
                } catch (ArithmeticException e) {
                    throw new EvaluationException("Output " + output + ": money amount out of range");
                }
            }
            return Money.ofMinor(first.currency(), total);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Object value : values) {
            total = total.add((BigDecimal) value, MathContext.DECIMAL128);
        }
        return total;
    }

    private static Object extreme(List<Object> values, String output, int sign) {
        Object best = null;
        for (Object value : values) {
            if (best == null || Integer.signum(compare(value, best, output)) == sign) {
                best = value;
            }
        }
        return best;
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
            case String x -> x.compareTo((String) b);
            case LocalDate x -> x.compareTo((LocalDate) b);
            case Instant x -> x.compareTo((Instant) b);
            default -> throw new EvaluationException("Output " + output + ": values cannot be ordered");
        };
    }
}
