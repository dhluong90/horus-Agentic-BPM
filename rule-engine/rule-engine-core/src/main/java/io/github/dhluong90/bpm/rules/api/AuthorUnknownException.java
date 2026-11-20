package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 409 AUTHOR_UNKNOWN}: the pending version has no recorded author, so four eyes cannot tell whether the approver
 * made it (PD-105). Versions written before the author was recorded have none. The engine never assigns one; the
 * change is submitted again, which records its author, and that version is approved instead.
 */
public final class AuthorUnknownException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     */
    public AuthorUnknownException(String ruleSet, int version) {
        super("AUTHOR_UNKNOWN", ruleSet + " v" + version + " has no recorded author, so nobody can approve it under"
                + " four eyes; import the change again, which records its author, and reject or withdraw this version");
    }
}
