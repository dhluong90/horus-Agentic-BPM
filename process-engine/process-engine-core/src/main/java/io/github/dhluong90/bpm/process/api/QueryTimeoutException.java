package io.github.dhluong90.bpm.process.api;

/**
 * A list read ran longer than {@code query.statement-timeout} and the database stopped it (ADR-0015, {@code PRE-394}).
 * No partial page is returned; the caller narrows the filter or tries again. Code {@code QUERY_TIMEOUT}.
 */
public final class QueryTimeoutException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, which names the limit */
    public QueryTimeoutException(String message) {
        super("QUERY_TIMEOUT", message);
    }
}
