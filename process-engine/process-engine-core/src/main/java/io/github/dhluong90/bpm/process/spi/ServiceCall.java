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
 * @param inputs the resolved {@code horus:input} values; for an undo (#67), the forward step's inputs and its result
 *     under {@code forwardResult}
 * @param forwardAttemptId for an undo of a compensation walk (#67): the forward step's attempt id, the key its
 *     writes can be found by; null for a forward step. A local undo runs in its own transaction and its writes
 *     commit with the walk, so it needs no other key
 */
public record ServiceCall(String tenantId, String instanceId, String nodeId, String businessKey, String attemptId,
        Map<String, Object> inputs, String forwardAttemptId) {

    public ServiceCall {
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
    }

    /**
     * A forward step's call, which undoes nothing.
     *
     * @param tenantId the instance's tenant
     * @param instanceId the instance id
     * @param nodeId the step id
     * @param businessKey the business key, or null
     * @param attemptId the step's attempt id
     * @param inputs the resolved inputs
     */
    public ServiceCall(String tenantId, String instanceId, String nodeId, String businessKey, String attemptId,
            Map<String, Object> inputs) {
        this(tenantId, instanceId, nodeId, businessKey, attemptId, inputs, null);
    }
}
