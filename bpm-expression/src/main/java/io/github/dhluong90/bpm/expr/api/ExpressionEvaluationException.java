package io.github.dhluong90.bpm.expr.api;

/**
 * A deterministic evaluation failure: division by zero, two currencies, a value of the wrong type where the language
 * refuses it, a missing evaluation clock. The same expression over the same values always fails the same way.
 */
public final class ExpressionEvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** @param message what failed; never the input values */
    public ExpressionEvaluationException(String message) {
        super(message);
    }
}
