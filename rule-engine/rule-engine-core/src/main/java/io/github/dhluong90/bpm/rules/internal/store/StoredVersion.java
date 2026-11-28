package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.RetirementRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import java.time.Instant;

/**
 * A row of {@code rle_version} with its rule set name.
 *
 * @param tenantId the tenant
 * @param id the version id (ULID)
 * @param ruleSetId the rule set id (ULID)
 * @param ruleSet the rule set name
 * @param version the version number
 * @param status the status
 * @param hitPolicy the hit policy name
 * @param effectiveFrom when it takes effect, or null
 * @param canonicalJson the canonical JSON
 * @param canonicalHash its SHA-256
 * @param sourceFormat the source format
 * @param sourceDocument the source text
 * @param sourceHash SHA-256 of the source bytes
 * @param lockVersion the optimistic lock
 * @param requestedBy requester, or null
 * @param requestedAt request time, or null
 * @param approvedBy approver, or null
 * @param approvedAt approval time, or null
 * @param approvalComment approval comment, or null
 * @param createdBy who imported the version (the author), or null for rows written before it was recorded
 * @param createdAt creation time
 * @param retiredAt when it was retired, or null while it is not; selection reads it (ADR-0025 decision 3)
 * @param copiedFrom the earlier version this one restored as a draft (RLE-062), or null when it was imported
 * @param retirementRequest the pending four-eyes retirement (columns {@code retire_*}), or null; only a
 *     {@code PUBLISHED} version has one, and selection never reads it
 * @param approvalSeal the seal written at approval over the approved date, hash and approver (RLE-215), or null
 *     for a version not yet approved or approved before seals existed
 * @param legacyBackfilledAt when migration V3 scoped the pre-seal marker to this version, or null; only a version
 *     that existed before the seals has it, and only such a version may carry the marker (RLE-231)
 */
