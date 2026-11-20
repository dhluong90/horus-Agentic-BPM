package io.github.dhluong90.bpm.process.test;

import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds an {@link AgentInvocation} for a host's adapter tests. The engine builds the record in production and may add
 * components in a minor release; a test that builds it here keeps compiling. Every field has a sensible default, so a
 * test sets only what it checks.
 *
 * <pre>
 * AgentInvocation invocation = AgentInvocations.builder()
 *         .input("applicationId", "LOAN-2027-0042")
 *         .outputSchema(Map.of("type", "object"))
 *         .build();
 * </pre>
 */
public final class AgentInvocations {

    private AgentInvocations() {
    }

    /** @return a builder with defaults: tenant {@code bank-a}, step {@code assess-documents}, attempt 1 */
    public static Builder builder() {
        return new Builder();
    }

    /** A mutable builder; not thread-safe. */
    public static final class Builder {
        private String tenantId = "bank-a";
        private String instanceId = "01JQ8Z0000INSTANCE00000001";
        private String nodeId = "assess-documents";
        private String businessKey;
        private String jobId = "01JQ8Z0000JOB0000000000001";
        private String attemptId;
        private String completionToken = "test-completion-token";
        private int attempt = 1;
        private Instant deadline = Instant.parse("2027-03-01T02:00:30Z");
        private final Map<String, Object> inputs = new LinkedHashMap<>();
        private Map<String, Object> outputSchema = Map.of();

        private Builder() {
        }

        /** @param value the tenant @return this builder */
        public Builder tenantId(String value) {
            tenantId = Objects.requireNonNull(value, "tenantId");
            return this;
        }

        /** @param value the instance id @return this builder */
        public Builder instanceId(String value) {
            instanceId = value;
            return this;
        }

        /** @param value the step id @return this builder */
        public Builder nodeId(String value) {
            nodeId = value;
            return this;
        }

        /** @param value the business key, or null @return this builder */
        public Builder businessKey(String value) {
            businessKey = value;
            return this;
        }

        /** @param value the job id, the side-effect key @return this builder */
        public Builder jobId(String value) {
            jobId = Objects.requireNonNull(value, "jobId");
            return this;
        }

        /** @param value the attempt id, the model call key; defaults to the job id and the attempt @return this */
        public Builder attemptId(String value) {
            attemptId = value;
            return this;
        }

        /** @param value the completion token @return this builder */
        public Builder completionToken(String value) {
            completionToken = Objects.requireNonNull(value, "completionToken");
            return this;
        }

        /** @param value the attempt number, from 1 @return this builder */
        public Builder attempt(int value) {
            attempt = value;
            return this;
        }

        /** @param value when the engine abandons the attempt @return this builder */
        public Builder deadline(Instant value) {
            deadline = Objects.requireNonNull(value, "deadline");
            return this;
        }

        /** @param name an input name @param value its value @return this builder */
        public Builder input(String name, Object value) {
            inputs.put(name, value);
            return this;
        }

        /** @param value every input, replacing those set before @return this builder */
        public Builder inputs(Map<String, ?> value) {
            inputs.clear();
            inputs.putAll(value);
            return this;
        }

        /** @param value the step's output schema; its {@code mask} annotations are removed @return this builder */
        public Builder outputSchema(Map<String, Object> value) {
            outputSchema = Objects.requireNonNull(value, "outputSchema");
            return this;
        }

        /** @return the invocation */
        public AgentInvocation build() {
            String attemptKey = attemptId != null ? attemptId : jobId + ":" + attempt;
            return new AgentInvocation(tenantId, instanceId, nodeId, businessKey, jobId, attemptKey, completionToken,
                    attempt, deadline, inputs, outputSchema);
        }
    }
}
