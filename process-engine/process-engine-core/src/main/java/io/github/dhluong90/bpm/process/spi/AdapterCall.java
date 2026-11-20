package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link ServiceAdapter} receives for one attempt of a remote step: only the declared inputs, never the whole
 * variable set.
 *
 * @param tenantId the instance's tenant
 * @param instanceId the instance id
 * @param nodeId the step id
 * @param businessKey the business key, or null
 * @param jobId the id of the step's job; with the attempt id it addresses the completion API
 * @param attemptId unique per attempt; the idempotency key of the remote call, and the key a later completion must
 *     carry
 * @param attempt the attempt number, from 1
 * @param deadline when the engine abandons this attempt (claim time plus the step timeout)
 * @param inputs the resolved {@code horus:input} values
 */
public record AdapterCall(String tenantId, String instanceId, String nodeId, String businessKey, String jobId,
        String attemptId, int attempt, Instant deadline, Map<String, Object> inputs) {

    public AdapterCall {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs == null ? Map.of() : inputs));
    }
}
