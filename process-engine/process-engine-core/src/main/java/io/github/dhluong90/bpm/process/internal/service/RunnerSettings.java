package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the runner of one async job kind (#363, DESIGN sections 4 and 6.5), resolved from
 * {@code horus.bpm.processes.runners.<kind>.*} with fall-backs to the engine-wide keys, so a host that sets nothing
 * runs every kind with today's values ({@link AsyncSettings#runner}).
 *
 * @param slots calls of this kind in flight per replica; a job of this kind is claimed only when one of these is
 *     free, and no other kind can take them
 * @param defaultTimeout timeout of a step of this kind that declares none; its lease is this plus
 *     {@code jobs.lease-margin}
 * @param maxAttempts claims after which a job of this kind that keeps failing is {@code FAILED}
 * @param retryBackoff a failed attempt of this kind is due again after this times the attempt number
 * @param guardBudget longest time the result check of this kind (output schema and guardrail decision) may take inside
 *     the result transaction; a check that takes longer fails closed
 */
public record RunnerSettings(int slots, Duration defaultTimeout, int maxAttempts, Duration retryBackoff,
        Duration guardBudget) {

    /** Default {@code guard-budget}: the result check is in-process rule evaluation, which takes microseconds. */
    public static final Duration DEFAULT_GUARD_BUDGET = Duration.ofMillis(500);

    public RunnerSettings {
        if (slots < 1 || slots > 1_000) {
            throw new IllegalArgumentException("runner slots must be 1 to 1000");
        }
        Objects.requireNonNull(defaultTimeout, "defaultTimeout must not be null");
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) {
            throw new IllegalArgumentException("runner default-timeout must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("runner max-attempts must be 1 to 100");
        }
        Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        if (retryBackoff.isNegative() || retryBackoff.isZero()) {
            throw new IllegalArgumentException("runner retry-backoff must be positive");
        }
        Objects.requireNonNull(guardBudget, "guardBudget must not be null");
        if (guardBudget.isNegative() || guardBudget.isZero() || guardBudget.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("runner guard-budget must be positive and at most 10s");
        }
    }

    /**
     * What a host set for one kind; every value may be null, which means the engine-wide fall-back.
     *
     * @param slots {@code runners.<kind>.slots}, falls back to {@code adapters.executor-threads}
     * @param defaultTimeout {@code runners.<kind>.default-timeout}, falls back to {@code adapters.default-timeout}
     * @param maxAttempts {@code runners.<kind>.max-attempts}, falls back to {@code jobs.max-attempts}
     * @param retryBackoff {@code runners.<kind>.retry-backoff}, falls back to {@code jobs.retry-backoff}
     * @param guardBudget {@code runners.<kind>.guard-budget}, falls back to {@link #DEFAULT_GUARD_BUDGET}
     */
    public record Overrides(Integer slots, Duration defaultTimeout, Integer maxAttempts, Duration retryBackoff,
            Duration guardBudget) {

        /** Nothing set: every value falls back. */
        public static final Overrides NONE = new Overrides(null, null, null, null, null);
    }
}
