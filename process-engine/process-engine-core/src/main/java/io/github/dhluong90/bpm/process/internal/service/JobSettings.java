package io.github.dhluong90.bpm.process.internal.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Validated job settings, bound from {@code horus.bpm.processes.jobs.*} by the starter (DESIGN section 6.5).
 *
 * @param replicaId the name this replica claims jobs under; unique per running copy of the host
 * @param timerLease {@code jobs.timer-lease}: how long a claim of a timer holds it before another replica may take it
 * @param batchSize {@code jobs.batch-size}: most jobs one poll claims
 * @param maxAttempts {@code jobs.max-attempts}: claims after which a job that keeps failing is marked {@code FAILED}
 * @param retryBackoff {@code jobs.retry-backoff}: a failed firing is due again after this times the attempt number
 */
public record JobSettings(String replicaId, Duration timerLease, int batchSize, int maxAttempts, Duration retryBackoff) {

    /** Default lease of timer, SLA and effective-date jobs (ADR-0008). */
    public static final Duration DEFAULT_TIMER_LEASE = Duration.ofSeconds(60);

    public JobSettings {
        Objects.requireNonNull(replicaId, "replicaId must not be null");
        if (replicaId.isBlank() || replicaId.length() > 200) {
            throw new IllegalArgumentException("jobs.replica-id must be 1 to 200 characters");
        }
        Objects.requireNonNull(timerLease, "timerLease must not be null");
        if (timerLease.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("jobs.timer-lease must be at least 1 second");
        }
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("jobs.batch-size must be 1 to 1000");
        }
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("jobs.max-attempts must be 1 to 100");
        }
        Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        if (retryBackoff.isNegative() || retryBackoff.isZero()) {
            throw new IllegalArgumentException("jobs.retry-backoff must be positive");
        }
    }

    /**
     * @param replicaId the replica name
     * @return the defaults for that replica
     */
    public static JobSettings defaults(String replicaId) {
        return new JobSettings(replicaId, DEFAULT_TIMER_LEASE, 32, 3, Duration.ofSeconds(10));
    }

    /**
     * @param lease a new lease
     * @return a copy with it
     */
    public JobSettings withTimerLease(Duration lease) {
        return new JobSettings(replicaId, lease, batchSize, maxAttempts, retryBackoff);
    }

    /**
     * @param replica a new replica id
     * @return a copy with it
     */
    public JobSettings withReplicaId(String replica) {
        return new JobSettings(replica, timerLease, batchSize, maxAttempts, retryBackoff);
    }
}
