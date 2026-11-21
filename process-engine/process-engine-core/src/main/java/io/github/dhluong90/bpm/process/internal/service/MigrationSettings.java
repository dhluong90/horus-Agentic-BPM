package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.MigrationPlan;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings of migration plans (#74, PD-114, PD-131).
 *
 * @param approval the approval a new plan records; {@code single} is accepted by the starter only under the
 *     {@code dev} profile
 * @param dryRunSampleSize most instance ids a dry run lists per conflict and for the instances that would move,
 *     1 to {@value #MAX_SAMPLE_SIZE}; may be lowered, never raised
 * @param dryRunValidity how long an execution may rely on a dry run, at most one hour; may be lowered only
 * @param batchSize instances the runner reads at a time, 1 to {@value #MAX_BATCH_SIZE}; may be lowered only (#90)
 * @param lease how long a runner holds a plan without renewing it, {@code PT10S} to {@code PT10M}; a runner renews it
 *     after every instance, so a crashed runner's plan is taken over after this time (#90)
 * @param lockWait how long the runner waits for an instance row another transaction holds when it opens the incident of
 *     a failed instance, {@code PT1S} to {@code PT30S}; a longer wait stops the run and the next poll tries again
 */
public record MigrationSettings(MigrationPlan.Approval approval, int dryRunSampleSize, Duration dryRunValidity,
        int batchSize, Duration lease, Duration lockWait) {

    /** The engine bound of {@code migration.dry-run-sample-size}. */
    public static final int MAX_SAMPLE_SIZE = 500;
    /** The engine bound of {@code migration.dry-run-validity} (PD-114). */
    public static final Duration MAX_VALIDITY = Duration.ofHours(1);
    /** The engine bound of {@code migration.batch-size} (DESIGN section 4.3). */
    public static final int MAX_BATCH_SIZE = 100;
    /** The shortest {@code migration.lease}. */
    public static final Duration MIN_LEASE = Duration.ofSeconds(10);
    /** The longest {@code migration.lease}. */
    public static final Duration MAX_LEASE = Duration.ofMinutes(10);

    /** The shortest {@code migration.lock-wait}. */
    public static final Duration MIN_LOCK_WAIT = Duration.ofSeconds(1);
    /** The longest {@code migration.lock-wait}. */
    public static final Duration MAX_LOCK_WAIT = Duration.ofSeconds(30);

    /** Four-eyes, 100 ids, one hour, batches of 100, a two-minute lease and a five-second lock wait. */
    public static final MigrationSettings DEFAULTS = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100,
            MAX_VALIDITY);

    /**
     * Settings with the default lock wait.
     *
     * @param approval the approval a new plan records
     * @param dryRunSampleSize most ids a dry run lists
     * @param dryRunValidity how long a dry run may be relied on
     * @param batchSize instances read at a time
     * @param lease how long a runner holds a plan
     */
    public MigrationSettings(MigrationPlan.Approval approval, int dryRunSampleSize, Duration dryRunValidity,
            int batchSize, Duration lease) {
        this(approval, dryRunSampleSize, dryRunValidity, batchSize, lease, Duration.ofSeconds(5));
    }

    /**
     * Settings with the default batch size and lease.
     *
     * @param approval the approval a new plan records
     * @param dryRunSampleSize most ids a dry run lists
     * @param dryRunValidity how long a dry run may be relied on
     */
    public MigrationSettings(MigrationPlan.Approval approval, int dryRunSampleSize, Duration dryRunValidity) {
        this(approval, dryRunSampleSize, dryRunValidity, MAX_BATCH_SIZE, Duration.ofMinutes(2));
    }

    public MigrationSettings {
        Objects.requireNonNull(approval, "migration.approval");
        if (dryRunSampleSize < 1 || dryRunSampleSize > MAX_SAMPLE_SIZE) {
            throw new IllegalArgumentException("migration.dry-run-sample-size must be 1 to " + MAX_SAMPLE_SIZE
                    + ", was " + dryRunSampleSize);
        }
        Objects.requireNonNull(dryRunValidity, "migration.dry-run-validity");
        if (dryRunValidity.isNegative() || dryRunValidity.isZero() || dryRunValidity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalArgumentException("migration.dry-run-validity must be positive and at most PT1H, was "
                    + dryRunValidity);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("migration.batch-size must be 1 to " + MAX_BATCH_SIZE + ", was "
                    + batchSize);
        }
        Objects.requireNonNull(lease, "migration.lease");
        if (lease.compareTo(MIN_LEASE) < 0 || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("migration.lease must be between PT10S and PT10M, was " + lease);
        }
        Objects.requireNonNull(lockWait, "migration.lock-wait");
        if (lockWait.compareTo(MIN_LOCK_WAIT) < 0 || lockWait.compareTo(MAX_LOCK_WAIT) > 0) {
            throw new IllegalArgumentException("migration.lock-wait must be between PT1S and PT30S, was " + lockWait);
        }
    }
}
