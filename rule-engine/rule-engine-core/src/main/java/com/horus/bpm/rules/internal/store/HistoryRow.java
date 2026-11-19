package com.horus.bpm.rules.internal.store;

import com.horus.bpm.rules.api.LifecycleAction;
import com.horus.bpm.rules.api.VersionHistoryEntry;
import com.horus.bpm.rules.api.VersionStatus;
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
 */
public record HistoryRow(String tenantId, String id, String versionId, LifecycleAction action,
        VersionStatus fromStatus, VersionStatus toStatus, String actor, String reason, long lockVersion,
        Instant occurredAt) {

    /**
     * Returns the caller's view.
     *
     * @return the entry
     */
    public VersionHistoryEntry toEntry() {
        return new VersionHistoryEntry(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt);
    }
}
