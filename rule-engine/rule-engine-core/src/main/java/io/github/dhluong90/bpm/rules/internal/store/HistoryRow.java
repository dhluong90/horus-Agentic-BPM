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
 * @param canonicalHash on {@code DRAFT_UPDATED}: the canonical hash after the replacement, else null
 * @param previousCanonicalHash on {@code DRAFT_UPDATED}: the canonical hash before it, else null
 * @param acknowledgedWarnings on {@code APPROVED}: the warnings the approver accepted (RLE-053), else empty
 * @param maskedByDefaultInputs on {@code APPROVED}: the inputs masked by the tenant's {@code decision-log.mask-default}
 *     rather than by their declaration at approval (PD-104), else empty
 */
public record HistoryRow(String tenantId, String id, String versionId, LifecycleAction action,
        VersionStatus fromStatus, VersionStatus toStatus, String actor, String reason, long lockVersion,
        Instant occurredAt, String canonicalHash, String previousCanonicalHash,
        List<AcknowledgedWarning> acknowledgedWarnings, List<String> maskedByDefaultInputs) {

    public HistoryRow {
        acknowledgedWarnings = acknowledgedWarnings == null ? List.of() : List.copyOf(acknowledgedWarnings);
        maskedByDefaultInputs = maskedByDefaultInputs == null ? List.of() : List.copyOf(maskedByDefaultInputs);
    }

    /**
     * Creates a row without an acknowledgement.
     *
     * @param tenantId the tenant
     * @param id the row id
     * @param versionId the version id
     * @param action the action
     * @param fromStatus the status before, or null
     * @param toStatus the status after
     * @param actor who did it
     * @param reason the reason or comment, or null
     * @param lockVersion the lock version after
     * @param occurredAt when
     * @param canonicalHash the canonical hash after, or null
     * @param previousCanonicalHash the canonical hash before, or null
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt,
            String canonicalHash, String previousCanonicalHash) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt,
                canonicalHash, previousCanonicalHash, List.of(), List.of());
    }

    /**
     * Creates a row without content hashes, for every action except {@code DRAFT_UPDATED}.
     *
     * @param tenantId the tenant
     * @param id the row id
     * @param versionId the version id
     * @param action the action
     * @param fromStatus the status before, or null
     * @param toStatus the status after
     * @param actor who did it
     * @param reason the reason or comment, or null
     * @param lockVersion the lock version after
     * @param occurredAt when
     */
    public HistoryRow(String tenantId, String id, String versionId, LifecycleAction action, VersionStatus fromStatus,
            VersionStatus toStatus, String actor, String reason, long lockVersion, Instant occurredAt) {
        this(tenantId, id, versionId, action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null);
    }

    /**
     * Returns the caller's view.
     *
     * @return the entry
     */
    public VersionHistoryEntry toEntry() {
        return new VersionHistoryEntry(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt,
                canonicalHash, previousCanonicalHash, acknowledgedWarnings, maskedByDefaultInputs);
    }
}
