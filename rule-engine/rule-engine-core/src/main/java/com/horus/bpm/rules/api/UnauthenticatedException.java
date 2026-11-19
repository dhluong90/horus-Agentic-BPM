package com.horus.bpm.rules.api;

/** {@code 401 UNAUTHENTICATED}: no tenant or caller could be resolved from the security context. */
public final class UnauthenticatedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail what is missing
     */
    public UnauthenticatedException(String detail) {
        super("UNAUTHENTICATED", detail);
    }
}
