package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.internal.exec.StepResult;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.service.AgentLabelLimits;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.service.RunnerSettings;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Agent step timeout, retry and incident (#58, PRE-011) and agent step history with overrides (#59, PRE-012), on
 * PostgreSQL with the real Rule Engine behind the process starter's decision port.
 *
 * <p>A loan application's documents are assessed by the agent {@code documents.assess}; the bank's
 * {@code agent-output-guardrails} rule set refuses a risk score of 70 or more (R2). The step declares
 * {@code timeout PT0.2S} and {@code <horus:retry max="2" backoff="PT1S"/>}: three attempts in all, one second apart.
 * The applicant's national id reaches the agent as an input marked {@code mask="true"}. Replicas share one
 * {@link TestClock}; no test sleeps.
 */
class AgentRetryAndHistoryIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String ASSESSOR = "documents.assess";
    private static final String ASSESSOR_CALLBACK = "ASSESSOR_CALLBACK";
    private static final String REVIEWER_ROLE = "COMPLIANCE_REVIEWER";
    private static final String DEFINITION = "/bpmn/loan-agent-assessment.bpmn";
    private static final String NATIONAL_ID = "079088001234";
    private static final MaskingKey HOST_KEY = MaskingKey.of("k1", "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8));

    /** Rule sets outlive a test on the shared database, so every test runs in a tenant of its own. */
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private final TestClock clock = TestClock.at(T0);
    private final String tenant = "bank-retry-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private static AsyncSettings settings(Integer schemaRetries) {
        return new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT,
                AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of(ASSESSOR, Set.of(ASSESSOR_CALLBACK)),
                Map.of(JobStore.ASYNC_AGENT, new RunnerSettings.Overrides(null, null, null, null, null,
                        schemaRetries)));
    }

    private TestEngine replica(StubAgentAdapter assessor, Integer schemaRetries) {
        TestEngine engine = TestEngine.replica("pod-1", clock).withRuleEngine()
                .decisionPort(StarterDecisionPorts::over).async(settings(schemaRetries)).agents(assessor)
                .inputMasker((tenantId, plain) -> HOST_KEY.forTenant(tenantId).fingerprint(plain))
                .labelLimits(AgentLabelLimits.DEFAULTS).build();
        engines.add(engine);
        pipeline(engine);
        return engine;
    }

    private void pipeline(TestEngine engine) {
        engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:step:complete");
        engine.identity.roles = Set.of(ASSESSOR_CALLBACK);
    }

    private void publishGuardrails(TestEngine engine) {
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                TestEngine.resource("/rules/document-assessment-guardrails.yaml")));
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", 1,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", 1, new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
    }

    /**
     * The loan assessment with a one-second retry policy, a 0.2 s timeout, the national id as a masked input, and
     * REJECT among the recommendations a reviewer may give.
     */
    private static String definition(boolean failOnViolation) {
        String xml = new String(TestEngine.resource(DEFINITION), StandardCharsets.UTF_8)
                .replace("horus:timeout=\"PT2S\"", "horus:timeout=\"PT0.2S\"")
                .replace("<horus:input name=\"requestedAmount\" value=\"${instance.amount}\"/>",
                        "<horus:input name=\"requestedAmount\" value=\"${instance.amount}\"/>"
                                + "<horus:input name=\"nationalId\" value=\"${instance.nationalId}\" mask=\"true\"/>")
                .replace("\"DECLINE\"]", "\"DECLINE\", \"REJECT\"]")
                .replace("</bpmn:extensionElements>", "<horus:retry max=\"2\" backoff=\"PT1S\"/>"
                        + "</bpmn:extensionElements>");
        return failOnViolation ? xml.replaceAll("onViolation=\"route-to-human\"\\s+reviewer=\"role:COMPLIANCE_REVIEWER\"",
                "onViolation=\"fail\"") : xml;
    }

    private static void deploy(TestEngine engine, boolean failOnViolation) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                definition(failOnViolation).getBytes(StandardCharsets.UTF_8), null));
    }

    private static String apply(TestEngine engine, String applicationId) {
        return engine.services.start(new StartRequest("loan-agent-assessment", applicationId,
                Map.of("applicationId", applicationId, "amount", 250_000_000L, "nationalId", NATIONAL_ID), null)).id();
    }

    private static StubAgentAdapter assessor(Function<AgentInvocation, AdapterResult> answer) {
        return StubAgentAdapter.of(ASSESSOR, answer);
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, attempt, max_attempts, next_fire_at FROM pre_timer "
                + "WHERE instance_id = ?").param(instanceId).query().singleRow();
    }

    private static List<String> failures(String instanceId) {
        return TestEngine.jdbc().sql("SELECT details->>'errorCode' || ' final=' || (details->>'final') FROM pre_history "
                + "WHERE instance_id = ? AND event_type = 'ASYNC_ATTEMPT_FAILED' ORDER BY (details->>'attempt')::int")
                .param(instanceId).query(String.class).list();
    }

    private static int history(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    private static int incidentRows(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_incident WHERE instance_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    private static Map<String, Object> details(String instanceId, String type) {
        return Values.readMap(TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE instance_id = ? "
                + "AND event_type = ?").params(instanceId, type).query(String.class).single());
    }

    private static String actor(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT actor_id FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(String.class).single();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private static String reviewTaskId(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id FROM pre_task WHERE instance_id = ? AND node_id = 'assess-documents'")
                .param(instanceId).query(String.class).single();
    }

    private static int plainNationalIdRows(String instanceId) {
        return TestEngine.jdbc().sql("SELECT (SELECT count(*) FROM pre_history WHERE instance_id = ? AND details::text "
                + "LIKE '%" + NATIONAL_ID + "%') + (SELECT count(*) FROM pre_task WHERE instance_id = ? AND "
                + "review::text LIKE '%" + NATIONAL_ID + "%')").params(instanceId, instanceId)
                .query(Integer.class).single();
    }

    private static Claim claimOne(TestEngine engine) {
        List<Claim> claims = engine.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(claims).hasSize(1);
        return claims.get(0);
    }

    /** One attempt on the adapter executor, with the step timeout, until its result transaction ended. */
    private static void attempt(TestEngine engine) throws InterruptedException {
        assertThat(engine.async.pollOnce()).isEqualTo(1);
        assertThat(engine.async.awaitIdle(Duration.ofSeconds(30))).isTrue();
    }

    // ---- PRE-011 timeout and retry, then an incident ----------------------------------------------------------

    @Test
    void PRE_011_agent_task_exhausted_retries_raises_incident() throws Exception {
        // Given the agent never answers within the step's 0.2 s, and the step retries twice, one second apart
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TestEngine pod = replica(assessor(invocation -> {
            calls.incrementAndGet();
            try {
                never.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AdapterResult.completed(Map.of("riskScore", 20, "recommendation", "APPROVE"));
        }), null);
        publishGuardrails(pod);
        deploy(pod, false);
        String loan = apply(pod, "LOAN-2027-1101");
        assertThat(job(loan)).containsEntry("max_attempts", 3);

        // When the first attempt and both retries time out
        attempt(pod);
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1);
        assertThat(pod.async.claim(JobStore.ASYNC_AGENT, 16)).isEmpty();
        clock.advance(Duration.ofSeconds(1));
        attempt(pod);
        clock.advance(Duration.ofSeconds(1));
        attempt(pod);

        // Then the step was retried twice and the third failure opened one incident, the token paused on the step
        assertThat(calls).hasValue(3);
        assertThat(failures(loan)).containsExactly("AGENT_TIMEOUT final=false", "AGENT_TIMEOUT final=false",
                "AGENT_TIMEOUT final=true");
        assertThat(job(loan)).containsEntry("status", "FAILED").containsEntry("attempt", 3);
        List<Incident> incidents = pod.services.openIncidents(loan);
        assertThat(incidents).singleElement().satisfies(i -> {
            assertThat(i.errorCode()).isEqualTo(AsyncStepExecutor.AGENT_TIMEOUT);
            assertThat(i.retryCount()).isEqualTo(2);
            assertThat(i.status()).isEqualTo("OPEN");
            assertThat(i.nodeId()).isEqualTo("assess-documents");
            assertThat(i.causeSummary()).contains("AGENT_TIMEOUT");
        });
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
        assertThat(details(loan, "INCIDENT_RAISED")).containsEntry("errorCode", "AGENT_TIMEOUT")
                .containsEntry("retryCount", 2L).containsEntry("incidentId", incidents.get(0).id());
        assertThat(pod.events).filteredOn(e -> e.type().equals("processes.incident.raised.v1")).singleElement()
                .satisfies(e -> assertThat(e.payload()).containsEntry("errorCode", "AGENT_TIMEOUT")
                        .containsEntry("instanceId", loan).containsEntry("retryCount", 2));
        // And nothing more is claimed: the operator retries or resolves it (#68)
        clock.advance(Duration.ofMinutes(10));
        assertThat(pod.async.claim(JobStore.ASYNC_AGENT, 16)).isEmpty();
    }

    @Test
    void PRE_011_agent_task_exhausted_schema_retries_raises_incident() {
        // Given a host that allows two schema retries, a step with onViolation fail, and an agent whose answer
        // breaks the output schema every time (riskScore must be an integer)
        TestEngine pod = replica(assessor(invocation -> AdapterResult.completed(Map.of("riskScore", "high",
                "recommendation", "APPROVE"))), 2);
        publishGuardrails(pod);
        deploy(pod, true);
        String loan = apply(pod, "LOAN-2027-1102");

        // When the first attempt and both schema retries are answered
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.ACCEPTED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.ACCEPTED);
        clock.advance(Duration.ofSeconds(1));
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);

        // Then the third answer is refused and one incident is open with the schema code and two retries
        assertThat(failures(loan)).containsExactly("AGENT_SCHEMA_INVALID final=false",
                "AGENT_SCHEMA_INVALID final=false");
        assertThat(details(loan, "ASYNC_RESULT_REFUSED")).containsEntry("code", "AGENT_SCHEMA_INVALID")
                .containsEntry("route", "incident");
        assertThat(pod.services.openIncidents(loan)).singleElement().satisfies(i -> {
            assertThat(i.errorCode()).isEqualTo("AGENT_SCHEMA_INVALID");
            assertThat(i.retryCount()).isEqualTo(2);
        });
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
    }

    @Test
    void PRE_011_another_tenant_can_neither_complete_the_step_nor_read_its_incidents() {
        // Given tenant bank-retry-n's loan with an open incident and a claimed attempt of another loan
        TestEngine pod = replica(assessor(invocation -> AdapterResult.completed(Map.of("riskScore", "high",
                "recommendation", "APPROVE"))), 0);
        publishGuardrails(pod);
        deploy(pod, true);
        String failed = apply(pod, "LOAN-2027-1103");
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);
        assertThat(pod.services.openIncidents(failed)).hasSize(1);
        String waiting = apply(pod, "LOAN-2027-1104");
        Claim claim = claimOne(pod);
        int before = history(waiting);

        // When another tenant's integration completes the step and reads the incidents
        pod.identity.as("bank-other", "callback", "processes:step:complete", "processes:read");
        String attemptId = ProcessServices.attemptId(claim.job().id(), claim.job().attempt());

        // Then both are refused as not found, and nothing is written
        assertThatThrownBy(() -> pod.services.complete(claim.job().id(), attemptId, claim.completionToken(),
                AdapterResult.completed(Map.of("riskScore", 10, "recommendation", "APPROVE"))))
                .isInstanceOf(AsyncStepNotFoundException.class);
        assertThatThrownBy(() -> pod.services.openIncidents(failed)).isInstanceOf(InstanceNotFoundException.class);
        assertThat(history(waiting)).isEqualTo(before);
        assertThat(incidentRows(waiting)).isZero();
        assertThat(incidentRows(failed)).isEqualTo(1);
    }

    // ---- PRE-012 masked inputs, output, guardrail decision, labels and the human override ---------------------

    @Test
    void PRE_012_agent_task_audits_masked_io_and_human_override() {
        // Given the agent recommends approval with a risk score of 85 and reports three labels, 1.5 s after the claim
        TestEngine pod = replica(assessor(invocation -> AdapterResult.completed(Map.of())), null);
        publishGuardrails(pod);
        deploy(pod, false);
        String loan = apply(pod, "LOAN-2027-1201");
        Claim claim = claimOne(pod);
        clock.advance(Duration.ofMillis(1_500));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("model", "scoring-2026-10");
        labels.put("promptVersion", "p7");
        labels.put("vendor", "in-house");
        Map<String, Object> answer = Map.of("riskScore", 85, "recommendation", "APPROVE", "summary",
                "Applicant with national id " + NATIONAL_ID + " has stable income");

        // When the answer is recorded, the guardrail refuses it, and a compliance reviewer changes the
        // recommendation from APPROVE to REJECT with a reason
        assertThat(pod.services.recordAttempt(tenant, claim.job().id(), ProcessServices.attemptId(claim.job().id(),
                claim.job().attempt()), new StepResult.Completed(answer, labels), "system:replica:pod-1"))
                .isEqualTo(StepCompletion.Outcome.REFUSED);
        String taskId = reviewTaskId(loan);
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        assertThatThrownBy(() -> pod.services.claim(taskId, 0, "starter-claims"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView task = pod.services.task(taskId);
        TaskView claimed = pod.services.claim(taskId, task.lockVersion(), "thu-claim");
        ProcessInstance after = pod.services.complete(taskId, Map.of("riskScore", 85, "recommendation", "REJECT"),
                "Salary slips do not match the declared income", claimed.lockVersion(), "thu-complete");

        // Then the refusal records the national id only as its keyed fingerprint, the masked answer, the guardrail
        // decision, the latency and the three labels as they were reported
        String fingerprint = HOST_KEY.forTenant(tenant).fingerprint(NATIONAL_ID);
        Map<String, Object> refused = details(loan, "ASYNC_RESULT_REFUSED");
        assertThat(refused).containsEntry("inputs", Map.of("applicationId", "LOAN-2027-1201", "requestedAmount",
                250_000_000L, "nationalId", fingerprint));
        assertThat(fingerprint).startsWith("hmac:k1:");
        assertThat(refused).containsEntry("output", Map.of("riskScore", 85L, "recommendation", "APPROVE",
                "summary", "***"));
        assertThat(refused.get("guardrailDecisionId")).asString().isNotBlank();
        assertThat(refused).containsEntry("ruleIds", List.of("R2")).containsEntry("latencyMs", 1_500L)
                .containsEntry("labels", Map.of("model", "scoring-2026-10", "promptVersion", "p7",
                        "vendor", "in-house"));
        assertThat(task.review()).containsEntry("inputs", refused.get("inputs"))
                .containsEntry("reportedBy", "system:replica:pod-1");
        // And the override is recorded with both identities, the reason and what changed, for offline evaluation
        Map<String, Object> override = details(loan, "HUMAN_OVERRIDE");
        assertThat(actor(loan, "HUMAN_OVERRIDE")).isEqualTo("thu");
        assertThat(override).containsEntry("reviewedBy", "thu")
                .containsEntry("refusedReportedBy", "system:replica:pod-1")
                .containsEntry("refusedAttemptId", actorAttempt(loan))
                .containsEntry("code", "GUARDRAIL_VIOLATION")
                .containsEntry("guardrailDecisionId", refused.get("guardrailDecisionId"))
                .containsEntry("reason", "Salary slips do not match the declared income")
                .containsEntry("output", Map.of("riskScore", 85L, "recommendation", "REJECT"))
                .containsEntry("changes", Map.of(
                        "recommendation", Map.of("from", "APPROVE", "to", "REJECT"),
                        "summary", mapOf("from", "***", "to", null)));
        assertThat(after.variables()).containsEntry("assessment", Map.of("riskScore", 85L, "recommendation",
                "REJECT"));
        assertThat(plainNationalIdRows(loan)).isZero();
        // The engine takes no decision from a label: the step went to review on the guardrail alone
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_012_labels_above_the_bound_fail_the_attempt_and_it_is_retried() {
        // Given an answer with 17 labels while agents.labels.max-count is 16
        TestEngine pod = replica(assessor(invocation -> AdapterResult.completed(Map.of("riskScore", 20,
                "recommendation", "APPROVE"))), null);
        publishGuardrails(pod);
        deploy(pod, false);
        String loan = apply(pod, "LOAN-2027-1202");
        Claim claim = claimOne(pod);
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 1; i <= 17; i++) {
            labels.put("label" + i, "v" + i);
        }

        // When it is recorded
        StepCompletion.Outcome outcome = pod.services.recordAttempt(tenant, claim.job().id(),
                ProcessServices.attemptId(claim.job().id(), claim.job().attempt()),
                new StepResult.Completed(Map.of("riskScore", 20, "recommendation", "APPROVE"), labels),
                "system:replica:pod-1");

        // Then the attempt failed with AGENT_LABELS_INVALID and is due again a second later, like an invalid answer
        assertThat(outcome).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(failures(loan)).containsExactly("AGENT_LABELS_INVALID final=false");
        assertThat(job(loan)).containsEntry("status", "DUE");
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND details::text "
                + "LIKE '%label17%'").param(loan).query(Integer.class).single()).isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_012_another_tenant_cannot_read_or_complete_the_review_and_nothing_is_written() {
        // Given a refused answer waiting for its compliance review
        TestEngine pod = replica(assessor(invocation -> AdapterResult.completed(Map.of("riskScore", 85,
                "recommendation", "APPROVE"))), null);
        publishGuardrails(pod);
        deploy(pod, false);
        String loan = apply(pod, "LOAN-2027-1203");
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);
        String taskId = reviewTaskId(loan);
        int before = history(loan);

        // When a reviewer of another tenant reads, claims and completes it
        pod.identity.as("bank-other", "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);

        // Then every call is refused as not found, and no override is written
        assertThatThrownBy(() -> pod.services.task(taskId)).isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> pod.services.claim(taskId, 0, "other-claim"))
                .isInstanceOf(TaskNotFoundException.class);
        assertThatThrownBy(() -> pod.services.complete(taskId, Map.of("riskScore", 85, "recommendation", "REJECT"),
                "not mine", 1, "other-complete")).isInstanceOf(TaskNotFoundException.class);
        assertThat(history(loan)).isEqualTo(before);
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'HUMAN_OVERRIDE'").param(loan).query(Integer.class).single()).isZero();
    }

    private static String actorAttempt(String instanceId) {
        return TestEngine.jdbc().sql("SELECT attempt_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'ASYNC_RESULT_REFUSED'").param(instanceId).query(String.class).single();
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
