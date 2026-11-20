package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.VersionStatus;
import java.time.Instant;

/**
 * The lifecycle columns of one version, without its document, for the rule set history and the evidence export.
 *
 * @param id the version id
 * @param version the version number
 * @param status the status
 * @param effectiveFrom when it takes effect, or null
 * @param retiredAt when it was retired, or null
 * @param canonicalHash the canonical hash
 * @param createdBy the author, or null
 * @param createdAt when it was created
 * @param requestedBy the latest requester, or null
 * @param requestedAt when, or null
 * @param approvedBy the approver, or null
 * @param approvedAt when, or null
 * @param approvalComment the approval comment, or null
 */
public record HistoryVersion(String id, int version, VersionStatus status, Instant effectiveFrom, Instant retiredAt,
        String canonicalHash, String createdBy, Instant createdAt, String requestedBy, Instant requestedAt,
        String approvedBy, Instant approvedAt, String approvalComment) {
}
