package io.github.dhluong90.bpm.rules.api;

/**
 * The caller has used up its allowance of an operation that is limited per caller ({@code 429 RATE_LIMITED}), today
 * the masked-input search ({@code masking.search.max-per-minute}).
 */
public final class RateLimitedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final long retryAfterSeconds;

    /**
     * Creates the exception.
     *
     * @param detail what is limited and the limit, without any searched value
     * @param retryAfterSeconds seconds until the next call is allowed, at least 1
     */
    public RateLimitedException(String detail, long retryAfterSeconds) {
        super("RATE_LIMITED", detail);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    /**
     * Returns the seconds until the next call is allowed, for {@code Retry-After}.
     *
     * @return at least 1
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
