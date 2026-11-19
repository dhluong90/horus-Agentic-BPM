package io.github.dhluong90.bpm.process.api;

/** A request body or the instance variables exceed their size limit. Code {@code PAYLOAD_TOO_LARGE}. */
public final class PayloadTooLargeException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public PayloadTooLargeException(String message) {
        super("PAYLOAD_TOO_LARGE", message);
    }
}
