package io.github.dhluong90.bpm.expr.spi;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;

/** A compiled expression. Immutable and thread-safe. */
public interface CompiledExpression {

    /** @return the source text it was compiled from */
    String source();

    /** @return the result type, {@link ValueType#ANY} when it depends on the values */
    ValueType resultType();

    /** @return how it runs */
    EvaluationProfile profile();

    /**
     * @param context the values and the clock
     * @return the value, possibly null
     * @throws ExpressionEvaluationException on a deterministic failure
     */
    Object evaluate(EvaluationContext context);

    /**
     * Tells whether a condition holds: only {@code true} holds; {@code null} and {@code false} do not.
     *
     * @param context the values and the clock
     * @return whether the result is {@code true}
     * @throws ExpressionEvaluationException on a deterministic failure, or when the result is not a boolean
     */
    default boolean holds(EvaluationContext context) {
        Object value = evaluate(context);
        if (value == null || value instanceof Boolean) {
            return Boolean.TRUE.equals(value);
        }
        throw new ExpressionEvaluationException("A condition must give a boolean");
    }
}
