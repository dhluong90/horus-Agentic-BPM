package com.horus.bpm.rules.api;

/** {@code 409 VERSION_CONFLICT}: the expected lock version is stale; reload and retry. */
public final class VersionConflictException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail what conflicted
     */
    public VersionConflictException(String detail) {
        super("VERSION_CONFLICT", detail);
    }
}
