package com.horus.bpm.rules.api;

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
 */
public record VersionSummary(String ruleSet, int version, VersionStatus status, String hitPolicy,
        Instant effectiveFrom, long lockVersion, String canonicalHash, String createdBy, Instant createdAt,
        String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt) {

    public VersionSummary {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
