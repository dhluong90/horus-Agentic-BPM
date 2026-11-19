package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 428 PRECONDITION_REQUIRED}: a command that must be conditional was sent without {@code If-Match} (RFC 6585),
 * so it could overwrite a change the caller has not seen.
 */
public final class PreconditionRequiredException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operation the command, for example {@code :withdraw}
     */
    public PreconditionRequiredException(String operation) {
        super("PRECONDITION_REQUIRED", operation + " requires If-Match with the lock version it is based on,"
                + " for example If-Match: \"3\"");
    }
}
