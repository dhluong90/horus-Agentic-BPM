package com.horus.bpm.rules.api;

/** {@code 422 IDEMPOTENCY_KEY_REUSE_MISMATCH}: the idempotency key was already used with a different request (ADR-0006). */
public final class IdempotencyKeyReuseException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public IdempotencyKeyReuseException() {
        super("IDEMPOTENCY_KEY_REUSE_MISMATCH", "The idempotency key was used with a different request");
    }
}
