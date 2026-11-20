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
     * Returns the refusal of a draft replacement by anyone but its author (RLE-047): otherwise a second author could
     * replace the content and then approve it, because four eyes compares the approver with the author.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @return the exception
     */
    public static PermissionDeniedException notAuthor(String ruleSet, int version) {
        return new PermissionDeniedException("Only the author of " + ruleSet + " v" + version
                + " may replace it; import your own document as a new version with POST .../rule-sets/" + ruleSet
                + "/versions", true);
    }
}
