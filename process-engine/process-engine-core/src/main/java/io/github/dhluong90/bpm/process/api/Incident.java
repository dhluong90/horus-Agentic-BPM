package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * An incident (#58, DESIGN section 7): a step whose job failed for good, with the token left on the step for an
 * operator. Retrying and resolving it by API follows with #68.
 *
 * @param id the incident id
 * @param instanceId the instance
 * @param nodeId the step
 * @param tokenId the token waiting on the step, or null
 * @param errorCode why the job failed, for example {@code AGENT_TIMEOUT} or {@code AGENT_SCHEMA_INVALID}
 * @param causeSummary a summary built from codes only, never from an adapter's detail
 * @param retryCount attempts after the first
 * @param status {@code OPEN} or {@code RESOLVED}
 * @param lockVersion the optimistic lock version
 * @param createdAt when it was opened
 * @param resolvedAt when it was resolved, or null
 */
public record Incident(String id, String instanceId, String nodeId, String tokenId, String errorCode,
        String causeSummary, int retryCount, String status, long lockVersion, Instant createdAt, Instant resolvedAt) {

    public Incident {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
