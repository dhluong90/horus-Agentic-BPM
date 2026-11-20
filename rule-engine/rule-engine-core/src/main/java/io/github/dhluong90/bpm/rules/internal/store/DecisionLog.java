package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appends decision rows in the current transaction: one insert per decision, never an update (RLE-013), and reads one
 * back by id for replay and the log read. Reads are always inside one tenant.
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
     * @param decisionId the decision id (ULID)
     * @return the row, or empty when the tenant has no such decision
     */
    Optional<DecisionRecord> find(String tenantId, String decisionId);

    /**
     * Reads the decisions of one rule set in a period whose logged input has exactly one of the given texts, newest
     * first.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param input the input name
     * @param texts the logged texts, for a masked input its fingerprint under each key the tenant holds; not empty
     * @param from start of the period, inclusive
     * @param to end of the period, exclusive
     * @param limit most rows returned
     * @return the rows
     */
    List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, List<String> texts, Instant from,
            Instant to, int limit);
}
