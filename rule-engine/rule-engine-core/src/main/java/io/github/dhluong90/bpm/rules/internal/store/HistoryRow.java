package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import java.time.Instant;

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
 */
public record HistoryRow(String tenantId, String id, String versionId, LifecycleAction action,
        VersionStatus fromStatus, VersionStatus toStatus, String actor, String reason, long lockVersion,
        Instant occurredAt, Integer supersededBy, String approvalMode, Boolean noVersionInEffect) {

    /**
     * Creates a retirement row without the PD-101 mark.
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
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            Integer supersededBy, String approvalMode) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, supersededBy,
                approvalMode, null);
    }

    /**
     * Creates a row of a transition other than a retirement.
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
                null);
    }

    /**
     * Returns the caller's view.
     *
     * @return the entry
     */
    public VersionHistoryEntry toEntry() {
        return new VersionHistoryEntry(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt,
                supersededBy, approvalMode, noVersionInEffect);
    }
}
