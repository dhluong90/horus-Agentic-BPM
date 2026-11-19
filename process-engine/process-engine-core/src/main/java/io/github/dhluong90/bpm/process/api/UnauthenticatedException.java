package io.github.dhluong90.bpm.process.api;

/** No tenant or caller in the security context. Code {@code UNAUTHENTICATED}. */
public final class UnauthenticatedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public UnauthenticatedException(String message) {
        super("UNAUTHENTICATED", message);
    }
}
