package io.github.dhluong90.bpm.process.api;

/** The object is not in a state that allows the operation. Code {@code INVALID_STATE}. */
public final class InvalidStateException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public InvalidStateException(String message) {
        super("INVALID_STATE", message);
    }
}
