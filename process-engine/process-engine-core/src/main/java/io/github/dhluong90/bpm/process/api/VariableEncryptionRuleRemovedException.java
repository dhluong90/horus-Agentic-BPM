package io.github.dhluong90.bpm.process.api;

/**
 * A write would store a variable in the clear that the row still holds encrypted, because the encryption rules no longer
 * name it. The engine refuses and changes nothing: switching a rule off never decrypts a stored value silently. Code
 * {@code VARIABLE_ENCRYPTION_RULE_REMOVED}.
 */
public final class VariableEncryptionRuleRemovedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail; names variables and the instance, never a value */
    public VariableEncryptionRuleRemovedException(String message) {
        super("VARIABLE_ENCRYPTION_RULE_REMOVED", message);
    }
}
