package io.github.dhluong90.bpm.process.api;

/**
 * A four-eyes task was completed by the identity that started the instance or completed an earlier task of it
 * (PRE-007). Code {@code SELF_APPROVAL_FORBIDDEN}.
 */
public final class SelfApprovalForbiddenException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail; never names other identities */
    public SelfApprovalForbiddenException(String message) {
        super("SELF_APPROVAL_FORBIDDEN", message);
    }
}
