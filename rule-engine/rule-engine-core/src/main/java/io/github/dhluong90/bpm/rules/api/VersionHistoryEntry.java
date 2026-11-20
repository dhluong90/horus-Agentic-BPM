package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
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
 * @param noVersionInEffect on a {@code RETIRED} entry, whether the retirement left the rule set with no version in
 *     effect (PD-101); otherwise null
 * @param canonicalHash on {@code DRAFT_UPDATED}: the canonical hash after the replacement, else null
 * @param previousCanonicalHash on {@code DRAFT_UPDATED}: the canonical hash before it, else null
 * @param acknowledgedWarnings on {@code APPROVED}: the warnings the approver accepted, for example
 *     {@code UNMASKED_PERSONAL_DATA} on {@code nationalId} (RLE-053, PD-104); empty when nothing needed an
 *     acknowledgement or on other actions, never null
 * @param maskedByDefaultInputs on {@code APPROVED}: the inputs masked by the tenant's {@code decision-log.mask-default}
 *     rather than by their declaration at approval (PD-104); else empty, never null
 */
public record VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus,
        String actor, String reason, long lockVersion, Instant occurredAt, Integer supersededBy, String approvalMode,
        Boolean noVersionInEffect, String canonicalHash, String previousCanonicalHash,
        List<AcknowledgedWarning> acknowledgedWarnings, List<String> maskedByDefaultInputs) {

    /**
     * Creates an entry without an acknowledgement.
     *
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
    public VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus, String actor,
            String reason, long lockVersion, Instant occurredAt, Integer supersededBy, String approvalMode,
            Boolean noVersionInEffect, String canonicalHash, String previousCanonicalHash) {
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, supersededBy, approvalMode,
                noVersionInEffect, canonicalHash, previousCanonicalHash, List.of(), List.of());
    }

    /**
     * Creates an entry of a {@code DRAFT_UPDATED} transition, with its content hashes.
     *
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
    public VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus, String actor,
            String reason, long lockVersion, Instant occurredAt, String canonicalHash, String previousCanonicalHash) {
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null, null, canonicalHash,
                previousCanonicalHash);
    }

    public VersionHistoryEntry {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(toStatus, "toStatus must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        acknowledgedWarnings = acknowledgedWarnings == null ? List.of() : List.copyOf(acknowledgedWarnings);
        maskedByDefaultInputs = maskedByDefaultInputs == null ? List.of() : List.copyOf(maskedByDefaultInputs);
    }

    /**
     * Creates an entry of a transition other than a retirement or a draft update.
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
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, null, null, null, null, null);
    }

    /**
     * Creates the entry of a retirement.
     *
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
    public VersionHistoryEntry(LifecycleAction action, VersionStatus fromStatus, VersionStatus toStatus, String actor,
            String reason, long lockVersion, Instant occurredAt, Integer supersededBy, String approvalMode,
            Boolean noVersionInEffect) {
        this(action, fromStatus, toStatus, actor, reason, lockVersion, occurredAt, supersededBy, approvalMode,
                noVersionInEffect, null, null);
    }
}
