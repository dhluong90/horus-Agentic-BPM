package io.github.dhluong90.bpm.expr.internal;

/**
 * The characters every {@code matches} of one evaluation may still read together
 * ({@code evaluation.regex-max-reads-per-decision}, RLE-007, TR-SEC-02). One counter per evaluation, used by the
 * evaluating thread only; it counts the same reads as the per-match budget, so a decision that runs out replays the
 * same on every machine.
 */
public final class DecisionReads {

    private long left;

    DecisionReads(long reads) {
        this.left = reads;
    }

    /**
     * Returns the reads left to this decision.
     *
     * @return the reads left, never negative
     */
    public long left() {
        return left;
    }

    /** Charges the reads one match made; never below zero. */
    void spend(long reads) {
        left = Math.max(0, left - reads);
    }
}
