package io.github.dhluong90.bpm.process.api;

/**
 * The period of a replay holds more recorded answers than {@code replay.max-cases}. Code {@code REPLAY_TOO_LARGE}.
 */
public final class ReplayTooLargeException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final long cases;
    private final int limit;

    /**
     * @param cases the recorded answers in the period
     * @param limit {@code replay.max-cases}
     */
    public ReplayTooLargeException(long cases, int limit) {
        super("REPLAY_TOO_LARGE", "The period holds " + cases + " recorded answers; the limit is " + limit
                + " (replay.max-cases). Choose a shorter period.");
        this.cases = cases;
        this.limit = limit;
    }

    /** @return the recorded answers in the period */
    public long cases() {
        return cases;
    }

    /** @return the limit */
    public int limit() {
        return limit;
    }
}
