package io.github.dhluong90.bpm.process.api;

/** The idempotency key was used before with a different request. Code {@code IDEMPOTENCY_KEY_REUSE_MISMATCH}. */
public final class IdempotencyKeyReuseException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public IdempotencyKeyReuseException(String message) {
        super("IDEMPOTENCY_KEY_REUSE_MISMATCH", message);
    }
}
