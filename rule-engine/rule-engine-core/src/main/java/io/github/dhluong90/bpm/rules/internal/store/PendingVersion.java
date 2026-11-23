package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Instant;

/**
 * A version waiting for approval, without its document (#31, RLE-190).
 *
 * @param id the version id, the tie-break of the keyset
 * @param ruleSet the rule set name
 * @param version the version number
 * @param canonicalHash the canonical hash
 * @param lockVersion the optimistic lock
 * @param createdBy the author, or null when not recorded
 * @param requestedBy the requester
 * @param requestedAt when publication was requested
 * @param effectiveFrom the requested effective date, or null
 */
public record PendingVersion(String id, String ruleSet, int version, String canonicalHash, long lockVersion,
        String createdBy, String requestedBy, Instant requestedAt, Instant effectiveFrom) {
}
