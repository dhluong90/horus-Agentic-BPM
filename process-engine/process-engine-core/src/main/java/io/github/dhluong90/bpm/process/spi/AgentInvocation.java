package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What an {@link AgentAdapter} receives for one attempt of an agent step ({@code horus:type="agentTask"}, #52): only
 * the step's declared inputs, never the whole variable set, and never the guard spec, which the engine applies to the
 * answer in the result transaction.
 *
 * <p>Two keys, for two kinds of repeat:
 * <ul>
 *   <li>{@link #modelCallKey()} is the attempt id. Key the model call, and any cache of model answers, by it: every
 *       attempt, including the one retry of an answer that broke the output schema
 *       ({@code runners.async-agent.schema-retries}), is then a fresh generation instead of the same bad answer
 *       replayed.</li>
 *   <li>{@link #idempotencyKey()} is the job id, the same for every attempt. Key any side effect outside the model by
 *       it (a document store write, a ticket, a message), so a retry never repeats one (TR-IDEM-04).</li>
 * </ul>
 *
 * <p>Evolution: the engine builds this record; a host only reads it. New components may be added in a minor release,
 * so read it through its accessors and do not construct or deconstruct it in production code. Tests build one with
 * {@code AgentInvocations.builder()} from the test-support module, which keeps compiling when a component is added.
 *
 * <p>Output schema: {@link #outputSchema()} is the step's {@code <horus:outputSchema>} with the engine's {@code mask}
 * annotations removed, so an adapter can ask its model for structured output in that shape. It is read-only and
 * informative: the engine still checks every answer against the full schema in the result transaction. The guardrail
 * rule set is never passed to the adapter.
 *
 * @param tenantId the instance's tenant
 * @param instanceId the instance id
 * @param nodeId the step id
 * @param businessKey the business key, or null
 * @param jobId the id of the step's job, the same for every attempt, and the address of the completion API
 * @param attemptId unique per attempt; a later completion quotes it. Derived from the job id, so it is not a secret
 * @param completionToken the credential a later completion of this attempt must present: 256 random bits, new for
 *     every attempt. Pass it on only over a protected channel and never log it; {@link #toString()} masks it
 * @param attempt the attempt number, from 1
 * @param deadline when the engine abandons this attempt (claim time plus the step timeout)
 * @param inputs the resolved {@code horus:input} values
 * @param outputSchema the step's output schema as a JSON object without {@code mask} annotations, deeply read-only;
 *     empty when the engine has none to give
 */
public record AgentInvocation(String tenantId, String instanceId, String nodeId, String businessKey, String jobId,
        String attemptId, String completionToken, int attempt, Instant deadline, Map<String, Object> inputs,
        Map<String, Object> outputSchema) {

    /** The engine's annotation that masks a field's value in history; never shown to the adapter. */
    private static final String MASK = "mask";

    public AgentInvocation {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(completionToken, "completionToken must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs == null ? Map.of() : inputs));
        outputSchema = withoutMask(outputSchema == null ? Map.of() : outputSchema);
    }

    /**
     * The form without an output schema, as built before the schema was passed on; kept so existing callers compile.
     *
     * @param tenantId see the record
     * @param instanceId see the record
     * @param nodeId see the record
     * @param businessKey see the record
     * @param jobId see the record
     * @param attemptId see the record
     * @param completionToken see the record
     * @param attempt see the record
     * @param deadline see the record
     * @param inputs see the record
     */
    public AgentInvocation(String tenantId, String instanceId, String nodeId, String businessKey, String jobId,
            String attemptId, String completionToken, int attempt, Instant deadline, Map<String, Object> inputs) {
        this(tenantId, instanceId, nodeId, businessKey, jobId, attemptId, completionToken, attempt, deadline, inputs,
                Map.of());
    }

    /** A deep, read-only copy of a schema node with every {@code mask} key removed. */
    private static Map<String, Object> withoutMask(Map<?, ?> schema) {
        Map<String, Object> copy = new LinkedHashMap<>();
        schema.forEach((key, value) -> {
            if (!MASK.equals(key)) {
                copy.put(String.valueOf(key), copyValue(value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return withoutMask(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(copyValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

    /**
     * The key of the model call: the attempt id, new for every attempt, so a retry asks the model again.
     *
     * @return the attempt id
     */
    public String modelCallKey() {
        return attemptId;
    }

    /**
     * The key of side effects outside the model: the job id, the same for every attempt of the step.
     *
     * @return the job id
     */
    public String idempotencyKey() {
        return jobId;
    }

    /** Masks the completion token and leaves out the input values, which may carry personal data. */
    @Override
    public String toString() {
        return "AgentInvocation[tenantId=" + tenantId + ", instanceId=" + instanceId + ", nodeId=" + nodeId + ", jobId="
                + jobId + ", attemptId=" + attemptId + ", completionToken=***, attempt=" + attempt + ", deadline="
                + deadline + ", inputs=" + inputs.keySet() + "]";
    }
}
