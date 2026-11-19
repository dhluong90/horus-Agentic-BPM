package io.github.dhluong90.bpm.process.api;

/** A request value is malformed. Code {@code INPUT_INVALID}. */
public final class InputInvalidException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public InputInvalidException(String message) {
        super("INPUT_INVALID", message);
    }
}
