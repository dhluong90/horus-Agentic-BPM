package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the kernel needs to know about one instance: positions and variables. Tasks, jobs and subscriptions
 * are not part of it; the kernel creates and cancels them through {@link Effect}s, and the shell stores them.
 *
 * @param instanceId instance id
 * @param tenantId tenant
 * @param definitionId the pinned definition version id
 * @param businessKey business key, or null
 * @param status instance status
 * @param tokens the execution tree in creation order
 * @param variables process-level variables
 * @param sequence counter for deterministic child ids (tokens, tasks, attempts, jobs); never decreases
 */
public record InstanceState(String instanceId, String tenantId, String definitionId, String businessKey,
        InstanceStatus status, List<Token> tokens, Map<String, Object> variables, long sequence) {

    public InstanceState {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(definitionId, "definitionId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /**
     * The state of an instance before its {@link Command.Start}: no tokens, no variables.
     *
     * @param instanceId instance id
     * @param tenantId tenant
     * @param definitionId definition version id
     * @param businessKey business key or null
     * @return the empty state
     */
    public static InstanceState created(String instanceId, String tenantId, String definitionId, String businessKey) {
        return new InstanceState(instanceId, tenantId, definitionId, businessKey, InstanceStatus.RUNNING, List.of(),
                Map.of(), 0);
    }
}
