package io.github.dhluong90.bpm.rules.internal.expr;

/**
 * The characters every {@code matches} of one evaluation may still read together
 * ({@code evaluation.regex-max-reads-per-decision}, RLE-007, TR-SEC-02). One counter per evaluation, used by the
 * evaluating thread only; it counts the same reads as the per-match budget, so a decision that runs out replays the
 * same on every machine. A decision that belongs to a request of many decisions also charges the request's counter
 * ({@code evaluation.regex-max-reads-per-request}).
 */
public final class DecisionReads {

    private long left;

    private final RequestReads request;

    DecisionReads(long reads) {
        this(reads, null);
    }

    DecisionReads(long reads, RequestReads request) {
        this.left = reads;
        this.request = request;
    }

    /**
     * Returns the reads left to this decision itself, not counting its request.
     *
     * @return the reads left, never negative
     */
    public long left() {
        return left;
    }

    /** The request this decision belongs to, or null for a decision on its own. */
    RequestReads request() {
        return request;
    }

    /** Charges the reads one match made to the decision and its request; never below zero. */
    void spend(long reads) {
        left = Math.max(0, left - reads);
        if (request != null) {
            request.spend(reads);
        }
    }
}
