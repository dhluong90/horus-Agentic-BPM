package io.github.dhluong90.bpm.rules.api;

/** {@code 403 SELF_APPROVAL_FORBIDDEN}: the approver requested the publication or authored the version (TR-SEC-04). */
public final class SelfApprovalForbiddenException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     */
    public SelfApprovalForbiddenException(String ruleSet, int version) {
        super("SELF_APPROVAL_FORBIDDEN", "The requester or author of " + ruleSet + " v" + version + " cannot approve it");
    }
}
