package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepRunner;
import io.github.dhluong90.bpm.process.internal.service.JsonSchemaSubsetValidator;
import io.github.dhluong90.bpm.process.internal.service.RunnerSettings;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One runner per async job kind, and the result check in the result transaction (#363), on PostgreSQL with the real
 * Rule Engine behind the process starter's decision port, the only route from the result check to the Rule Engine
 * (#336). A loan application's documents are assessed by an automated assessor. Its answer must be
 * {@code { riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE }}, and the bank's
 * {@code agent-output-guardrails} rule set refuses a risk score of 70 or more (R2). A refused answer goes to a
 * compliance reviewer.
 *
 * <p>These tests check the result transaction itself, whatever wrote the job: each parks the assessment as a remote
 * step and turns its job into an agent job ({@code ASYNC_AGENT}) with a guard spec of its choosing, including specs the
 * agent step never writes (none, or {@code fourEyes: false}); a stub agent runner answers it. The agent step, which
 * writes the spec from the published definition, is covered by {@code AgentTaskIT} (#52). Replicas share one
 * {@link TestClock}; no test sleeps.
 */
class RunnerPerKindIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String ASSESSOR = "documents.assess";
    private static final String BUREAU = "creditBureau.score";
    /** The role of the assessor's callback client. */
    private static final String ASSESSOR_CALLBACK = "ASSESSOR_CALLBACK";
    private static final String REVIEWER_ROLE = "COMPLIANCE_REVIEWER";
    private static final String GUARD = """
            {"outputSchema": {"type": "object", "required": ["riskScore", "recommendation"],
               "additionalProperties": false,
               "properties": {"riskScore": {"type": "integer", "minimum": 0, "maximum": 100},
                              "recommendation": {"type": "string", "enum": ["APPROVE", "REFER", "DECLINE"]}}},
             "decision": "agent-output-guardrails", "onViolation": "route-to-human",
             "reviewer": "role:COMPLIANCE_REVIEWER"}""";

    /** Rule sets outlive a test on the shared database, so every test runs in a tenant of its own. */
    private static final java.util.concurrent.atomic.AtomicInteger TENANTS = new java.util.concurrent.atomic.AtomicInteger();

    private final TestClock clock = TestClock.at(T0);
    private final String tenant = "bank-runner-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();
    /** Every call the assessor received, in order. */
    private final List<AdapterCall> assessorCalls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    /** The stub agent runner: records the call and answers with the function. */
    private AsyncStepRunner assessor(Function<AdapterCall, AdapterResult> answer) {
        return new AsyncStepRunner() {
            @Override
            public String jobKind() {
                return JobStore.ASYNC_AGENT;
            }

            @Override
            public AdapterResult run(String target, AdapterCall call) {
                assessorCalls.add(call);
                return answer.apply(call);
            }
        };
    }

    private static ServiceAdapter bureau(Function<AdapterCall, AdapterResult> answer) {
        return new ServiceAdapter() {
            @Override
            public String name() {
                return BUREAU;
            }

            @Override
            public AdapterResult execute(AdapterCall call) {
                return answer.apply(call);
            }
        };
    }

    private static AsyncSettings settings(Map<String, RunnerSettings.Overrides> runners) {
        return new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT,
                AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of(ASSESSOR, Set.of(ASSESSOR_CALLBACK)), runners);
    }

    private TestEngine replica(String id, AsyncStepRunner agent) {
        return replica(TestEngine.replica(id, clock).withRuleEngine().decisionPort(StarterDecisionPorts::over), agent, Map.of());
    }

    private TestEngine replica(TestEngine.Builder builder, AsyncStepRunner agent,
            Map<String, RunnerSettings.Overrides> runners) {
        TestEngine engine = builder.async(settings(runners)).adapters(bureau(call -> score(712)))
                .runners(Set.of(ASSESSOR), agent).build();
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
                TestEngine.resource("/rules/agent-output-guardrails.yaml")));
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", 1,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", 1, new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
    }

    private static void deploy(TestEngine engine) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-document-assessment.bpmn"), null));
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-bureau-check.bpmn"), null));
    }

    /** A loan reaches the assessment; its job becomes an agent job with the given guard spec. */
    private static String assess(TestEngine engine, String applicationId, String guardJson) {
        String id = engine.services.start(new StartRequest("loan-document-assessment", applicationId,
                Map.of("applicationId", applicationId), null)).id();
        TestEngine.jdbc().sql("UPDATE pre_timer SET job_kind = 'ASYNC_AGENT', payload = payload || "
                        + "jsonb_build_object('guard', ?::jsonb) WHERE instance_id = ?")
                .params(guardJson, id).update();
        return id;
    }

    private static String assess(TestEngine engine, String applicationId) {
        return assess(engine, applicationId, GUARD);
    }

    private static String bureauCheck(TestEngine engine, String applicationId) {
        return engine.services.start(new StartRequest("loan-bureau-check", applicationId,
                Map.of("nationalId", "079088001234", "amount", 250_000_000), null)).id();
    }

    private static AdapterResult answer(Object riskScore, String recommendation) {
        return AdapterResult.completed(Map.of("riskScore", riskScore, "recommendation", recommendation));
    }

    private static AdapterResult score(int value) {
        return AdapterResult.completed(Map.of("score", value, "band", value >= 700 ? "A" : "C"));
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, attempt, job_kind, max_attempts, next_fire_at FROM pre_timer "
                + "WHERE instance_id = ?").param(instanceId).query().singleRow();
    }

    private static int history(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(Integer.class).single();
    }

    private static String details(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(String.class).single();
    }

    private static String detail(String instanceId, String type, String path) {
        return TestEngine.jdbc().sql("SELECT details #>> ?::text[] FROM pre_history WHERE instance_id = ? "
                + "AND event_type = ?").params("{" + path + "}", instanceId, type).query(String.class).single();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private static int decisions(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM rle_decision WHERE correlation_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    private static Map<String, Object> reviewTask(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, node_id, assignee_role, four_eyes, status FROM pre_task "
                + "WHERE instance_id = ?").param(instanceId).query().singleRow();
    }

    private static int tasks(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_task WHERE instance_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    /** The asserted state of a result that was refused: nothing of it was accepted. */
    private static void nothingAccepted(TestEngine engine, String loan) {
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isZero();
        assertThat(engine.services.instance(loan).variables()).doesNotContainKey("assessment");
    }

    // ---- PRE-057 runner per kind -----------------------------------------------------------------------------

    @Test
    void PRE_057_a_backlog_of_one_kind_never_takes_the_slots_or_claims_of_another() throws Exception {
        // Given 2 assessor slots, an assessor that holds every call, 5 loans waiting for it and 1 for the bureau
        CountDownLatch release = new CountDownLatch(1);
        TestEngine pod = replica(TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over), assessor(call -> {
            try {
                return release.await(30, TimeUnit.SECONDS) ? answer(20, "APPROVE")
                        : new AdapterResult.Failed("TEST_STALLED", "never released", false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AdapterResult.Failed("INTERRUPTED", "interrupted", true);
            }
        }), Map.of(JobStore.ASYNC_AGENT, new RunnerSettings.Overrides(2, null, null, null, null, null)));
        publishGuardrails(pod);
        deploy(pod);
        List<String> assessments = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            assessments.add(assess(pod, "LOAN-2027-07" + i));
        }
        String bureauLoan = bureauCheck(pod, "LOAN-2027-0800");

        // When the replica polls while the assessor holds its two calls
        int started = pod.async.pollOnce();
        int secondPoll = pod.async.pollOnce();

        // Then the bureau step got a slot of its own, the assessor used only its 2, and 3 assessments stay due
        assertThat(started).isEqualTo(3);
        assertThat(secondPoll).isZero();
        assertThat(pod.async.freeSlots(JobStore.ASYNC_AGENT)).isZero();
        assertThat(assessments.stream().map(id -> job(id).get("status")).toList())
                .containsExactlyInAnyOrder("CLAIMED", "CLAIMED", "DUE", "DUE", "DUE");
        release.countDown();
        assertThat(pod.async.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(tokenOn(pod, bureauLoan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(job(bureauLoan)).containsEntry("status", "DONE").containsEntry("job_kind", "ASYNC_SERVICE");
        assertThat(pod.async.pollOnce()).isEqualTo(2);
        assertThat(pod.async.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(assessments.stream().filter(id -> "DONE".equals(job(id).get("status"))).count()).isEqualTo(4);
    }

    @Test
    void PRE_057_each_kind_parks_and_retries_with_its_own_settings() {
        // Given remote service steps limited to 5 attempts and assessor attempts retried after 4 s
        TestEngine pod = replica(TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over), assessor(call ->
                new AdapterResult.Failed("ASSESSOR_UNAVAILABLE", "503", true)), Map.of(
                JobStore.ASYNC_SERVICE, new RunnerSettings.Overrides(null, null, 5, null, null, null),
                JobStore.ASYNC_AGENT, new RunnerSettings.Overrides(null, null, null, Duration.ofSeconds(4), null, null)));
        deploy(pod);
        String bureauLoan = bureauCheck(pod, "LOAN-2027-0801");
        String loan = assess(pod, "LOAN-2027-0802");

        // When the assessor's attempt fails
        Claim claim = pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0);
        assertThat(pod.async.runNow(claim)).contains(StepCompletion.Outcome.ACCEPTED);

        // Then the service job was parked with its kind's attempts, and the assessor job is due after its own back-off
        assertThat(job(bureauLoan)).containsEntry("max_attempts", 5).containsEntry("status", "DUE");
        assertThat(((Timestamp) job(loan).get("next_fire_at")).toInstant()).isEqualTo(T0.plusSeconds(4));
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1);
    }

    @Test
    void PRE_057_remote_service_results_are_not_guarded() {
        // Given a bureau answer no schema would accept
        TestEngine pod = TestEngine.replica("pod-1", clock).adapters(bureau(call -> AdapterResult.completed(
                Map.of("anything", List.of(1, "two"))))).build();
        engines.add(pod);
        pipeline(pod);
        pod.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource("/bpmn/loan-bureau-check.bpmn"), null));
        String loan = bureauCheck(pod, "LOAN-2027-0803");

        // When the bureau's result is recorded
        Claim claim = pod.async.claim(16).get(0);

        // Then it is accepted as before: remote service steps have no result check
        assertThat(pod.async.runNow(claim)).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(details(loan, "ASYNC_RESULT_ACCEPTED")).doesNotContain("guardrailDecisionId");
    }

    // ---- PRE-058 output schema ------------------------------------------------------------------------------

    @Test
    void PRE_058_an_answer_breaking_its_output_schema_is_retried_once_then_refused_to_review() {
        // Given the assessor answers a risk score as text, every time
        TestEngine pod = replica("pod-1", assessor(call -> answer("high", "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0810");

        // When the executor records its first answer
        StepCompletion.Outcome first = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        // Then it gets one fresh attempt (schema-retries 1), which does not use up an attempt for technical failures
        assertThat(first).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_ATTEMPT_FAILED", "errorCode")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(detail(loan, "ASYNC_ATTEMPT_FAILED", "schemaRetry")).isEqualTo("1");
        assertThat(job(loan)).containsEntry("status", "DUE").containsEntry("attempt", 1).containsEntry("max_attempts", 4);
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
        assertThat(decisions(loan)).isZero();

        // When the retry breaks the schema again
        clock.advance(Duration.ofMinutes(1));
        StepCompletion.Outcome second = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        // Then it is refused with AGENT_SCHEMA_INVALID and goes to the step's reviewer; the guardrail never ran
        assertThat(second).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "reasons")).isEqualTo("[\"$.riskScore: expected integer\"]");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "route")).isEqualTo("review");
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
        assertThat(reviewTask(loan)).containsEntry("four_eyes", true);
        assertThat(decisions(loan)).isZero();
    }

    @Test
    void PRE_058_with_no_schema_retries_the_first_bad_answer_is_refused() {
        TestEngine pod = replica(TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over),
                assessor(call -> answer("high", "APPROVE")),
                Map.of(JobStore.ASYNC_AGENT, new RunnerSettings.Overrides(null, null, null, null, null, 0)));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0812", GUARD.replace("\"route-to-human\"", "\"fail\""));

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(history(loan, "ASYNC_ATTEMPT_FAILED")).isZero();
    }

    @Test
    void PRE_058_an_answer_breaking_its_output_schema_is_refused_through_the_completion_api() {
        // Given the assessor takes the request and calls back later
        TestEngine pod1 = replica("pod-1", assessor(call -> new AdapterResult.Accepted()));
        publishGuardrails(pod1);
        deploy(pod1);
        String loan = assess(pod1, "LOAN-2027-0811");
        Claim claim = pod1.async.claim(JobStore.ASYNC_AGENT, 16).get(0);
        assertThat(pod1.async.runNow(claim)).isEmpty();
        AdapterCall call = assessorCalls.get(0);

        // When its callback, on another replica, carries a score of 140 and an extra field
        TestEngine pod2 = replica("pod-2", assessor(c -> answer(1, "DECLINE")));
        StepCompletion completion = pod2.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                AdapterResult.completed(Map.of("riskScore", 140, "recommendation", "APPROVE", "comment", "ok")));

        // Then the completion API treats it the same way: nothing accepted, a failed attempt, retried later. The
        // retry is not final, so the caller reads ACCEPTED, not REFUSED
        assertThat(completion.outcome()).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        nothingAccepted(pod2, loan);
        assertThat(detail(loan, "ASYNC_ATTEMPT_FAILED", "errorCode")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(job(loan)).containsEntry("status", "DUE");
        assertThat(tokenOn(pod2, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
        assertThat(decisions(loan)).isZero();
    }

    // ---- PRE-059 guardrail ----------------------------------------------------------------------------------

    @Test
    void PRE_059_a_guardrail_violation_routes_to_a_reviewer_whose_answer_completes_the_step() {
        // Given the assessor recommends approval with a risk score of 85
        TestEngine pod = replica("pod-1", assessor(call -> answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0820");

        // When the executor records the answer
        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        // Then R2 refuses it with HIGH_RISK_SCORE, and a compliance reviewer gets a four-eyes task on the same step
        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "reasons")).isEqualTo("[\"HIGH_RISK_SCORE\"]");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "ruleIds")).isEqualTo("[\"R2\"]");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "route")).isEqualTo("review");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "output")).isEqualTo(
                "{\"riskScore\": 85, \"recommendation\": \"APPROVE\"}");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "outputFields")).isEqualTo("2");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "guardrailDecisionId")).isEqualTo(TestEngine.jdbc()
                .sql("SELECT id FROM rle_decision WHERE correlation_id = ?").param(loan).query(String.class).single());
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
        assertThat(job(loan)).containsEntry("status", "DONE");
        assertThat(reviewTask(loan)).containsEntry("node_id", "assess-documents")
                .containsEntry("assignee_role", REVIEWER_ROLE).containsEntry("four_eyes", true)
                .containsEntry("status", "OPEN");
        assertThat(history(loan, "STEP_ROUTED_TO_REVIEW")).isEqualTo(1);

        // When the instance's starter tries to complete the review, and then a compliance reviewer refers the loan
        String taskId = (String) reviewTask(loan).get("id");
        pod.identity.as(tenant, "pipeline", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        assertThatThrownBy(() -> pod.services.claim(taskId, 0, "pipeline-claim-review"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView open = pod.services.task(taskId);
        TaskView claimed = pod.services.claim(taskId, open.lockVersion(), "thu-claim-review");
        ProcessInstance after = pod.services.complete(taskId, Map.of("riskScore", 85, "recommendation", "REFER"),
                claimed.lockVersion(), "thu-complete-review");

        // Then the step completes with the reviewer's answer and the loan moves on to underwriting
        assertThat(after.variables()).containsEntry("assessment", Map.of("riskScore", 85L, "recommendation", "REFER"));
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isZero();
    }

    @Test
    void PRE_059_a_guardrail_violation_is_refused_the_same_way_through_the_completion_api() {
        // Given the assessor calls back later
        TestEngine pod = replica("pod-1", assessor(call -> new AdapterResult.Accepted()));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0821");
        assertThat(pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0))).isEmpty();
        AdapterCall call = assessorCalls.get(0);

        // When the callback reports approval with a risk score of 85, and the partner repeats it
        StepCompletion first = pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                answer(85, "APPROVE"));
        StepCompletion repeat = pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                answer(85, "APPROVE"));

        // Then it is refused and routed to review once; the repeat writes nothing
        assertThat(first.outcome()).isEqualTo(StepCompletion.Outcome.REFUSED);
        assertThat(repeat.outcome()).isEqualTo(StepCompletion.Outcome.DUPLICATE);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(history(loan, "ASYNC_RESULT_REFUSED")).isEqualTo(1);
        assertThat(tasks(loan)).isEqualTo(1);
        assertThat(decisions(loan)).isEqualTo(1);
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
    }

    @Test
    void PRE_059_with_on_violation_fail_the_job_fails_and_nothing_is_accepted() {
        // Given a step whose guardrail violations fail the job instead of going to a person
        TestEngine pod = replica("pod-1", assessor(call -> answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0822", GUARD.replace("\"route-to-human\"", "\"fail\""));

        // When the answer is recorded
        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        // Then the job fails, no task is opened, and the refusal is in the history for the incident (#68)
        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "route")).isEqualTo("incident");
        assertThat(tasks(loan)).isZero();
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
    }

    @Test
    void PRE_059_an_answer_the_guardrail_allows_advances_with_its_decision_recorded() {
        TestEngine pod = replica("pod-1", assessor(call -> answer(20, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0823");

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        assertThat(outcome).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(pod.services.instance(loan).variables()).containsEntry("assessment",
                Map.of("riskScore", 20L, "recommendation", "APPROVE"));
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "ruleIds")).isEqualTo("[\"R3\"]");
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "guardrailDecisionId")).isNotBlank();
        assertThat(job(loan)).containsEntry("status", "DONE");
    }

    // ---- PRE-060 fail closed --------------------------------------------------------------------------------

    @Test
    void PRE_060_a_missing_guardrail_rule_set_fails_closed_to_review() {
        // Given the guardrail rule set was never published in this tenant
        TestEngine pod = replica("pod-1", assessor(call -> answer(20, "APPROVE")));
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0830");

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        // Then even a harmless answer is refused and goes to a person
        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARDRAIL_EVALUATION_ERROR");
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
    }

    @Test
    void PRE_060_a_host_without_a_rule_engine_refuses_every_agent_answer() {
        TestEngine pod = replica(TestEngine.replica("pod-1", clock), assessor(call -> answer(20, "APPROVE")), Map.of());
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0831");

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "reasons")).isEqualTo("[\"RULE_ENGINE_MISSING\"]");
    }

    @Test
    void PRE_060_a_check_that_breaks_or_overruns_its_budget_fails_closed() {
        // Given one replica whose schema check throws, and one whose check takes 600 ms against a 500 ms budget
        OutputSchemaValidator broken = (schema, output) -> {
            throw new IllegalStateException("validator bug");
        };
        OutputSchemaValidator slow = (schema, output) -> {
            clock.advance(Duration.ofMillis(600));
            return new JsonSchemaSubsetValidator().validate(schema, output);
        };
        TestEngine pod1 = replica(TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over).outputSchemas(broken),
                assessor(call -> answer(20, "APPROVE")), Map.of());
        publishGuardrails(pod1);
        deploy(pod1);
        TestEngine pod2 = replica(TestEngine.replica("pod-2", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over).outputSchemas(slow),
                assessor(call -> answer(20, "APPROVE")), Map.of());
        String brokenLoan = assess(pod1, "LOAN-2027-0832");
        String slowLoan = assess(pod1, "LOAN-2027-0833");

        // When each replica records an answer the guardrail would allow (a result is accepted on any replica)
        List<Claim> claims = pod1.async.claim(JobStore.ASYNC_AGENT, 2);
        Claim brokenClaim = claims.stream().filter(c -> c.job().instanceId().equals(brokenLoan)).findFirst()
                .orElseThrow();
        Claim slowClaim = claims.stream().filter(c -> c.job().instanceId().equals(slowLoan)).findFirst().orElseThrow();
        StepCompletion.Outcome first = pod1.async.runNow(brokenClaim).orElseThrow();
        StepCompletion.Outcome second = pod2.async.runNow(slowClaim).orElseThrow();

        // Then both are refused: a check that breaks or overruns never lets a result through
        assertThat(List.of(first, second)).containsOnly(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod1, brokenLoan);
        nothingAccepted(pod1, slowLoan);
        assertThat(detail(brokenLoan, "ASYNC_RESULT_REFUSED", "reasons")).isEqualTo("[\"IllegalStateException\"]");
        assertThat(detail(slowLoan, "ASYNC_RESULT_REFUSED", "reasons")).isEqualTo("[\"GUARD_BUDGET_EXCEEDED\"]");
        // The schema check used the budget up, so the guardrail decision was never evaluated
        assertThat(decisions(slowLoan)).isZero();
        assertThat(tokenOn(pod1, brokenLoan)).isEqualTo("assess-documents:WAITING_USER");
        assertThat(tokenOn(pod1, slowLoan)).isEqualTo("assess-documents:WAITING_USER");
    }

    @Test
    void PRE_060_an_agent_job_without_a_guard_spec_fails_and_is_never_accepted() {
        TestEngine pod = replica("pod-1", assessor(call -> answer(20, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = pod.services.start(new StartRequest("loan-document-assessment", "LOAN-2027-0834",
                Map.of("applicationId", "LOAN-2027-0834"), null)).id();
        TestEngine.jdbc().sql("UPDATE pre_timer SET job_kind = 'ASYNC_AGENT' WHERE instance_id = ?").param(loan).update();

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARD_SPEC_INVALID");
        assertThat(job(loan)).containsEntry("status", "FAILED");
    }

    // ---- review fixes: maker of the review, refusal never lost, reviewer answer checked, four-eyes kept ---------

    @Test
    void PRE_059_the_caller_who_submitted_a_refused_result_cannot_approve_its_review() {
        // Given the assessor calls back later, through an account that also holds the compliance reviewer role
        TestEngine pod = replica("pod-1", assessor(call -> new AdapterResult.Accepted()));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0840");
        assertThat(pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0))).isEmpty();
        AdapterCall call = assessorCalls.get(0);
        pod.identity.as(tenant, "integration-svc", "processes:step:complete", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(ASSESSOR_CALLBACK, REVIEWER_ROLE);

        // When it posts a risk score of 95, which the guardrail refuses, and then tries to approve the review
        StepCompletion completion = pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                answer(95, "APPROVE"));
        String taskId = (String) reviewTask(loan).get("id");

        // Then the review is four-eyes against the submitter: claim and the action list refuse it
        assertThat(completion.outcome()).isEqualTo(StepCompletion.Outcome.REFUSED);
        assertThat(pod.services.task(taskId).allowedActions()).isEmpty();
        assertThatThrownBy(() -> pod.services.claim(taskId, 0, "svc-claim-review"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(reviewTask(loan)).containsEntry("status", "OPEN");
    }

    @Test
    void PRE_059_a_refusal_whose_review_task_cannot_be_created_is_recorded_and_fails_the_job() {
        // Given the database refuses the review task of this tenant (a broken constraint, say)
        TestEngine pod = replica("pod-1", assessor(call -> new AdapterResult.Accepted()));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0841");
        assertThat(pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0))).isEmpty();
        AdapterCall call = assessorCalls.get(0);
        TestEngine.jdbc().sql("CREATE OR REPLACE FUNCTION test_refuse_review() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'review task refused by test'; END $$").update();
        TestEngine.jdbc().sql("CREATE TRIGGER test_refuse_review BEFORE INSERT ON pre_task FOR EACH ROW "
                + "WHEN (NEW.tenant_id = '" + tenant + "') EXECUTE FUNCTION test_refuse_review()").update();
        StepCompletion completion;
        try {
            // When the callback reports a refused answer
            completion = pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                    answer(85, "APPROVE"));
        } finally {
            TestEngine.jdbc().sql("DROP TRIGGER test_refuse_review ON pre_task").update();
        }

        // Then the caller reads REFUSED, the refusal is in the history, and the job failed instead of retrying
        assertThat(completion.outcome()).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(history(loan, "ASYNC_RESULT_REFUSED")).isEqualTo(1);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "route")).isEqualTo("incident");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "routeError")).isEqualTo("STEP_RUN_FAILED");
        assertThat(job(loan)).containsEntry("status", "FAILED");
        assertThat(tasks(loan)).isZero();
        assertThat(pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                answer(20, "APPROVE")).outcome()).isEqualTo(StepCompletion.Outcome.DISCARDED);
    }

    @Test
    void PRE_059_the_reviewers_answer_must_meet_the_output_schema() {
        TestEngine pod = replica("pod-1", assessor(call -> answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0842");
        pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();
        String taskId = (String) reviewTask(loan).get("id");
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView claimed = pod.services.claim(taskId, 0, "thu-claim");

        // When the reviewer completes with no risk score and an extra field
        assertThatThrownBy(() -> pod.services.complete(taskId, Map.of("recommendation", "REFER", "note", "ok"),
                claimed.lockVersion(), "thu-complete-1"))
                .isInstanceOfSatisfying(io.github.dhluong90.bpm.process.api.FormValidationException.class, e ->
                        assertThat(e.violations()).contains("$.note: not allowed"));

        // Then nothing moved; a valid answer completes the step
        assertThat(reviewTask(loan)).containsEntry("status", "CLAIMED");
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
        ProcessInstance after = pod.services.complete(taskId, Map.of("riskScore", 85, "recommendation", "REFER"),
                claimed.lockVersion(), "thu-complete-2");
        assertThat(after.variables()).containsEntry("assessment", Map.of("riskScore", 85L, "recommendation", "REFER"));
    }

    @Test
    void PRE_059_a_payload_cannot_switch_four_eyes_off() {
        TestEngine pod = replica("pod-1", assessor(call -> answer(20, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = assess(pod, "LOAN-2027-0843", GUARD.replace("\"reviewer\"", "\"fourEyes\": false, \"reviewer\""));

        StepCompletion.Outcome outcome = pod.async.runNow(pod.async.claim(JobStore.ASYNC_AGENT, 16).get(0)).orElseThrow();

        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        nothingAccepted(pod, loan);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("GUARD_SPEC_INVALID");
        assertThat(reviewTask(loan)).containsEntry("four_eyes", true);
    }
}
