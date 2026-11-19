package io.github.dhluong90.bpm.rules.api;

/**
 * Which page of a list to read.
 *
 * @param limit items per page, 1 to {@code query.max-page-size}; null for the default (50, or the maximum when lower)
 * @param cursor the {@code nextCursor} of the previous page, or null for the first page
 */
public record PageRequest(Integer limit, String cursor) {

    /**
     * Returns the first page with the default size.
     *
     * @return the request
     */
    public static PageRequest first() {
        return new PageRequest(null, null);
    }
}
