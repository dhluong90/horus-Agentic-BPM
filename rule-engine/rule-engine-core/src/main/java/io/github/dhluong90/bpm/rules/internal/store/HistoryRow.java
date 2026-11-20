package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import java.time.Instant;
import java.util.List;

/**
 * A row of {@code rle_version_history}: one transition of one version, appended in the transition's transaction.
 *
 * @param tenantId the tenant
 * @param id the row id (ULID)
 * @param versionId the version id
 * @param action the transition
 * @param fromStatus the status before, or null for {@code CREATED}
 * @param toStatus the status after
 * @param actor who made it
 * @param reason the rejection reason or approval comment, or null
 * @param lockVersion the lock version after the transition
 * @param occurredAt when it happened
 * @param supersededBy on a {@code RETIRED} row of a superseded version, the version that took over; otherwise null
 * @param approvalMode on a {@code RETIRED} row, {@code single}, {@code four-eyes} or {@code system}; otherwise null
 * @param noVersionInEffect on a {@code RETIRED} row, whether the retirement left no version in effect (PD-101);
 *     otherwise null
 * @param canonicalHash on a {@code DRAFT_UPDATED} row, the canonical hash after the replacement; otherwise null
 * @param previousCanonicalHash on a {@code DRAFT_UPDATED} row, the canonical hash before it; otherwise null
 * @param acknowledgedWarnings on {@code APPROVED}: the warnings the approver accepted (RLE-053), else empty
 * @param maskedByDefaultInputs on {@code APPROVED}: the inputs masked by the tenant's {@code decision-log.mask-default}
 *     rather than by their declaration at approval (PD-104), else empty
 * @param copiedFrom on the {@code CREATED} row of a restore, the earlier version it copied (RLE-062); otherwise null
 */
public record HistoryRow(String tenantId, String id, String versionId, LifecycleAction action,
        VersionStatus fromStatus, VersionStatus toStatus, String actor, String reason, long lockVersion,
        Instant occurredAt, Integer supersededBy, String approvalMode, Boolean noVersionInEffect, String canonicalHash,
        String previousCanonicalHash, List<AcknowledgedWarning> acknowledgedWarnings, List<String> maskedByDefaultInputs,
        Integer copiedFrom) {

    /**
     * Creates a row that is not the creation of a restore.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param versionId see the record
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     * @param supersededBy see the record
     * @param approvalMode see the record
     * @param noVersionInEffect see the record
     * @param canonicalHash see the record
     * @param previousCanonicalHash see the record
     * @param acknowledgedWarnings see the record
     * @param maskedByDefaultInputs see the record
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            Integer supersededBy, String approvalMode, Boolean noVersionInEffect, String canonicalHash,
            String previousCanonicalHash, List<AcknowledgedWarning> acknowledgedWarnings,
            List<String> maskedByDefaultInputs) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt,
                supersededBy, approvalMode, noVersionInEffect, canonicalHash, previousCanonicalHash,
                acknowledgedWarnings, maskedByDefaultInputs, null);
    }

    public HistoryRow {
        acknowledgedWarnings = acknowledgedWarnings == null ? List.of() : List.copyOf(acknowledgedWarnings);
        maskedByDefaultInputs = maskedByDefaultInputs == null ? List.of() : List.copyOf(maskedByDefaultInputs);
    }

    /**
     * Creates a row without an acknowledgement.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param versionId see the record
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     * @param supersededBy see the record
     * @param approvalMode see the record
     * @param noVersionInEffect see the record
     * @param canonicalHash see the record
     * @param previousCanonicalHash see the record
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            Integer supersededBy, String approvalMode, Boolean noVersionInEffect, String canonicalHash,
            String previousCanonicalHash) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, supersededBy,
                approvalMode, noVersionInEffect, canonicalHash, previousCanonicalHash, List.of(), List.of());
    }

    /**
     * Creates the row of a retirement.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param versionId see the record
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     * @param supersededBy see the record
     * @param approvalMode see the record
     * @param noVersionInEffect see the record
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            Integer supersededBy, String approvalMode, Boolean noVersionInEffect) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, supersededBy,
                approvalMode, noVersionInEffect, null, null);
    }

    /**
     * Creates the row of a {@code DRAFT_UPDATED} transition, with its content hashes.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param versionId see the record
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     * @param canonicalHash see the record
     * @param previousCanonicalHash see the record
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            String canonicalHash, String previousCanonicalHash) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null,
                null, canonicalHash, previousCanonicalHash);
    }

    /**
     * Creates a row of any other transition.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param versionId see the record
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null,
                null, null, null);
    }

    /**
     * Returns the caller's view.
     *
     * @return the entry
     */
    public VersionHistoryEntry toEntry() {
        return new VersionHistoryEntry(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt,
                supersededBy, approvalMode, noVersionInEffect, canonicalHash, previousCanonicalHash, acknowledgedWarnings,
                maskedByDefaultInputs, copiedFrom);
    }
}
