package com.horus.bpm.rules.api;

import java.time.Instant;

/**
 * Which decisions of the decision log to list: one rule set over a half-open period {@code [from, to)}.
 *
 * <p>The period is bounded by {@code query.max-range} (default 31 days, ADR-0015), so a search never reads more
 * than two monthly partitions of the decision log. Every field is required; a missing or out-of-range field is
 * {@code 400 QUERY_INVALID} naming it.
 *
 * @param ruleSet the rule set name
 * @param from the first instant of the period, inclusive
 * @param to the end of the period, exclusive; after {@code from}
 */
public record DecisionSearch(String ruleSet, Instant from, Instant to) {

    /**
     * Returns the search of one rule set over a period.
     *
     * @param ruleSet the rule set name
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @return the search
     */
    public static DecisionSearch of(String ruleSet, Instant from, Instant to) {
        return new DecisionSearch(ruleSet, from, to);
    }
}
