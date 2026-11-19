package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The decision log: appends one row per decision in the current transaction, never an update (RLE-013), and
 * reads it back by rule set and period (RLE-029).
 */
public interface DecisionLog {

    /**
     * Appends one decision.
     *
     * @param record the row
     */
    void append(DecisionRecord record);

    /**
     * Returns the decisions of one rule set and tenant with {@code from <= createdAt < to}, newest first by
     * {@code (createdAt, id)}, strictly after the keyset position when one is given.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param from the first instant, inclusive
     * @param to the end, exclusive
     * @param after the last row of the previous page, or null for the first page
     * @param limit the most rows to return
     * @param statementTimeout the longest the read may run
     * @return the rows, at most {@code limit}
     */
    List<DecisionRecord> search(String tenantId, String ruleSet, Instant from, Instant to, Position after, int limit,
            Duration statementTimeout);

    /**
     * A keyset position in the decision log.
     *
     * @param createdAt the row time
     * @param id the decision id
     */
    record Position(Instant createdAt, String id) {
    }
}
