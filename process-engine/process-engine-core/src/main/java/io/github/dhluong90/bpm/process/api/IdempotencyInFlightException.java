package io.github.dhluong90.bpm.process.api;

/** A request with the same idempotency key is still running. Code {@code IDEMPOTENCY_IN_FLIGHT}. */
public final class IdempotencyInFlightException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public IdempotencyInFlightException(String message) {
        super("IDEMPOTENCY_IN_FLIGHT", message);
    }
}
