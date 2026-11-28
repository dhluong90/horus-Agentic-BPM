package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
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
 * @param retiredAt when the version was retired, by hand or as superseded; from then on evaluation no longer selects
 *     it, while a clock before it still does. Null while it is not retired
 * @param copiedFrom the version this one restored as a draft (RLE-062), or null when it was imported
 * @param warnings what the author and the approver should see before publication, such as an undeclared input that
 *     looks like personal data (RLE-049); empty when none, never null
 * @param retirementRequest the pending retirement waiting for a second approver (RLE-038); null when none. The
 *     version is still {@code PUBLISHED} and serving while it is pending
 * @param sourceFileName the name of the file the version was imported from, or null when the import named none.
 *     Display metadata: it is outside {@code canonicalHash} and {@code sourceHash}, so importing the same file under
 *     another name gives the same hashes (RLE-228)
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
        Instant createdAt,
        Instant retiredAt,
        Integer copiedFrom,
        List<VersionWarning> warnings,
        RetirementRequest retirementRequest,
        String sourceFileName) {

    /**
     * Creates a version that was not imported from a named file.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param copiedFrom see the record
     * @param warnings see the record
     * @param retirementRequest see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt, Instant retiredAt, Integer copiedFrom,
            List<VersionWarning> warnings, RetirementRequest retirementRequest) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, retiredAt, copiedFrom, warnings, retirementRequest, null);
    }

    public RuleSetVersion {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(hitPolicy, "hitPolicy must not be null");
        Objects.requireNonNull(canonicalDocument, "canonicalDocument must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(sourceFormat, "sourceFormat must not be null");
        Objects.requireNonNull(sourceHash, "sourceHash must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Returns a copy with these warnings.
     *
     * @param newWarnings the warnings
     * @return the version
     */
    public RuleSetVersion withWarnings(List<VersionWarning> newWarnings) {
        return new RuleSetVersion(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument,
                canonicalHash, sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy,
                approvedAt, createdBy, createdAt, retiredAt, copiedFrom, newWarnings, retirementRequest, sourceFileName);
    }

    /**
     * Creates a version that is not a restore and has no pending retirement.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param warnings see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt, Instant retiredAt, List<VersionWarning> warnings) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, retiredAt, null, warnings, null);
    }

    /**
     * Creates a version with no pending retirement.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param copiedFrom see the record
     * @param warnings see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt, Instant retiredAt, Integer copiedFrom,
            List<VersionWarning> warnings) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, retiredAt, copiedFrom, warnings, null);
    }

    /**
     * Creates a version that is not a restore.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param warnings see the record
     * @param retirementRequest see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt, Instant retiredAt, List<VersionWarning> warnings,
            RetirementRequest retirementRequest) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, retiredAt, null, warnings, retirementRequest);
    }

    /**
     * Creates a version that is not retired and has no warnings.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, null, null, List.of(), null);
    }

    /**
     * Creates a version without warnings.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param lockVersion see the record
     * @param canonicalDocument see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceHash see the record
     * @param sourceDocument see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     */
    public RuleSetVersion(String ruleSet, int version, VersionStatus status, String hitPolicy, Instant effectiveFrom,
            long lockVersion, String canonicalDocument, String canonicalHash, SourceFormat sourceFormat,
            String sourceHash, String sourceDocument, String requestedBy, Instant requestedAt, String approvedBy,
            Instant approvedAt, String createdBy, Instant createdAt, Instant retiredAt) {
        this(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument, canonicalHash,
                sourceFormat, sourceHash, sourceDocument, requestedBy, requestedAt, approvedBy, approvedAt, createdBy,
                createdAt, retiredAt, null, List.of(), null);
    }

    /**
     * Returns a copy without the source document.
     *
     * @return this version without {@code sourceDocument}
     */
    public RuleSetVersion withoutSource() {
        return new RuleSetVersion(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalDocument,
                canonicalHash, sourceFormat, sourceHash, null, requestedBy, requestedAt, approvedBy, approvedAt,
                createdBy, createdAt, retiredAt, copiedFrom, warnings, retirementRequest, sourceFileName);
    }
}
