package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A version waiting for a second person, as the pending approvals list shows it (#31, RLE-190): what the reviewer
 * needs to open it and to approve exactly what was reviewed, never the definition, the source or an input value.
 *
 * @param ruleSet the rule set name
 * @param version the version number
 * @param canonicalHash SHA-256 of the canonical JSON: the value a reviewer sends back as
 *     {@code expectedCanonicalHash} (RLE-084, PD-139)
 * @param lockVersion the optimistic lock a decision sends as {@code If-Match}
 * @param requestedBy who requested publication
 * @param requestedAt when publication was requested
 * @param allowedActions what the caller may send now, in the order {@code approve}, {@code reject},
 *     {@code withdraw}
 * @param deniedActions for {@code approve} and {@code reject}, when the caller may not send it, the reason as a code:
 *     {@code PERMISSION_REQUIRED}, {@code SELF_APPROVAL_FORBIDDEN} or {@code AUTHOR_UNKNOWN}
 * @param requestedByName the requester's readable name in the caller's language (#442), or the id when the host knows
 *     none
 * @param effectiveFrom the date the requester asked the version to take effect, or null for "when approved"
 *     (RLE-210, #470); the approver sees it before approving
 */
public record PendingApproval(String ruleSet, int version, String canonicalHash, long lockVersion,
        String requestedBy, Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions,
        String requestedByName, Instant effectiveFrom) {

    public PendingApproval {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(canonicalHash, "canonicalHash must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        allowedActions = List.copyOf(allowedActions);
        deniedActions = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(deniedActions));
        if (requestedByName == null || requestedByName.isBlank()) {
            requestedByName = requestedBy;
        }
    }

    /**
     * An item with no requested effective date, as before #470.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param canonicalHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param allowedActions see the record
     * @param deniedActions see the record
     * @param requestedByName see the record
     */
    public PendingApproval(String ruleSet, int version, String canonicalHash, long lockVersion,
            String requestedBy, Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions,
            String requestedByName) {
        this(ruleSet, version, canonicalHash, lockVersion, requestedBy, requestedAt, allowedActions, deniedActions,
                requestedByName, null);
    }

    /**
     * An item whose requester is shown by id, as before #442.
     *
     * @param ruleSet see the record
     * @param version see the record
     * @param canonicalHash see the record
     * @param lockVersion see the record
     * @param requestedBy see the record
     * @param requestedAt see the record
     * @param allowedActions see the record
     * @param deniedActions see the record
     */
    public PendingApproval(String ruleSet, int version, String canonicalHash, long lockVersion, String requestedBy,
            Instant requestedAt, List<String> allowedActions, Map<String, String> deniedActions) {
        this(ruleSet, version, canonicalHash, lockVersion, requestedBy, requestedAt, allowedActions, deniedActions,
                null);
    }
}
