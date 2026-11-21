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
 * <p>Evolution: the engine builds this record; a host only reads it. New components may be added in a minor release,
 * so read it through its accessors and do not construct or deconstruct it in production code. Tests build one with
 * {@code AdapterCalls.builder()} from the test-support module, which keeps compiling when a component is added.
 *
 * @param tenantId the instance's tenant
 * @param instanceId the instance id
 * @param nodeId the step id
 * @param businessKey the business key, or null
 * @param jobId the id of the step's job, the same for every attempt: the idempotency key of the remote call
 *     ({@link #idempotencyKey()}, TR-IDEM-04), and the address of the completion API
 * @param attemptId unique per attempt: a correlation id that fences results of earlier attempts; a later completion
 *     quotes it. Derived from the job id, so it is not a secret
 * @param completionToken the credential a later completion of this attempt must present: 256 random bits, new for
 *     every attempt. The engine stores only its hash. Pass it to the remote side only over a protected channel, and
 *     never log it; {@link #toString()} masks it
 * @param attempt the attempt number, from 1
 * @param deadline when the engine abandons this attempt (claim time plus the step timeout)
 * @param inputs the resolved {@code horus:input} values
 */
public record AdapterCall(String tenantId, String instanceId, String nodeId, String businessKey, String jobId,
        String attemptId, String completionToken, int attempt, Instant deadline, Map<String, Object> inputs) {

    public AdapterCall {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(completionToken, "completionToken must not be null");
        Objects.requireNonNull(deadline, "deadline must not be null");
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs == null ? Map.of() : inputs));
    }

    /**
     * The key the remote side deduplicates the call by: the job id, which stays the same when the engine retries the
     * step after a timeout, a crash or a retryable failure. A retry must not repeat a side effect the remote side
     * already performed (TR-IDEM-04).
     *
     * @return the job id
     */
    public String idempotencyKey() {
        return jobId;
    }

    /** Masks the completion token and leaves out the inputs, which may carry personal data. */
    @Override
    public String toString() {
        return "AdapterCall[tenantId=" + tenantId + ", instanceId=" + instanceId + ", nodeId=" + nodeId + ", jobId="
                + jobId + ", attemptId=" + attemptId + ", completionToken=***, attempt=" + attempt + ", deadline="
                + deadline + ", inputs=" + inputs.keySet() + "]";
    }
}
