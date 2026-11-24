package io.github.dhluong90.bpm.process.api;

/**
 * A process that starts on several events already holds its configured number of arrivals that wait for the others.
 * Code {@code START_ARRIVALS_FULL}, answered {@code 429} with a {@code Retry-After} header: a held arrival expires
 * after {@code parallel-start.arrival-ttl}, so the same request can succeed later (AUD-10).
 */
public final class StartArrivalsFullException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    /**
     * @param message the detail
     * @param retryAfterSeconds the time after which a held arrival has expired, so a retry can be held
     */
    public StartArrivalsFullException(String message, long retryAfterSeconds) {
        super("START_ARRIVALS_FULL", message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /** @return seconds for the {@code Retry-After} header, at least 1 */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
