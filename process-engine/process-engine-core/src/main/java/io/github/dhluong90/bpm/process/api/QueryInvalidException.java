package io.github.dhluong90.bpm.process.api;

/**
 * A list query breaks a read limit: a page size outside 1 to {@code query.max-page-size}, a cursor this list did not
 * issue to the caller's tenant, or a time range longer than {@code query.max-range}. Code {@code QUERY_INVALID}.
 */
public final class QueryInvalidException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public QueryInvalidException(String message) {
        super("QUERY_INVALID", message);
    }
}
