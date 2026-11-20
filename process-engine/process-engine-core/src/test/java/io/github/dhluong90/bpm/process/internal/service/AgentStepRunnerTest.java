package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The agent runner (#52): the host's agent adapter by name, with the model call keyed by the attempt id. */
class AgentStepRunnerTest {

    private static AdapterCall call(int attempt) {
        String job = "01JQ8Z0000JOB0000000000001";
        return new AdapterCall("bank-a", "01JQ8Z0000INSTANCE00000001", "assess-documents", "LOAN-2027-0042", job,
                ProcessServices.attemptId(job, attempt), "token-" + attempt, attempt,
                Instant.parse("2027-03-01T02:00:02Z"), Map.of("applicationId", "LOAN-2027-0042",
                        "requestedAmount", 250_000_000L));
    }

    @Test
    void PRE_062_the_model_call_is_keyed_by_the_attempt_id_and_side_effects_by_the_job_id() {
        List<AgentInvocation> seen = new ArrayList<>();
        AgentAdapter assessor = new AgentAdapter() {
            @Override
            public String name() {
                return "documents.assess";
            }

            @Override
            public AdapterResult execute(AgentInvocation invocation) {
                seen.add(invocation);
                return AdapterResult.completed(Map.of("riskScore", 20, "recommendation", "APPROVE"));
            }
        };
        AgentStepRunner runner = new AgentStepRunner(Map.of("documents.assess", assessor));

        runner.run("documents.assess", call(1));
        runner.run("documents.assess", call(2));

        assertThat(runner.jobKind()).isEqualTo(JobStore.ASYNC_AGENT);
        assertThat(seen).extracting(AgentInvocation::modelCallKey).containsExactly(call(1).attemptId(),
                call(2).attemptId()).doesNotHaveDuplicates();
        assertThat(seen).extracting(AgentInvocation::idempotencyKey).containsOnly(call(1).jobId());
        assertThat(seen.get(0).inputs()).containsEntry("applicationId", "LOAN-2027-0042");
        assertThat(seen.get(0).toString()).contains("completionToken=***").doesNotContain("token-1")
                .doesNotContain("250000000");
    }

    @Test
    void an_unregistered_agent_fails_the_attempt_without_a_retry() {
        AdapterResult result = new AgentStepRunner(Map.of()).run("documents.assess", call(1));

        assertThat(result).isEqualTo(new AdapterResult.Failed("AGENT_NOT_REGISTERED",
                "No AgentAdapter named documents.assess", false));
    }
}
