package com.horus.bpm.rules.internal.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appends decision rows in the current transaction: one insert per decision, never an update (RLE-013). Reads are
 * always inside one tenant.
 */
public interface DecisionLog {

    /**
     * Appends one decision.
     *
     * @param record the row
     */
    void append(DecisionRecord record);

    /**
     * Reads one decision of a tenant.
     *
     * @param tenantId the tenant
     * @param id the decision id
     * @return the row, or empty
     */
    Optional<DecisionRecord> find(String tenantId, String id);

    /**
     * Reads the decisions of one rule set in a period whose logged input has exactly a given text, newest first.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param input the input name
     * @param text the logged text, for a masked input its fingerprint
     * @param from start of the period, inclusive
     * @param to end of the period, exclusive
     * @param limit most rows returned
     * @return the rows
     */
    List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, String text, Instant from,
            Instant to, int limit);
}
