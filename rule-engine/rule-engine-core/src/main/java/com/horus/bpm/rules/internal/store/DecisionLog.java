package com.horus.bpm.rules.internal.store;

import java.util.Optional;

/**
 * Appends decision rows in the current transaction: one insert per decision, never an update (RLE-013), and reads one
 * back by id for replay.
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
}
