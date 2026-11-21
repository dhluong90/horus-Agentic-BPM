package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.OutputInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Retry and resolve the incident of an agent step (#68, PRE-077, PRE-078), on PostgreSQL with the real Rule Engine
 * behind the decision port. A loan application's documents are assessed by the agent {@code documents.assess};
 * while the model is down the agent answers {@code MODEL_UNAVAILABLE}, not retryable, so the job fails on its first
 * attempt and an incident opens. The operators {@code ops.lan} and {@code ops.minh} hold
 * {@code processes:incident:manage}. The clock is a {@link TestClock}; no test sleeps.
 */
class IncidentRetryIT {

    private static final Instant T0 = Instant.parse("2027-05-04T02:00:00Z");
    private static final String ASSESSOR = "documents.assess";
    private static final String[] OPERATOR = {"processes:incident:manage", "processes:read"};
    private static final AdapterResult DOWN = new AdapterResult.Failed("MODEL_UNAVAILABLE", "503 from the model",
            false);

    private static final java.util.concurrent.atomic.AtomicInteger TENANTS = new java.util.concurrent.atomic.AtomicInteger();

    private final TestClock clock = TestClock.at(T0);
    /** One tenant per test: the Rule Engine's tables keep their rule sets between tests. */
    private final String tenant = "bank-incident-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();
    private final AtomicReference<AdapterResult> answer = new AtomicReference<>(DOWN);
    private final StubAgentAdapter assessor = StubAgentAdapter.of(ASSESSOR, invocation -> answer.get());

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    private TestEngine replica() {
        TestEngine engine = TestEngine.replica("pod-1", clock).withRuleEngine()
                .decisionPort(StarterDecisionPorts::over).agents(assessor).build();
        engines.add(engine);
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/document-assessment-guardrails.yaml")));
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", 1,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", 1, new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
        engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-agent-assessment.bpmn"), null));
        return engine;
    }

    /** Starts an application and runs its first attempt, which fails for good while the model is down. */
    private Incident failedApplication(TestEngine engine, String applicationId) {
        engine.identity.as(tenant, "pipeline", "processes:start", "processes:read");
        String loan = engine.services.start(new StartRequest("loan-agent-assessment", applicationId,
                Map.of("applicationId", applicationId, "amount", 250_000_000L), null)).id();
        assertThat(attempt(engine)).contains(StepCompletion.Outcome.ACCEPTED);
        engine.identity.as(tenant, "ops.lan", OPERATOR);
        return engine.services.openIncidents(loan).get(0);
    }

    private java.util.Optional<StepCompletion.Outcome> attempt(TestEngine engine) {
        List<Claim> claims = engine.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(claims).hasSize(1);
        return engine.async.runNow(claims.get(0));
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, attempt, max_attempts FROM pre_timer WHERE instance_id = ?")
                .param(instanceId).query().singleRow();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private static List<String> texts(String instanceId) {
        return TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE instance_id = ?").param(instanceId)
                .query(String.class).list();
    }

    @Test
    void PRE_077_a_retried_agent_step_runs_under_the_same_job_id_with_a_new_attempt_id_and_resolves_on_success() {
        // Given LOAN-2027-6601's assessment failed for good with MODEL_UNAVAILABLE on attempt 1
        TestEngine pod = replica();
        Incident incident = failedApplication(pod, "LOAN-2027-6601");
        String loan = incident.instanceId();
        assertThat(incident.errorCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 1);
        AgentInvocation first = assessor.invocations().get(0);

        // When ops.lan retries it, and repeats the request
        Incident retried = pod.services.retry(incident.id(), incident.lockVersion(), "Model back after maintenance",
                "r-6601");
        Incident replay = pod.services.retry(incident.id(), incident.lockVersion(), "Model back after maintenance",
                "r-6601");

        // Then the job is due now with one more attempt, the incident stays open with a retry in flight, and the
        // repeat replays the answer
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1).containsEntry("max_attempts", 2);
        assertThat(retried.status()).isEqualTo("OPEN");
        assertThat(retried.retryCount()).isEqualTo(1);
        assertThat(retried.retrying()).isTrue();
        assertThat(retried.allowedActions()).isEmpty();
        assertThat(replay.lockVersion()).isEqualTo(retried.lockVersion());
        // And a second retry while the attempt is due is refused
        assertThatThrownBy(() -> pod.services.retry(incident.id(), retried.lockVersion(), "Again", "r-6601-b"))
                .isInstanceOf(InvalidStateException.class);

        // When attempt 2 runs and the model answers
        answer.set(AdapterResult.completed(Map.of("riskScore", 20, "recommendation", "APPROVE")));
        assertThat(attempt(pod)).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the agent was called with the same job id, a new attempt id, and its answer passed the result check
        AgentInvocation second = assessor.invocations().get(1);
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(pod.services.incident(incident.id())).satisfies(i -> {
            assertThat(i.status()).isEqualTo("RESOLVED");
            assertThat(i.resolution()).isEqualTo("RETRY_SUCCEEDED");
        });
        assertThat(pod.events).filteredOn(e -> e.type().equals("processes.incident.resolved.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("resolution", "RETRY_SUCCEEDED"));
    }

    @Test
    void PRE_077_a_retried_agent_step_that_fails_again_keeps_its_incident_open_with_the_new_cause() {
        // Given LOAN-2027-6602's assessment failed for good, and ops.lan retried it
        TestEngine pod = replica();
        Incident incident = failedApplication(pod, "LOAN-2027-6602");
        pod.services.retry(incident.id(), incident.lockVersion(), "Model back", "r-6602");

        // When attempt 2 fails with another code
        answer.set(new AdapterResult.Failed("MODEL_QUOTA_EXCEEDED", "429", false));
        assertThat(attempt(pod)).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the same incident is open with the new cause and can be retried again
        assertThat(pod.services.openIncidents(incident.instanceId())).singleElement().satisfies(i -> {
            assertThat(i.id()).isEqualTo(incident.id());
            assertThat(i.errorCode()).isEqualTo("MODEL_QUOTA_EXCEEDED");
            assertThat(i.retrying()).isFalse();
            assertThat(i.allowedActions()).containsExactly("retry", "resolve");
        });
        assertThat(job(incident.instanceId())).containsEntry("status", "FAILED").containsEntry("attempt", 2);
    }

    @Test
    void PRE_078_an_output_that_breaks_the_steps_schema_is_refused_naming_the_path_never_the_value() {
        // Given LOAN-2027-6603's assessment failed for good
        TestEngine pod = replica();
        Incident incident = failedApplication(pod, "LOAN-2027-6603");
        int before = texts(incident.instanceId()).size();

        // When ops.lan proposes a risk score that is not a number
        assertThatThrownBy(() -> pod.services.resolve(incident.id(), incident.lockVersion(),
                Map.of("riskScore", "lots", "recommendation", "APPROVE"), "Assessed by hand", "p-6603"))
                // Then it is refused naming the path, the value is in neither the answer nor history, and nothing is
                // written
                .isInstanceOfSatisfying(OutputInvalidException.class, e -> {
                    assertThat(e.code()).isEqualTo("OUTPUT_INVALID");
                    assertThat(e.paths()).isNotEmpty().allSatisfy(p -> assertThat(p).startsWith("$.riskScore")
                            .doesNotContain("lots"));
                    assertThat(e.getMessage()).doesNotContain("lots");
                });
        assertThat(texts(incident.instanceId())).hasSize(before).noneMatch(t -> t.contains("lots"));
        assertThat(pod.services.incident(incident.id()).lockVersion()).isEqualTo(incident.lockVersion());
    }

    @Test
    void PRE_078_an_approved_agent_output_completes_the_step_and_history_keeps_it_masked() {
        // Given LOAN-2027-6604's assessment failed for good
        TestEngine pod = replica();
        Incident incident = failedApplication(pod, "LOAN-2027-6604");
        Map<String, Object> output = Map.of("riskScore", 30, "recommendation", "REFER",
                "summary", "Payslips for applicant Nguyen Van A checked by hand");

        // When ops.lan proposes the assessment she made by hand, and ops.minh approves it
        Incident proposed = pod.services.resolve(incident.id(), incident.lockVersion(), output,
                "Documents assessed by hand, model down", "p-6604");
        pod.identity.as(tenant, "ops.minh", OPERATOR);
        Incident resolved = pod.services.approveResolution(incident.id(), proposed.lockVersion(), null, "a-6604");

        // Then the step completed with her output and the loan moved on, while the proposal, the incident view and
        // history keep the free-text summary masked, as the step's output schema says
        assertThat(resolved.resolution()).isEqualTo("COMPLETED_MANUALLY");
        assertThat(tokenOn(pod, incident.instanceId())).isEqualTo("underwrite:WAITING_USER");
        assertThat(pod.services.instance(incident.instanceId()).variables()).containsKey("assessment");
        assertThat(proposed.pendingResolution().output()).containsKey("riskScore")
                .containsEntry("summary", "***");
        assertThat(texts(incident.instanceId())).noneMatch(t -> t.contains("Nguyen Van A"));
        assertThat(assessor.invocations()).hasSize(1);
    }
}
