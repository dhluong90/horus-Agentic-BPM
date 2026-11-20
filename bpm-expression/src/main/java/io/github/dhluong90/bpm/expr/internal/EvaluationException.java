package io.github.dhluong90.bpm.expr.internal;

/** A deterministic evaluation failure, logged as {@code EVALUATION_ERROR}. */
public final class EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /**
     * Creates the exception.
     *
     * @param message the detail, without input values
     */
    public EvaluationException(String message) {
        this(null, message);
    }

    /**
     * Creates the exception with a reason code, which the decision row records as its {@code error_code} and the
     * detail starts with.
     *
     * @param reason the reason code, for example {@code REGEX_TIMEOUT}, or null
     * @param message the detail, without input values
     */
    public EvaluationException(String reason, String message) {
        super(reason == null ? message : reason + ": " + message);
        this.reason = reason;
    }

    /**
     * Returns the reason code.
     *
     * @return the reason, or null when the failure has none beyond {@code EVALUATION_ERROR}
     */
    public String reason() {
        return reason;
    }
}
