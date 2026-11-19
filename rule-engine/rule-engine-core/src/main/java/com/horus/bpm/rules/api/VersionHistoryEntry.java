package com.horus.bpm.rules.api;

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
 * @param canonicalHash on {@code DRAFT_UPDATED}: the canonical hash after the replacement, else null
 * @param previousCanonicalHash on {@code DRAFT_UPDATED}: the canonical hash before it, else null
 */
public record VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus,
        String actor, String reason, long lockVersion, Instant occurredAt, String canonicalHash,
        String previousCanonicalHash) {

    /**
     * Creates an entry without content hashes.
     *
     * @param action what happened
     * @param fromStatus the status before, or null
     * @param toStatus the status after
     * @param actor who did it
     * @param reason the reason or comment, or null
     * @param lockVersion the lock version after
     * @param occurredAt when
     */
    public VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus, String actor,
            String reason, long lockVersion, Instant occurredAt) {
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null);
    }

    public VersionHistoryEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
