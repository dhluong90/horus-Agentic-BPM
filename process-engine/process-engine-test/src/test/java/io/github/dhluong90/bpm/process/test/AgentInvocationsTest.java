package io.github.dhluong90.bpm.process.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The test builders of the adapter records: a host's adapter test sets only what it checks. */
class AgentInvocationsTest {

    @Test
    void an_invocation_is_built_with_defaults_and_the_schema_loses_its_mask_annotations() {
        AgentInvocation invocation = AgentInvocations.builder()
                .input("applicationId", "LOAN-2027-0042")
                .attempt(2)
                .outputSchema(Map.of("type", "object", "properties",
                        Map.of("summary", Map.of("type", "string", "mask", true))))
                .build();

        assertThat(invocation.tenantId()).isEqualTo("bank-a");
        assertThat(invocation.nodeId()).isEqualTo("assess-documents");
        assertThat(invocation.attempt()).isEqualTo(2);
        assertThat(invocation.modelCallKey()).isNotEqualTo(AgentInvocations.builder().build().modelCallKey());
        assertThat(invocation.idempotencyKey()).isEqualTo(invocation.jobId());
        assertThat(invocation.inputs()).containsEntry("applicationId", "LOAN-2027-0042");
        assertThat(invocation.outputSchema().toString()).contains("summary").doesNotContain("mask");
    }

    @Test
    void a_stub_adapter_answers_a_built_invocation() {
        StubAgentAdapter assessor = StubAgentAdapter.answering("documents.assess",
                Map.of("riskScore", 20, "recommendation", "APPROVE"));

        AdapterResult result = assessor.execute(AgentInvocations.builder().build());

        assertThat(result).isInstanceOf(AdapterResult.Completed.class);
        assertThat(assessor.invocations()).hasSize(1);
    }

    @Test
    void a_service_adapter_call_is_built_with_defaults() {
        AdapterCall call = AdapterCalls.builder().tenantId("bank-b").input("customerId", "CUS-1001").build();

        assertThat(call.tenantId()).isEqualTo("bank-b");
        assertThat(call.nodeId()).isEqualTo("bureau-score");
        assertThat(call.idempotencyKey()).isEqualTo(call.jobId());
        assertThat(call.inputs()).containsEntry("customerId", "CUS-1001");
        assertThat(call.toString()).contains("completionToken=***");
    }
}
