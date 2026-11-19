package io.github.dhluong90.bpm.rules.internal.model;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * A DMN {@code definitions} element: the decision model of one rule set version (ADR-0018, PD-060).
 *
 * @param name the rule set name
 * @param expressionLanguage the expression language URN
 * @param timeZone the zone of {@code today()} and {@code now()}
 * @param effectiveFrom the instant the version takes effect, or null for "when approved"
 * @param decisions the decisions; exactly one in schema version 1
 */
public record Definitions(
        String name, String expressionLanguage, ZoneId timeZone, Instant effectiveFrom, List<Decision> decisions) {

    public Definitions {
        decisions = List.copyOf(decisions);
    }

    /**
     * Returns the only decision of a schema version 1 model.
     *
     * @return the decision named after the rule set
     */
    public Decision decision() {
        return decisions.getFirst();
    }
}
