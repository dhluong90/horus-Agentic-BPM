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

    private InvalidStateException(String message) {
        super("INVALID_STATE", message);
    }

    /**
     * A retire of a version a newer version in effect has already superseded: it no longer serves and the lifecycle
     * sweep retires it on its own (ADR-0025 decision 2).
     *
     * @param ruleSet the rule set name
     * @param version the version number
     * @param supersededBy the version in force
     * @return the exception
     */
    public static InvalidStateException superseded(String ruleSet, int version, int supersededBy) {
        return new InvalidStateException("retire is not allowed on " + ruleSet + " v" + version + ": v" + supersededBy
                + " is in effect and supersedes it, so it no longer serves and retires on its own");
    }

    /**
     * A retirement is already pending for the version (RLE-038).
     *
     * @param ruleSet the rule set
     * @param version the version
     * @param requestedBy who requested it
     * @return the exception
     */
    public static InvalidStateException retirePending(String ruleSet, int version, String requestedBy) {
        return new InvalidStateException("retire is not allowed on " + ruleSet + " v" + version + ": a retirement"
                + " requested by " + requestedBy + " is pending; confirm it with :confirm-retire or cancel it with"
                + " :cancel-retire");
    }

    /**
     * No retirement is pending for the version, so there is nothing to confirm or cancel (RLE-038).
     *
     * @param ruleSet the rule set
     * @param version the version
     * @param status its status
     * @param command the command, {@code confirm-retire} or {@code cancel-retire}
     * @return the exception
     */
    public static InvalidStateException noRetirePending(String ruleSet, int version, VersionStatus status,
            String command) {
        return new InvalidStateException(command + " is not allowed on " + ruleSet + " v" + version + " in status "
                + status + ": no retirement is pending");
    }
}
