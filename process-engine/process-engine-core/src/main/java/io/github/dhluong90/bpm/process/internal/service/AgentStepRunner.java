package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.util.Map;

/**
 * The runner of agent steps ({@code ASYNC_AGENT}, #52): calls the host's {@link AgentAdapter} by name. The invocation
 * keys the model call by the attempt id and side effects by the job id ({@link AgentInvocation}). Like every runner it
 * holds no engine logic and never checks the answer: {@link ProcessServices#recordAttempt} does.
 */
public final class AgentStepRunner implements AsyncStepRunner {

    private final Map<String, AgentAdapter> agents;

    /** @param agents the host's agent adapters by name */
    public AgentStepRunner(Map<String, AgentAdapter> agents) {
        this.agents = Map.copyOf(agents);
    }

    @Override
    public String jobKind() {
        return JobStore.ASYNC_AGENT;
    }

    @Override
    public AdapterResult run(String target, AdapterCall call) {
        AgentAdapter agent = agents.get(target);
        if (agent == null) {
            return new AdapterResult.Failed("AGENT_NOT_REGISTERED", "No AgentAdapter named " + target, false);
        }
        return agent.execute(invocation(call, Map.of()));
    }

    /** Passes the step's output schema, from the job's guard spec, on to the adapter without its mask annotations. */
    @Override
    public AdapterResult run(String target, AdapterCall call, Map<String, Object> payload) {
        AgentAdapter agent = agents.get(target);
        if (agent == null) {
            return new AdapterResult.Failed("AGENT_NOT_REGISTERED", "No AgentAdapter named " + target, false);
        }
        return agent.execute(invocation(call, outputSchema(payload), memory(payload)));
    }

    /**
     * @param call the executor's call for this attempt
     * @param outputSchema the step's output schema, or empty
     * @return the agent's view of it; the invocation removes the {@code mask} annotations
     */
    static AgentInvocation invocation(AdapterCall call, Map<String, Object> outputSchema) {
        return invocation(call, outputSchema, Map.of());
    }

    /**
     * @param call the executor's call for this attempt
     * @param outputSchema the step's output schema, or empty
     * @param memory the memory resolved when the step parked (#101), the same for every attempt, or empty
     * @return the agent's view of it
     */
    static AgentInvocation invocation(AdapterCall call, Map<String, Object> outputSchema, Map<String, Object> memory) {
        return new AgentInvocation(call.tenantId(), call.instanceId(), call.nodeId(), call.businessKey(), call.jobId(),
                call.attemptId(), call.completionToken(), call.attempt(), call.deadline(), call.inputs(),
                outputSchema, memory);
    }

    /** The memory stored in the job payload when the step parked; never the audit copy. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> memory(Map<String, Object> payload) {
        if (payload != null && payload.get(ProcessServices.MEMORY) instanceof Map<?, ?> memory) {
            return (Map<String, Object>) memory;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> outputSchema(Map<String, Object> payload) {
        if (payload != null && payload.get("guard") instanceof Map<?, ?> guard
                && guard.get("outputSchema") instanceof Map<?, ?> schema) {
            return (Map<String, Object>) schema;
        }
        return Map.of();
    }
}
