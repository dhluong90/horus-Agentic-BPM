package com.horus.bpm.rules.api;

/** {@code 409 IDEMPOTENCY_IN_FLIGHT}: a request with the same idempotency key is still running; retry after one second. */
public final class IdempotencyInFlightException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     */
    public IdempotencyInFlightException() {
        super("IDEMPOTENCY_IN_FLIGHT", "A request with the same idempotency key is still running");
    }
}
