package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored rule set version as callers see it.
 *
 * @param ruleSet the rule set name
 * @param version the version number, 1, 2, 3
 * @param status the lifecycle status
 * @param hitPolicy {@code FIRST}, {@code UNIQUE} or {@code COLLECT}
 * @param effectiveFrom when a published version takes effect; null before approval unless the document names it
 * @param lockVersion optimistic lock, returned as {@code ETag}; unrelated to {@code version}
 * @param canonicalDocument the canonical JSON (RFC 8785) of the definition
 * @param canonicalHash SHA-256 of the canonical JSON, lower-case hex
 * @param sourceFormat the format the version was imported from
 * @param sourceHash SHA-256 of the source bytes, lower-case hex
 * @param sourceDocument the source text as received; null unless requested
 * @param requestedBy who requested publication, or null
 * @param requestedAt when publication was requested, or null
 * @param approvedBy who approved, or null
 * @param approvedAt when it was approved, or null
 * @param createdBy who imported the version, or null when not recorded
 * @param createdAt when the version was created
 */
public record RuleSetVersion(
        String ruleSet,
        int version,
        VersionStatus status,
        String hitPolicy,
        Instant effectiveFrom,
        long lockVersion,
        String canonicalDocument,
        String canonicalHash,
        SourceFormat sourceFormat,
        String sourceHash,
        String sourceDocument,
        String requestedBy,
        Instant requestedAt,
        String approvedBy,
        Instant approvedAt,
        String createdBy,
        Instant createdAt) {

    public RuleSetVersion {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        Objects.requireNonNull(canonicalDocument, "canonicalDocument must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
        Objects.requireNonNull(sourceHash, "sourceHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * Returns a copy without the source document.
     *
     * @return this version without {@code sourceDocument}
     */
    public RuleSetVersion withoutSource() {
        return new RuleSetVersion(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument,
                canonicalHash, sourceFormat, sourceHash, null, requestedBy, requestedAt, approvedBy, approvedAt,
                createdBy, createdAt);
    }
}
