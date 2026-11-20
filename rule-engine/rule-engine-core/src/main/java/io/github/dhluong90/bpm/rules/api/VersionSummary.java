package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A version as a list shows it: the lifecycle fields without the definition or source.
 *
 * @param ruleSet the rule set name
 * @param version the version number
 * @param status the lifecycle status
 * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
 * @param effectiveFrom when it takes effect, or null
 * @param lockVersion the optimistic lock
 * @param canonicalHash SHA-256 of the canonical JSON
 * @param createdBy who imported it, or null when not recorded
 * @param createdAt when it was created
 * @param requestedBy who requested publication, or null
 * @param requestedAt when publication was requested, or null
 * @param approvedBy who approved it, or null
 * @param approvedAt when it was approved, or null
 * @param retireRequestedBy who requested the pending four-eyes retirement (RLE-038), or null when none is pending
 */
public record VersionSummary(String ruleSet, int version, VersionStatus status, String hitPolicy,
        Instant effectiveFrom, long lockVersion, String canonicalHash, String createdBy, Instant createdAt,
        String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt, String retireRequestedBy) {

    public VersionSummary {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Creates a summary with no pending retirement.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalHash see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     */
    public VersionSummary(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalHash, String createdBy, Instant createdAt, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalHash, createdBy, createdAt,
                requestedBy, requestedAt, approvedBy, approvedAt, null);
    }
}
