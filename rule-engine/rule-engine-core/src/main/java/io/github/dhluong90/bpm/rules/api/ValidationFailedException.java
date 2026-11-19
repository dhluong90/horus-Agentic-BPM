package io.github.dhluong90.bpm.rules.api;

/** {@code 422 VALIDATION_FAILED}: the definition cannot be read or compiled. */
public final class ValidationFailedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail the problem, with the rule id where known
     */
    public ValidationFailedException(String detail) {
        super("VALIDATION_FAILED", detail);
    }
}
