package com.horus.bpm.rules.internal.store;

/** Appends decision rows in the current transaction: one insert per decision, never an update (RLE-013). */
public interface DecisionLog {

    /**
     * Appends one decision.
     *
     * @param record the row
     */
    void append(DecisionRecord record);
}
