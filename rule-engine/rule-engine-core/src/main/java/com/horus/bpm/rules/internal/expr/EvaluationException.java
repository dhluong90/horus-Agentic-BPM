package com.horus.bpm.rules.internal.expr;

/** A deterministic evaluation failure, logged as {@code EVALUATION_ERROR}. */
public final class EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the detail, without input values
     */
    public EvaluationException(String message) {
        super(message);
    }
}
