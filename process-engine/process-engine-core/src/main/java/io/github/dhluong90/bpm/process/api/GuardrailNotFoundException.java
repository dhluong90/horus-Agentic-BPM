package io.github.dhluong90.bpm.process.api;

/**
 * The candidate guardrail of a replay does not exist in the caller's tenant: no such rule set or version, the same
 * answer for another tenant's rule set. Code {@code RULE_SET_NOT_FOUND}.
 */
public final class GuardrailNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the message */
    public GuardrailNotFoundException(String message) {
        super("RULE_SET_NOT_FOUND", message);
    }
}
