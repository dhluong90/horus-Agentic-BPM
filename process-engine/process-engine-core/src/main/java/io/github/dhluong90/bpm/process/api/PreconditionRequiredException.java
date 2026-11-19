package io.github.dhluong90.bpm.process.api;

/** A required If-Match or Idempotency-Key header is missing. Code {@code PRECONDITION_REQUIRED}. */
public final class PreconditionRequiredException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public PreconditionRequiredException(String message) {
        super("PRECONDITION_REQUIRED", message);
    }
}
