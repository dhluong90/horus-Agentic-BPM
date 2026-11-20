package io.github.dhluong90.bpm.expr.api;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Java value mapping of expressions (rule engine DESIGN section 3.5). On input, {@code Integer}, {@code Long},
 * {@code Short}, {@code Byte} and {@code BigInteger} become {@code BigDecimal} in {@code DECIMAL128}; {@code String},
 * {@code Boolean}, {@code BigDecimal}, {@code LocalDate}, {@code Instant} and {@link Money} pass through; a list is
 * copied with each element mapped, immutable. A map passes through so that a dotted path can read into it. Any other
 * type, {@code Double} and {@code Float} included (binary floating point is not exact), is refused.
 */
public final class ExpressionValues {

    private ExpressionValues() {
    }

    /**
     * @param value a host or instance value, possibly null
     * @return the expression value
     * @throws ExpressionEvaluationException when the type has no expression mapping
     */
    public static Object toExpressionValue(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case BigDecimal d -> d.round(MathContext.DECIMAL128);
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case BigInteger i -> new BigDecimal(i, MathContext.DECIMAL128);
            case Money m -> m;
            case LocalDate d -> d;
            case Instant i -> i;
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object element : list) {
                    copy.add(toExpressionValue(element));
                }
                yield Collections.unmodifiableList(copy);
            }
            case Map<?, ?> map -> map;
            default -> throw new ExpressionEvaluationException("A " + value.getClass().getSimpleName()
                    + " has no expression type");
        };
    }
}
