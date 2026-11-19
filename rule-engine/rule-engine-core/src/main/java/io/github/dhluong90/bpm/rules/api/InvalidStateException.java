package io.github.dhluong90.bpm.rules.api;

/** {@code 409 INVALID_STATE}: the command is not allowed in the version's status. */
public final class InvalidStateException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param status the current status
     * @param command the refused command
     */
    public InvalidStateException(String ruleSet, int version, VersionStatus status, String command) {
        super("INVALID_STATE", command + " is not allowed on " + ruleSet + " v" + version + " in status " + status);
    }
}
