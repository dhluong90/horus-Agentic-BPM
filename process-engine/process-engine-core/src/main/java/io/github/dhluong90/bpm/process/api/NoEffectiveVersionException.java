package io.github.dhluong90.bpm.process.api;

/** The tenant has no active version of the definition key. Code {@code NO_EFFECTIVE_VERSION}. */
public final class NoEffectiveVersionException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public NoEffectiveVersionException(String message) {
        super("NO_EFFECTIVE_VERSION", message);
    }
}
