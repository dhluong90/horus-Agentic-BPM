package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code <horus:retry max="2" backoff="PT1S"/>} of an agent step (#58, process-definition.v1 {@code retry}): how often
 * a failed attempt is tried again before the step's job fails and an incident is opened. Part of the definition, so
 * the definition hash and its approval cover it.
 *
 * @param max retries after the first attempt, 0 to {@value #MAX_RETRIES}; {@code max 2} gives three attempts
 * @param backoff the delay before each retry, or null for the agent runner's {@code retry-backoff}
 */
public record RetryPolicy(int max, Duration backoff) implements HorusExtension {

    /** Highest {@code max}, as process-definition.v1 allows. */
    public static final int MAX_RETRIES = 20;

    public RetryPolicy {
        if (max < 0 || max > MAX_RETRIES) {
            throw new IllegalArgumentException("retry max must be 0 to " + MAX_RETRIES);
        }
        if (backoff != null && (backoff.isNegative() || backoff.isZero())) {
            throw new IllegalArgumentException("retry backoff must be positive");
        }
    }

    /** @return the attempts the policy allows in total: the first one and {@link #max()} retries */
    public int attempts() {
        return max + 1;
    }

}
