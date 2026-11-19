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
 * @param reason the rejection reason or the approval comment, or null
 * @param lockVersion the lock version after the transition
 * @param occurredAt when it happened
 */
public record VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus,
        String actor, String reason, long lockVersion, Instant occurredAt) {

    public VersionHistoryEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
