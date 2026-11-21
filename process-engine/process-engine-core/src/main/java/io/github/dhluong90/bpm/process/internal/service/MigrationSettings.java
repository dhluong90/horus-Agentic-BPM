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
 */
public record MigrationSettings(MigrationPlan.Approval approval, int dryRunSampleSize, Duration dryRunValidity) {

    /** The engine bound of {@code migration.dry-run-sample-size}. */
    public static final int MAX_SAMPLE_SIZE = 500;
    /** The engine bound of {@code migration.dry-run-validity} (PD-114). */
    public static final Duration MAX_VALIDITY = Duration.ofHours(1);
    /** Four-eyes, 100 ids, one hour. */
    public static final MigrationSettings DEFAULTS = new MigrationSettings(MigrationPlan.Approval.FOUR_EYES, 100,
            MAX_VALIDITY);

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
    }
}
