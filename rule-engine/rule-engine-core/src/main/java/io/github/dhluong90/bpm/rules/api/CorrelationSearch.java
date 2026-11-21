package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;

/**
 * Which decisions of the decision log to list by the host's case reference (RLE-070): every decision of the caller's
 * tenant with this correlation id, in one period of at most {@code query.max-range}, optionally narrowed to one rule
 * set. The period is half-open, {@code [from, to)}.
 *
 * @param correlationId the correlation id, 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
 * @param ruleSet the rule set name, or null for every rule set of the tenant
 * @param from the first instant of the period, inclusive
 * @param to the end of the period, exclusive
 */
public record CorrelationSearch(String correlationId, String ruleSet, Instant from, Instant to) {

    /**
     * Returns the search of every rule set of the tenant.
     *
     * @param correlationId the correlation id
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @return the search
     */
    public static CorrelationSearch of(String correlationId, Instant from, Instant to) {
        return new CorrelationSearch(correlationId, null, from, to);
    }
}
