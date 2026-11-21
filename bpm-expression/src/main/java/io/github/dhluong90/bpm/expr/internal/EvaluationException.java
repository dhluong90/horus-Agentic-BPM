package io.github.dhluong90.bpm.expr.internal;

/** A deterministic evaluation failure, logged as {@code EVALUATION_ERROR}. */
public final class EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reason;
    private final String limit;

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
        this(reason, null, message);
    }

    /**
     * Creates the exception with a reason code and the limit that was reached.
     *
     * @param reason the reason code, for example {@code EVALUATION_BUDGET_EXCEEDED}, or null
     * @param limit the limit that was reached, {@code STEPS} or {@code TIME} for {@code EVALUATION_BUDGET_EXCEEDED},
     *     or null
     * @param message the detail, without input values
     */
    public EvaluationException(String reason, String limit, String message) {
        super(reason == null ? message : reason + ": " + message);
        this.reason = reason;
        this.limit = limit;
    }

    /**
     * Creates the exception with a reason code and the failure that caused it, which is kept for the server log and
     * never put in the message.
     *
     * @param reason the reason code, for example {@code FUNCTION_FAILED}
     * @param message the detail, without input values and without the cause's message
     * @param cause what failed, for the log only
     */
    public EvaluationException(String reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
        this.limit = null;
    }

    /**
     * Returns the reason code.
     *
     * @return the reason, or null when the failure has none beyond {@code EVALUATION_ERROR}
     */
    public String reason() {
        return reason;
    }

    /**
     * Returns the limit that was reached.
     *
     * @return {@code STEPS} or {@code TIME} for {@code EVALUATION_BUDGET_EXCEEDED}, else null
     */
    public String limit() {
        return limit;
    }
}
