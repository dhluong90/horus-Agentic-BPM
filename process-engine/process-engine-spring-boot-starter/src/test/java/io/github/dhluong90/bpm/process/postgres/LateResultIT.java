package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Late results after a timeout (#55, PRE-112), on PostgreSQL with the real Rule Engine behind the decision port.
 *
 * <p>A loan application's documents are assessed by the agent {@code documents.assess}, which hands every call off and
 * answers later through the completion API with the completion token of the attempt it was given. The step declares
 * {@code timeout PT0.2S} and {@code <horus:retry max="1" backoff="PT1S"/>}, so an attempt's lease ends 30.2 s after
 * its claim and the step has two attempts. The clock is a {@link TestClock}; no test sleeps.
 */
class LateResultIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String ASSESSOR = "documents.assess";
    private static final String ASSESSOR_CALLBACK = "ASSESSOR_CALLBACK";
    /** Past the lease of one attempt: the step timeout plus the 30 s lease margin. */
    private static final Duration LEASE_ENDED = Duration.ofMillis(30_201);
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private final TestClock clock = TestClock.at(T0);
    private final String tenant = "bank-late-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();
    private final StubAgentAdapter assessor = StubAgentAdapter.of(ASSESSOR, invocation -> new AdapterResult.Accepted());

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
                .decisionPort(StarterDecisionPorts::over)
                .async(new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT,
                        AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of(ASSESSOR, Set.of(ASSESSOR_CALLBACK))))
                .agents(assessor).build();
        engines.add(engine);
        engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:step:complete");
        engine.identity.roles = Set.of(ASSESSOR_CALLBACK);
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/document-assessment-guardrails.yaml")));
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", 1,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", 1, new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
        String xml = new String(TestEngine.resource("/bpmn/loan-agent-assessment.bpmn"), StandardCharsets.UTF_8)
                .replace("horus:timeout=\"PT2S\"", "horus:timeout=\"PT0.2S\"")
                .replace("</bpmn:extensionElements>", "<horus:retry max=\"1\" backoff=\"PT1S\"/></bpmn:extensionElements>");
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                xml.getBytes(StandardCharsets.UTF_8), null));
        return engine;
    }

    private static String apply(TestEngine engine, String applicationId) {
        return engine.services.start(new StartRequest("loan-agent-assessment", applicationId,
                Map.of("applicationId", applicationId, "amount", 250_000_000L), null)).id();
    }

    /** Claims the step's attempt and calls the assessor, which hands the call off. */
    private AgentInvocation attempt(TestEngine engine) {
        List<Claim> claims = engine.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(claims).hasSize(1);
        engine.async.runNow(claims.get(0));
        List<AgentInvocation> seen = assessor.invocations();
        return seen.get(seen.size() - 1);
    }

    private static StepCompletion.Outcome report(TestEngine engine, AgentInvocation attempt, int riskScore) {
        return engine.services.complete(attempt.jobId(), attempt.attemptId(), attempt.completionToken(),
                AdapterResult.completed(Map.of("riskScore", riskScore, "recommendation", "APPROVE"))).outcome();
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT status, attempt FROM pre_timer WHERE instance_id = ?").param(instanceId)
                .query().singleRow();
    }

    /** Every row a late result could write: history, decisions, tasks and incidents of the instance. */
    private static Map<String, Object> footprint(String instanceId) {
        return TestEngine.jdbc().sql("SELECT (SELECT count(*) FROM pre_history WHERE instance_id = ?) AS history, "
                + "(SELECT count(*) FROM rle_decision WHERE correlation_id = ?) AS decisions, "
                + "(SELECT count(*) FROM pre_task WHERE instance_id = ?) AS tasks, "
                + "(SELECT count(*) FROM pre_incident WHERE instance_id = ? AND status = 'OPEN') AS incidents")
                .params(instanceId, instanceId, instanceId, instanceId).query().singleRow();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    @Test
    void PRE_112_a_late_result_of_an_attempt_taken_over_is_discarded_and_the_new_attempt_keeps_the_step() {
        // Given attempt 1 was handed off, its lease ended, and attempt 2 claimed the step
        TestEngine pod = replica();
        String loan = apply(pod, "LOAN-2027-5501");
        AgentInvocation first = attempt(pod);
        clock.advance(LEASE_ENDED);
        AgentInvocation second = attempt(pod);
        assertThat(second.modelCallKey()).isNotEqualTo(first.modelCallKey());
        assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
        Map<String, Object> before = footprint(loan);

        // When attempt 1's answer arrives late, with a risk score its guardrail would refuse
        StepCompletion.Outcome late = report(pod, first, 85);

        // Then it is discarded and writes nothing, and attempt 2 still owns the step
        assertThat(late).isEqualTo(StepCompletion.Outcome.DISCARDED);
        assertThat(footprint(loan)).isEqualTo(before);
        assertThat(job(loan)).containsEntry("status", "CLAIMED").containsEntry("attempt", 2);
        assertThat(report(pod, second, 20)).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_112_a_late_result_after_the_step_completed_never_reopens_it() {
        // Given attempt 2's answer was accepted and the loan moved on to underwrite
        TestEngine pod = replica();
        String loan = apply(pod, "LOAN-2027-5502");
        AgentInvocation first = attempt(pod);
        clock.advance(LEASE_ENDED);
        AgentInvocation second = attempt(pod);
        assertThat(report(pod, second, 20)).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        Map<String, Object> before = footprint(loan);

        // When attempt 1's answer arrives, twice
        StepCompletion.Outcome late = report(pod, first, 85);
        StepCompletion.Outcome again = report(pod, first, 85);

        // Then both are discarded: the step stays completed with attempt 2's answer, and no review task opens
        assertThat(late).isEqualTo(StepCompletion.Outcome.DISCARDED);
        assertThat(again).isEqualTo(StepCompletion.Outcome.DISCARDED);
        assertThat(footprint(loan)).isEqualTo(before);
        assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 2);
        assertThat(pod.services.instance(loan).variables()).containsEntry("assessment",
                Map.of("riskScore", 20L, "recommendation", "APPROVE"));
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_112_a_late_result_after_the_job_failed_leaves_the_incident_open_every_time() {
        // Given both attempts' leases ended, so the next claim failed the job and opened an incident
        TestEngine pod = replica();
        String loan = apply(pod, "LOAN-2027-5503");
        attempt(pod);
        clock.advance(LEASE_ENDED);
        AgentInvocation second = attempt(pod);
        clock.advance(LEASE_ENDED);
        List<Claim> exhausted = pod.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(exhausted).hasSize(1);
        assertThat(pod.async.runNow(exhausted.get(0))).isEmpty();
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(pod.services.openIncidents(loan)).singleElement()
                .satisfies(i -> assertThat(i.errorCode()).isEqualTo("MAX_ATTEMPTS_REACHED"));
        Map<String, Object> before = footprint(loan);

        // When attempt 2's valid answer arrives three times
        List<StepCompletion.Outcome> outcomes = List.of(report(pod, second, 20), report(pod, second, 20),
                report(pod, second, 20));

        // Then every report is discarded, the incident stays open and the token stays on the step
        assertThat(outcomes).containsOnly(StepCompletion.Outcome.DISCARDED);
        assertThat(footprint(loan)).isEqualTo(before).containsEntry("incidents", 1L);
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(pod.services.instance(loan).variables()).doesNotContainKey("assessment");
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
    }
}
