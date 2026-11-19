package io.github.dhluong90.bpm.process.api;

/** The If-Match lock version is stale. Code {@code VERSION_CONFLICT}. */
public final class VersionConflictException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public VersionConflictException(String message) {
        super("VERSION_CONFLICT", message);
    }
}
