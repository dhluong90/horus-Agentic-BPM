package com.horus.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * Finds the decisions of one rule set whose masked input had a given plain value (RLE-012). The engine fingerprints
 * the value with the caller's tenant key and searches for the fingerprint, so the plain value is never stored or
 * compared in the database. Bounded like every decision-log read (ADR-0015): one rule set, {@code from} inclusive
 * to {@code to} exclusive, at most 31 days, at most {@code query.max-page-size} decisions.
 *
 * @param ruleSet the rule set name
 * @param input the masked input name
 * @param value the plain value, as it was passed to evaluation (a {@code String}, number, {@code Boolean},
 *     {@code Money}, {@code LocalDate} or {@code Instant})
 * @param from start of the period, inclusive
 * @param to end of the period, exclusive
 * @param limit most decisions returned, 1 to {@code query.max-page-size}
 */
public record MaskedInputQuery(String ruleSet, String input, Object value, Instant from, Instant to, int limit) {

    public MaskedInputQuery {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
    }

    /**
     * The value's string form is never printed, so a query in a log line or an exception does not leak it.
     *
     * @return the rule set, input and period only
     */
    @Override
    public String toString() {
        return "MaskedInputQuery[ruleSet=" + ruleSet + ", input=" + input + ", value=(masked), from=" + from
                + ", to=" + to + ", limit=" + limit + "]";
    }
}
