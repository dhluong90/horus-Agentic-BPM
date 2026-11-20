package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One instance as listed: no variables, tokens or tasks; read the instance for those.
 *
 * @param id instance id (ULID)
 * @param definitionKey the definition key
 * @param definitionVersion the pinned version number
 * @param businessKey the business key, or null
 * @param status {@code RUNNING}, {@code COMPLETED}, {@code CANCELLED}, {@code COMPENSATING} or {@code FAILED}
 * @param lockVersion optimistic lock version, sent as {@code If-Match} to cancel
 * @param startedBy the identity that started it
 * @param startedAt start time
 * @param endedAt end time, or null while running
 */
public record InstanceSummary(String id, String definitionKey, int definitionVersion, String businessKey,
        String status, long lockVersion, String startedBy, Instant startedAt, Instant endedAt) {

    public InstanceSummary {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
    }
}
