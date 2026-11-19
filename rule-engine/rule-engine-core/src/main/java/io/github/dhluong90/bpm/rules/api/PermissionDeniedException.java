package io.github.dhluong90.bpm.rules.api;

/** {@code 403 PERMISSION_DENIED}: the caller lacks the permission the operation needs (ADR-0010). */
public final class PermissionDeniedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param permission the missing permission, for example {@code rules:approve}
     */
    public PermissionDeniedException(String permission) {
        super("PERMISSION_DENIED", "The caller lacks permission " + permission);
    }

    private PermissionDeniedException(String detail, boolean ignored) {
        super("PERMISSION_DENIED", detail);
    }

    /**
     * Returns the refusal of a withdrawal by anyone but the requester (PD-089, RLE-046): an approver rejects instead.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @return the exception
     */
    public static PermissionDeniedException notRequester(String ruleSet, int version) {
        return new PermissionDeniedException("Only the requester of " + ruleSet + " v" + version
                + " may withdraw it; an approver sends it back with :reject", true);
    }
}
