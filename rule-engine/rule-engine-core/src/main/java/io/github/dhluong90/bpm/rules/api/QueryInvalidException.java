package io.github.dhluong90.bpm.rules.api;

/** {@code 400 QUERY_INVALID}: a list or search parameter is out of range or malformed (ADR-0015). */
public final class QueryInvalidException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail the parameter and what is wrong with it
     */
    public QueryInvalidException(String detail) {
        super("QUERY_INVALID", detail);
    }
}
