package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An incident (#58, DESIGN section 3.6): a step that failed for good, with the token left on the step for an
 * operator, who retries it or proposes a resolution that a second person approves (#68, PD-126).
 *
 * @param id the incident id
 * @param instanceId the instance
 * @param nodeId the step
 * @param tokenId the token waiting on the step, or null
 * @param errorCode why the step failed, for example {@code AGENT_TIMEOUT} or {@code UNHANDLED_BUSINESS_ERROR}
 * @param causeSummary a summary built from codes only, never from an adapter's or a handler's detail
 * @param retryCount how often it was retried, by an operator or, for a job, by its attempts after the first
 * @param status {@code OPEN}, {@code RESOLUTION_PENDING} or {@code RESOLVED}
 * @param lockVersion the optimistic lock version, sent back as {@code If-Match}
 * @param createdAt when it was opened
 * @param resolvedAt when it was resolved, or null
 * @param resolution {@code RETRY_SUCCEEDED}, {@code COMPLETED_MANUALLY} or {@code INSTANCE_CANCELLED}, or null
 * @param resolvedBy who resolved it: the approver, the canceller, or {@code system:engine} for a retry that succeeded
 * @param pendingResolution the proposal awaiting a second person, or the proposal that was applied; null when none
 * @param retrying whether a retry is in flight: the step's job is due or running again
 * @param allowedActions what the caller may do now: {@code retry}, {@code resolve}, {@code approve-resolution},
 *     {@code reject-resolution}; computed for the caller, empty without {@code processes:incident:manage}
 */
public record Incident(String id, String instanceId, String nodeId, String tokenId, String errorCode,
        String causeSummary, int retryCount, String status, long lockVersion, Instant createdAt, Instant resolvedAt,
        String resolution, String resolvedBy, PendingResolution pendingResolution, boolean retrying,
        List<String> allowedActions) {

    /** Incident status: the step waits for an operator. */
    public static final String OPEN = "OPEN";
    /** Incident status: a resolution was proposed and waits for a second person (PD-126). */
    public static final String RESOLUTION_PENDING = "RESOLUTION_PENDING";
    /** Incident status: resolved. */
    public static final String RESOLVED = "RESOLVED";

    public Incident {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(status, "status must not be null");
        allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
    }

    /**
     * An incident without resolution fields, as read before #68.
     *
     * @param id see the record
     * @param instanceId see the record
     * @param nodeId see the record
     * @param tokenId see the record
     * @param errorCode see the record
     * @param causeSummary see the record
     * @param retryCount see the record
     * @param status see the record
     * @param lockVersion see the record
     * @param createdAt see the record
     * @param resolvedAt see the record
     */
    public Incident(String id, String instanceId, String nodeId, String tokenId, String errorCode,
            String causeSummary, int retryCount, String status, long lockVersion, Instant createdAt,
            Instant resolvedAt) {
        this(id, instanceId, nodeId, tokenId, errorCode, causeSummary, retryCount, status, lockVersion, createdAt,
                resolvedAt, null, null, null, false, List.of());
    }
}
