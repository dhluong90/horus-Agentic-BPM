package io.github.dhluong90.bpm.rules.api;

/** {@code 404 VERSION_NOT_FOUND}: the rule set has no such version in the caller's tenant. */
public final class VersionNotFoundException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     */
    public VersionNotFoundException(String ruleSet, int version) {
        super("VERSION_NOT_FOUND", "Version " + version + " of rule set " + ruleSet + " not found");
    }
}
