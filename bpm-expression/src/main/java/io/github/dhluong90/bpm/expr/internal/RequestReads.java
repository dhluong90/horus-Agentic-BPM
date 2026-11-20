package io.github.dhluong90.bpm.expr.internal;

/**
 * The characters every {@code matches} of one request may still read together
 * ({@code evaluation.regex-max-reads-per-request}, RLE-007, TR-SEC-02). A request that evaluates many decisions, such
 * as a simulation over sample inputs, shares one counter between them, so a caller cannot multiply the per-decision
 * budget by the number of items. Used by the thread serving the request only. It counts the same reads as the
 * per-match and per-decision budgets, so a request that runs out stops at the same item on every replay.
 */
public final class RequestReads {

    private final long budget;
    private long left;
    private boolean spent;

    RequestReads(long reads) {
        this.budget = reads;
        this.left = reads;
    }

    /**
     * Returns the reads left to this request.
     *
     * @return the reads left, never negative
     */
    public long left() {
        return left;
    }

    /**
     * Returns the budget this request started with.
     *
     * @return {@code evaluation.regex-max-reads-per-request}
     */
    public long budget() {
        return budget;
    }

    /**
     * Tells whether a match of this request stopped because the request's budget ran out. From then on the request
     * evaluates no further item.
     *
     * @return true once the request's budget has stopped a match
     */
    public boolean spent() {
        return spent;
    }

    /** Charges the reads one match made; never below zero. */
    void spend(long reads) {
        left = Math.max(0, left - reads);
    }

    /** Records that the request's budget stopped a match. */
    void stop() {
        spent = true;
    }
}
