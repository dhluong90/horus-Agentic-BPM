package io.github.dhluong90.bpm.process.api;

/**
 * A stored encrypted variable does not authenticate: it was changed, copied from another tenant, instance or variable,
 * or is malformed. The engine refuses and returns nothing of it. Code {@code VARIABLE_INTEGRITY_FAILED}.
 */
public final class VariableIntegrityException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail; never a value */
    public VariableIntegrityException(String message) {
        super("VARIABLE_INTEGRITY_FAILED", message);
    }
}
