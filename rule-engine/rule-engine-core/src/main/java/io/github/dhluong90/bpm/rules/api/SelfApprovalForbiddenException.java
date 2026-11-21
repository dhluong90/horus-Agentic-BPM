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

    private SelfApprovalForbiddenException(String detail) {
        super("SELF_APPROVAL_FORBIDDEN", detail);
    }

    /**
     * Returns the refusal of a requester rejecting their own request (PD-089): they withdraw it instead.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @return the exception
     */
    public static SelfApprovalForbiddenException selfReject(String ruleSet, int version) {
        return new SelfApprovalForbiddenException("The requester of " + ruleSet + " v" + version
                + " cannot reject it; take the request back with :withdraw");
    }

    /**
     * The requester of a four-eyes retirement tried to confirm it (RLE-038).
     *
     * @param ruleSet the rule set
     * @param version the version
     * @return the exception
     */
    public static SelfApprovalForbiddenException selfConfirmRetire(String ruleSet, int version) {
        return new SelfApprovalForbiddenException("The requester of the retirement of " + ruleSet + " v" + version
                + " cannot confirm it; another holder of rules:approve confirms, or the request is cancelled with"
                + " :cancel-retire");
    }
}
