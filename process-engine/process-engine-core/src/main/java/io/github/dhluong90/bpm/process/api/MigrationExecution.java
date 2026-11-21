package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * The execution state of a migration plan (#90, PRE-015): who proposed it and why, who approved it, and how far the
 * runner has got. The counts are exact: every instance the runner has dealt with is one of migrated, failed or skipped.
 *
 * @param migrationId the plan id
 * @param status {@code PLANNED}, {@code PROPOSED}, {@code REJECTED}, {@code APPROVED} (waiting for the runner),
 *     {@code RUNNING}, {@code COMPLETED} or {@code COMPLETED_WITH_FAILURES}
 * @param approval the approval this plan needs, as recorded when it was created
 * @param lockVersion the version an approval or rejection names in {@code If-Match}
 * @param proposedBy who proposed the execution; null while {@code PLANNED}
 * @param proposedAt when
 * @param reason why the instances move; null while {@code PLANNED}
 * @param approvedBy who approved; the proposer in the {@code single} mode
 * @param approvedAt when
 * @param rejectedBy who rejected the proposal
 * @param rejectedAt when
 * @param rejectionReason why
 * @param total the running instances of the source version when the execution was proposed or approved
 * @param migrated instances moved to the target version
 * @param failed instances that could not be moved, each with an incident
 * @param skipped instances that had ended or moved on before the runner reached them
 * @param startedAt when the runner first took the plan
 * @param finishedAt when the last instance was dealt with
 */
public record MigrationExecution(String migrationId, String status, MigrationPlan.Approval approval, long lockVersion,
        String proposedBy, Instant proposedAt, String reason, String approvedBy, Instant approvedAt,
        String rejectedBy, Instant rejectedAt, String rejectionReason, long total, long migrated, long failed,
        long skipped, Instant startedAt, Instant finishedAt) {

    public MigrationExecution {
        Objects.requireNonNull(migrationId, "migrationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(approval, "approval");
    }
}
