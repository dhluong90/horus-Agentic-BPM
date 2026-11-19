package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One transition of a version.
 *
 * @param action what happened
 * @param fromStatus the status before, or null for {@code CREATED}
 * @param toStatus the status after
 * @param actor who did it
 * @param reason the rejection or retirement reason ({@code SUPERSEDED} when superseded) or the approval comment, or
 *     null
 * @param lockVersion the lock version after the transition
 * @param occurredAt when it happened
 * @param supersededBy on a {@code RETIRED} entry of a superseded version, the version that took over; otherwise null
 * @param approvalMode on a {@code RETIRED} entry, {@code single}, {@code four-eyes} or {@code system} (superseded);
 *     otherwise null
 */
public record VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus,
        String actor, String reason, long lockVersion, Instant occurredAt, Integer supersededBy, String approvalMode) {

    public VersionHistoryEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    /**
     * Creates an entry of a transition other than a retirement.
     *
     * @param action see the record
     * @param fromStatus see the record
     * @param toStatus see the record
     * @param actor see the record
     * @param reason see the record
     * @param lockVersion see the record
     * @param occurredAt see the record
     */
    public VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus, String actor,
            String reason, long lockVersion, Instant occurredAt) {
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null);
    }
}
