package io.github.dhluong90.bpm.process.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a handler receives: only the declared inputs, never the whole variable set.
 *
 * @param tenantId the instance's tenant
 * @param instanceId the instance id
 * @param nodeId the step id
 * @param businessKey the business key, or null
 * @param attemptId the step's attempt id, for correlation only. It is derived from the instance's committed state, so
 *     a firing that rolled back and runs again presents the same one. A local handler takes no idempotency key: its
 *     writes commit or roll back with the step (PRE-071)
 * @param inputs the resolved {@code horus:input} values
 */
public record ServiceCall(String tenantId, String instanceId, String nodeId, String businessKey, String attemptId,
        Map<String, Object> inputs) {

    public ServiceCall {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }
}
