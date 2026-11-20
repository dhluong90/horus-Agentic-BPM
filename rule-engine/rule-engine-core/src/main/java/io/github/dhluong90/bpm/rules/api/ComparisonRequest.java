package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * Two versions of one rule set to compare over the decisions actually made in a past period (RLE-075, RLE-077). Each
 * logged decision of the rule set in {@code [from, to)} is evaluated again, at its logged clock, through both versions.
 *
 * @param base the version the committee knows, in any status
 * @param candidate the version that would replace it, in any status; not equal to {@code base}
 * @param from the first instant, inclusive; required
 * @param to the end, exclusive; required, after {@code from} and at most {@code query.max-range} (31 days) later
 */
public record ComparisonRequest(int base, int candidate, Instant from, Instant to) {

    /**
     * Compares two versions over a period.
     *
     * @param base the version the committee knows
     * @param candidate the version that would replace it
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @return the request
     */
    public static ComparisonRequest of(int base, int candidate, Instant from, Instant to) {
        return new ComparisonRequest(base, candidate, from, to);
    }
}
