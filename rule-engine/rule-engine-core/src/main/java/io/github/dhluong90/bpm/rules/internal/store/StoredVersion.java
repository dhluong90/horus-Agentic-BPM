package io.github.dhluong90.bpm.rules.internal.store;

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
 */
public record StoredVersion(
        String tenantId, String id, String ruleSetId, String ruleSet, int version, VersionStatus status,
        String hitPolicy, Instant effectiveFrom, String canonicalJson, String canonicalHash,
        SourceFormat sourceFormat, String sourceDocument, String sourceHash, long lockVersion,
        String requestedBy, Instant requestedAt, String approvedBy, Instant approvedAt, String approvalComment,
        String createdBy, Instant createdAt, Instant retiredAt) {

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
                approvalComment, createdBy, createdAt, null);
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
                requestedAt, approvedBy, approvedAt, createdBy, createdAt, retiredAt);
    }

    /**
     * Returns a copy moved to {@code PENDING_APPROVAL}.
     *
     * @param requester the requester
     * @param at the request time
     * @return the new state with the lock version incremented
     */
    public StoredVersion requested(String requester, Instant at) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.PENDING_APPROVAL, hitPolicy,
                effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                requester, at, null, null, null, createdBy, createdAt);
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
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.PUBLISHED, hitPolicy,
                effectiveFrom == null ? at : effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument,
                sourceHash, lockVersion + 1, requestedBy, requestedAt, approver, at, comment, createdBy, createdAt);
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
                null, null, null, null, approvalComment, createdBy, createdAt);
    }

    /**
     * Returns a copy moved to {@code RETIRED} from an instant on: selection stops choosing it for any later clock.
     *
     * @param at the transaction's clock; a retirement is never back-dated
     * @return the new state with the lock version incremented
     */
    public StoredVersion retired(Instant at) {
        return new StoredVersion(tenantId, id, ruleSetId, ruleSet, version, VersionStatus.RETIRED, hitPolicy,
                effectiveFrom, canonicalJson, canonicalHash, sourceFormat, sourceDocument, sourceHash, lockVersion + 1,
                requestedBy, requestedAt, approvedBy, approvedAt, approvalComment, createdBy, createdAt, at);
    }

    /**
     * Returns the list view.
     *
     * @return the summary
     */
    public io.github.dhluong90.bpm.rules.api.VersionSummary toSummary() {
        return new io.github.dhluong90.bpm.rules.api.VersionSummary(ruleSet, version, status, hitPolicy, effectiveFrom,
                lockVersion, canonicalHash, createdBy, createdAt, requestedBy, requestedAt, approvedBy, approvedAt);
    }
}