public record StoredVersion(
        String tenantId, String id, String ruleSetId, String ruleSet, int version, VersionStatus status,
        String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
        SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion,
        String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment,
        String createdBy, Instant createdAt, Instant retiredAt, Integer copiedFrom,
        RetirementRequest retirementRequest, String approvalSeal, Instant legacyBackfilledAt) {

    public StoredVersion {
        if (retirementRequest != null && status != VersionStatus.PUBLISHED) {
            throw new IllegalArgumentException("only a published version has a pending retirement, " + ruleSet + " v"
                    + version + " is " + status);
        }
    }

    /**
     * Creates a row that is not scoped to the pre-seal marker.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param copiedFrom see the record
     * @param retirementRequest see the record
     * @param approvalSeal see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt, Instant retiredAt, Integer copiedFrom, RetirementRequest retirementRequest,
            String approvalSeal) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, retiredAt, copiedFrom, retirementRequest, approvalSeal, null);
    }

    /**
     * Creates a row without an approval seal.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param copiedFrom see the record
     * @param retirementRequest see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt, Instant retiredAt, Integer copiedFrom, RetirementRequest retirementRequest) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, retiredAt, copiedFrom, retirementRequest, null);
    }

    /**
     * Returns a copy that carries a seal.
     *
     * @param seal the seal text, see {@link io.github.dhluong90.bpm.rules.internal.service}
     * @return the copy
     */
    public StoredVersion sealed(String seal) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom,
                canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy,
                requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt, copiedFrom,
                retirementRequest, seal, legacyBackfilledAt);
    }

    /** Returns this row with the seal and the pre-seal scope of {@code source}, which it was derived from. */
    private StoredVersion carryingSealOf(StoredVersion source) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom,
                canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy,
                requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt, copiedFrom,
                retirementRequest, source.approvalSeal, source.legacyBackfilledAt);
    }

    /**
     * Returns a copy of an approved version that carries a new seal; the lock version moves, nothing else does.
     *
     * @param seal the new seal text
     * @return the copy
     */
    public StoredVersion resealed(String seal) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom,
                canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1, requestedBy,
                requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt, copiedFrom,
                retirementRequest, seal, legacyBackfilledAt);
    }

    /**
     * Creates a row of a restore, which has no pending retirement.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param copiedFrom see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt, Instant retiredAt, Integer copiedFrom) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, retiredAt, copiedFrom, null);
    }

    /**
     * Creates a row that is not a restore.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     * @param retirementRequest see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt, Instant retiredAt, RetirementRequest retirementRequest) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, retiredAt, null, retirementRequest);
    }

    /**
     * Creates a row that is not a restore and has no pending retirement.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     * @param retiredAt see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt, Instant retiredAt) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, retiredAt, null, null);
    }

    /**
     * Creates a row that is not retired.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param ruleSetId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param status see the record
     * @param hitPolicy see the record
     * @param effectiveFrom see the record
     * @param canonicalJson see the record
     * @param canonicalHash see the record
     * @param sourceFormat see the record
     * @param sourceDocument see the record
     * @param sourceHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param approvedBy see the record
     * @param approvedAt see the record
     * @param approvalComment see the record
     * @param createdBy see the record
     * @param createdAt see the record
     */
    public StoredVersion(String tenantId, String id, String ruleSetId, String ruleSet, int version,
            VersionStatus status, String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
            SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion, String requestedBy,
            Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment, String createdBy,
            Instant createdAt) {
        this(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom, canonicalJson, canonicalHash,
                sourceFormat, sourceDocument, sourceHash, lockVersion, requestedBy, requestedAt, approvedBy, approvedAt,
                approvalComment, createdBy, createdAt, null, null, null);
    }

    /**
     * Returns the caller's view.
     *
     * @param includeSource whether to include the source document
     * @return the view
     */
    public RuleSetVersion toView(boolean includeSource) {
        return new RuleSetVersion(ruleSet, version, status, hitPolicy, effectiveFrom, lockVersion, canonicalJson,
                canonicalHash, sourceFormat, sourceHash, includeSource ? sourceDocument : null, requestedBy,
                requestedAt, approvedBy, approvedAt, createdBy, createdAt, retiredAt, copiedFrom, java.util.List.of(),
                retirementRequest);
    }

    /**
     * Returns a copy moved to {@code PENDING_APPROVAL}.
     *
     * @param requester the requester
     * @param at the request time
     * @return the new state with the lock version incremented
     */
    public StoredVersion requested(String requester, Instant at) {
        return requested(requester, at, effectiveFrom);
    }

    /**
     * Returns a copy moved to {@code PENDING_APPROVAL} that asks to take effect at {@code requestedEffectiveFrom}
     * (RLE-210); the date is not part of the content hash.
     *
     * @param requester the requester
     * @param at the request time
     * @param requestedEffectiveFrom the date asked for, or the date already on the version
     * @return the new state with the lock version incremented
     */
    public StoredVersion requested(String requester, Instant at, Instant requestedEffectiveFrom) {
        // PostgreSQL rounds a finer instant; the seal is computed over what is stored, so the date is cut here.
        at = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        requestedEffectiveFrom = requestedEffectiveFrom == null ? null
                : requestedEffectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.PENDING_APPROVAL, hitPolicy,
                requestedEffectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                requester, at, null, null, null, createdBy, createdAt, null, copiedFrom);
    }

    /**
     * Returns a copy moved to {@code PUBLISHED}; without a declared {@code effectiveFrom} it takes effect at approval.
     *
     * @param approver the approver
     * @param at the approval time
     * @param comment the comment, or null
     * @return the new state with the lock version incremented
     */
    public StoredVersion approved(String approver, Instant at, String comment) {
        // PostgreSQL stores microseconds and rounds a finer instant, so the seal is computed over the stored value.
        at = at.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.PUBLISHED, hitPolicy,
                effectiveFrom == null ? at
                        : effectiveFrom.truncatedTo(java.time.temporal.ChronoUnit.MICROS), canonicalJson, canonicalHash, sourceFormat, sourceDocument,
                sourceHash, lockVersion + 1, requestedBy, requestedAt, approver, at, comment, createdBy, createdAt, null,
                copiedFrom);
    }

    /**
     * Returns a copy sent back to {@code DRAFT} by a rejection; the request is cleared so the author can edit and
     * request again.
     *
     * @return the new state with the lock version incremented
     */
    public StoredVersion rejected() {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.DRAFT, hitPolicy,
                effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                null, null, null, null, approvalComment, createdBy, createdAt, null, copiedFrom);
    }

    /**
     * Returns a copy that keeps serving with a pending four-eyes retirement (RLE-038).
     *
     * @param request the request
     * @return the new state with the lock version incremented
     */
    public StoredVersion retireRequested(RetirementRequest request) {
        java.util.Objects.requireNonNull(request, "request must not be null");
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom,
                canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1, requestedBy,
                requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt, copiedFrom,
                request).carryingSealOf(this);
    }

    /**
     * Returns a copy whose pending retirement is withdrawn; the version is unchanged otherwise and keeps serving.
     *
     * @return the new state with the lock version incremented
     */
    public StoredVersion retireCancelled() {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicy, effectiveFrom,
                canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1, requestedBy,
                requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt, copiedFrom,
                null).carryingSealOf(this);
    }

    /**
     * Returns a copy moved to {@code RETIRED} from an instant on: selection stops choosing it for any later clock. A
     * pending retirement request ends with it.
     *
     * @param at the transaction's clock; a retirement is never back-dated
     * @return the new state with the lock version incremented
     */
    public StoredVersion retired(Instant at) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.RETIRED, hitPolicy,
                effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                requestedBy, requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, at,
                copiedFrom).carryingSealOf(this);
    }

    /**
     * Returns a copy moved to {@code DISCARDED} (RLE-098). Nothing is deleted: the content, hashes and author stay.
     *
     * @return the new state with the lock version incremented
     */
    public StoredVersion discarded() {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.DISCARDED, hitPolicy,
                effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                requestedBy, requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt,
                copiedFrom).carryingSealOf(this);
    }

    /**
     * Returns a copy sent back to {@code DRAFT} by its requester's withdrawal (PD-089); like a rejection, the request
     * is cleared so the author can edit and request again.
     *
     * @return the new state with the lock version incremented
     */
    public StoredVersion withdrawn() {
        return rejected();
    }

    /**
     * Returns a copy of this draft with its document replaced (RLE-047): the content columns change, the number,
     * status, author and creation time stay, and the lock version moves on.
     *
     * @param hitPolicyName the new hit policy name
     * @param newEffectiveFrom the document's {@code effectiveFrom}, or null
     * @param newCanonicalJson the new canonical JSON
     * @param newCanonicalHash its SHA-256
     * @param newSourceFormat the new source format
     * @param newSourceDocument the new source text
     * @param newSourceHash SHA-256 of the new source bytes
     * @return the new state with the lock version incremented
     */
    public StoredVersion replaced(String hitPolicyName, Instant newEffectiveFrom, String newCanonicalJson,
            String newCanonicalHash, SourceFormat newSourceFormat, String newSourceDocument, String newSourceHash) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, status, hitPolicyName, newEffectiveFrom,
                newCanonicalJson, newCanonicalHash, newSourceFormat, newSourceDocument, newSourceHash, lockVersion + 1,
                requestedBy, requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, retiredAt,
                copiedFrom);
    }

    /**
     * Returns the list view.
     *
     * @return the summary
     */
    public io.github.dhluong90.bpm.rules.api.VersionSummary toSummary() {
        return new io.github.dhluong90.bpm.rules.api.VersionSummary(ruleSet, version, status, hitPolicy, effectiveFrom,
                lockVersion, canonicalHash, createdBy, createdAt, requestedBy, requestedAt, approvedBy, approvedAt,
                retirementRequest == null ? null : retirementRequest.requestedBy());
    }
}
