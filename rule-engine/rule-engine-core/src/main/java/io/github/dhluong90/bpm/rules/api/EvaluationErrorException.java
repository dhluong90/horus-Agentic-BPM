package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 422 EVALUATION_ERROR} for a request that is refused whole because an evaluation limit stopped it, as the
 * regular expression read budget of one request does for a comparison over history (RLE-077). Unlike a failed decision
 * it returns no partial result.
 */
public final class EvaluationErrorException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail the reason code and what was reached, for example {@code REGEX_TIMEOUT: ...}
     */
    public EvaluationErrorException(String detail) {
        super("EVALUATION_ERROR", detail);
    }
}
