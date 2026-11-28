package io.github.dhluong90.bpm.process.api;

/**
 * A protected variable cannot be written or read because the keyring has no key for it: none is current, or the key id
 * on the stored value is not held any more. The engine refuses and never stores or returns the value in the clear. Code
 * {@code VARIABLE_KEY_UNAVAILABLE}.
 */
public final class VariableKeyUnavailableException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail; names a key id, never a key or a value */
    public VariableKeyUnavailableException(String message) {
        super("VARIABLE_KEY_UNAVAILABLE", message);
    }
}
