package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 503 DECISION_LOG_BACKLOG}: with {@code decision-log.mode: async} the queue of decisions waiting to be written to
 * the log holds more than {@code decision-log.async.max-backlog}, so the engine refuses a new decision instead of
 * dropping one or letting the queue grow without bound (RLE-234). The evaluation is not logged and returns nothing;
 * retry after the relay has caught up.
 */
public final class DecisionLogBacklogException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final long depth;
    private final long limit;

    /**
     * Creates the exception.
     *
     * @param depth the number of queued decisions seen at the last count, at least {@code limit + 1}
     * @param limit {@code decision-log.async.max-backlog}
     */
    public DecisionLogBacklogException(long depth, long limit) {
        super("DECISION_LOG_BACKLOG", "More than " + limit + " decisions wait to be written to the decision log "
                + "(decision-log.async.max-backlog); the decision was refused, not dropped. Retry shortly");
        this.depth = depth;
        this.limit = limit;
    }

    /** @return the queue depth at the last count */
    public long depth() {
        return depth;
    }

    /** @return the limit that was exceeded */
    public long limit() {
        return limit;
    }
}
