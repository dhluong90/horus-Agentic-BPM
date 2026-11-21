package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 422 HISTORY_TOO_LARGE}: the period holds more logged decisions than one comparison reads (RLE-077, PD-124).
 * The comparison is refused, never sampled, so a total is never partial; the caller narrows the period.
 */
public final class HistoryTooLargeException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final long found;
    private final boolean atLeast;
    private final int limit;

    /**
     * Creates the exception.
     *
     * @param ruleSet the rule set name
     * @param found the decisions the period holds, or the most the engine counted when {@code atLeast}
     * @param atLeast whether the count stopped at the ceiling and the period may hold more
     * @param limit {@code simulation.max-history-decisions}
     */
    public HistoryTooLargeException(String ruleSet, long found, boolean atLeast, int limit) {
        super("HISTORY_TOO_LARGE", "The period holds " + (atLeast ? "more than " : "") + found + " logged decisions of "
                + ruleSet + ", and one comparison reads at most " + limit + " (simulation.max-history-decisions). "
                + "Narrow the period; the comparison never takes a sample");
        this.found = found;
        this.atLeast = atLeast;
        this.limit = limit;
    }

    /**
     * Returns the decisions counted.
     *
     * @return the count
     */
    public long found() {
        return found;
    }

    /**
     * Returns whether the period may hold more than {@link #found()}.
     *
     * @return true when the count stopped at the ceiling
     */
    public boolean atLeast() {
        return atLeast;
    }

    /**
     * Returns the setting in force.
     *
     * @return {@code simulation.max-history-decisions}
     */
    public int limit() {
        return limit;
    }
}
