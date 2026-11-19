package io.github.dhluong90.bpm.process.api;

/**
 * An older version was approved while a newer version of the key is already active (PRE-021). Code
 * {@code VERSION_SUPERSEDED}; the version stays {@code PENDING_APPROVAL}.
 */
public final class VersionSupersededException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public VersionSupersededException(String message) {
        super("VERSION_SUPERSEDED", message);
    }
}
