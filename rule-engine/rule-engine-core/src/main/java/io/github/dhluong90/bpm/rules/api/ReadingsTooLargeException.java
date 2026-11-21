package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 422 READINGS_TOO_LARGE}: a request for readings covers more rows or samples than one request may read
 * (RLE-091 to RLE-093). Nothing is truncated: the caller reads fewer, for example one rule set at a time.
 */
public final class ReadingsTooLargeException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param what {@code rows} or {@code samples}
     * @param count how many the request covers
     * @param limit the most one request may read
     */
    public ReadingsTooLargeException(String what, int count, int limit) {
        super("READINGS_TOO_LARGE", "One request reads at most " + limit + " " + what + ", this one covers " + count
                + "; read fewer at a time");
    }
}
