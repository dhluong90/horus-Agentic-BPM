package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.github.dhluong90.bpm.process.api.AsyncStepNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.service.AsyncSettings;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The agent step (#52) on PostgreSQL with the real Rule Engine behind the process starter's decision port: a
 * {@code serviceTask} with {@code horus:type="agentTask"} is imported with its guard spec, parks an {@code ASYNC_AGENT}
 * job whose {@code guard} payload comes from the published definition only, runs on the agent runner through a stub
 * agent adapter, and has its answer checked in the result transaction.
 *
 * <p>A loan application's documents are assessed by the agent {@code documents.assess}. Its answer must be
 * {@code { riskScore: integer 0..100, recommendation: APPROVE | REFER | DECLINE, summary?: string (masked) }}, and
 * the bank's {@code agent-output-guardrails} rule set refuses a risk score of 70 or more (R2). A refused answer goes
 * to a compliance reviewer's four-eyes task. Replicas share one {@link TestClock}; no test sleeps.
 */
class AgentTaskIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String ASSESSOR = "documents.assess";
    private static final String ASSESSOR_CALLBACK = "ASSESSOR_CALLBACK";
    private static final String REVIEWER_ROLE = "COMPLIANCE_REVIEWER";
    private static final String DEFINITION = "/bpmn/loan-agent-assessment.bpmn";
    private static final String SUMMARY = "Applicant Nguyen Van An, national id 079088001234, salary slips for 6 months";

    /** Rule sets outlive a test on the shared database, so every test runs in a tenant of its own. */
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private final TestClock clock = TestClock.at(T0);
    private final String tenant = "bank-agent-" + TENANTS.incrementAndGet();
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

    private static AsyncSettings settings() {
        return new AsyncSettings(AsyncSettings.DEFAULT_LEASE_MARGIN, AsyncSettings.DEFAULT_TIMEOUT,
                AsyncSettings.DEFAULT_EXECUTOR_THREADS, Map.of(ASSESSOR, Set.of(ASSESSOR_CALLBACK)));
    }

    private TestEngine replica(String id, StubAgentAdapter assessor) {
        return replica(TestEngine.replica(id, clock), assessor);
    }

    private TestEngine replica(TestEngine.Builder builder, StubAgentAdapter assessor) {
        TestEngine engine = builder.withRuleEngine().decisionPort(StarterDecisionPorts::over).async(settings())
                .agents(assessor).build();
        engines.add(engine);
        pipeline(engine);
        return engine;
    }

    private void pipeline(TestEngine engine) {
        engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work", "processes:step:complete", "processes:cancel");
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

    private static void deploy(TestEngine engine) {
        deploy(engine, TestEngine.resource(DEFINITION));
    }

    private static void deploy(TestEngine engine, byte[] bpmn) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML, bpmn, null));
    }

    private static String definition(String from, String to) {
        return new String(TestEngine.resource(DEFINITION), StandardCharsets.UTF_8).replace(from, to);
    }

    /** A loan reaches the assessment; the step parks its agent job. */
    private static String apply(TestEngine engine, String applicationId) {
        return engine.services.start(new StartRequest("loan-agent-assessment", applicationId,
                Map.of("applicationId", applicationId, "amount", 250_000_000L), null)).id();
    }

    private static Map<String, Object> answer(int riskScore, String recommendation) {
        return Map.of("riskScore", riskScore, "recommendation", recommendation, "summary", SUMMARY);
    }

    @SafeVarargs
    private static StubAgentAdapter assessor(Map<String, ?>... answers) {
        return StubAgentAdapter.answering(ASSESSOR, answers);
    }

    private static StubAgentAdapter assessor(Function<AgentInvocation, AdapterResult> answer) {
        return StubAgentAdapter.of(ASSESSOR, answer);
    }

    private static Map<String, Object> job(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id, status, attempt, job_kind, max_attempts, next_fire_at, locked_by, "
                + "locked_until, lease_duration, payload::text AS payload FROM pre_timer WHERE instance_id = ?")
                .param(instanceId).query().singleRow();
    }

    private static Instant lockedUntil(String instanceId) {
        return ((Timestamp) job(instanceId).get("locked_until")).toInstant();
    }

    private static int history(String instanceId, String type) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(Integer.class).single();
    }

    private static int history(String instanceId) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ?").param(instanceId)
                .query(Integer.class).single();
    }

    private static String detail(String instanceId, String type, String path) {
        return TestEngine.jdbc().sql("SELECT details #>> ?::text[] FROM pre_history WHERE instance_id = ? "
                + "AND event_type = ?").params("{" + path + "}", instanceId, type).query(String.class).single();
    }

    private static String tokenOn(TestEngine engine, String instanceId) {
        return engine.services.instance(instanceId).tokens().stream().filter(t -> t.kind().equals("FLOW")
                && !t.status().equals("COMPLETED")).map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private static String reviewTaskId(String instanceId) {
        return TestEngine.jdbc().sql("SELECT id FROM pre_task WHERE instance_id = ? AND node_id = 'assess-documents'")
                .param(instanceId).query(String.class).single();
    }

    private static int definitions(String tenant) {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_definition WHERE tenant_id = ?").param(tenant)
                .query(Integer.class).single();
    }

    private static Claim claimOne(TestEngine engine) {
        List<Claim> claims = engine.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(claims).hasSize(1);
        return claims.get(0);
    }

    // ---- PRE-061 the guard spec at import ---------------------------------------------------------------------

    @Test
    void PRE_061_an_invalid_guard_spec_or_an_unknown_agent_is_refused_at_import_and_nothing_is_written() {
        TestEngine pod = replica("pod-1", assessor(answer(20, "APPROVE")));
        String[][] cases = {
                {"horus:agent=\"documents.assess\"", "horus:agent=\"documents.summarise\"", "AGENT_NOT_REGISTERED"},
                {"\"maxLength\": 500", "\"maxLength\": 500, \"pattern\": \"^[A-Z]\"", "GUARD_SPEC_INVALID"},
                {"reviewer=\"role:COMPLIANCE_REVIEWER\"", "reviewer=\"role:COMPLIANCE_REVIEWER\" fourEyes=\"false\"",
                        "GUARD_SPEC_INVALID"},
                {" reviewer=\"role:COMPLIANCE_REVIEWER\"", "", "GUARD_SPEC_INVALID"}};

        for (String[] c : cases) {
            byte[] bpmn = definition(c[0], c[1]).getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> deploy(pod, bpmn)).as(c[1])
                    .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.problems())
                            .extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                            .contains(org.assertj.core.groups.Tuple.tuple(c[2], "assess-documents")));
        }

        assertThat(definitions(tenant)).isZero();
    }

    @Test
    void PRE_061_a_host_without_a_rule_engine_cannot_import_an_agent_step() {
        TestEngine pod = TestEngine.replica("pod-1", clock).async(settings()).agents(assessor(answer(20, "APPROVE")))
                .build();
        engines.add(pod);
        pipeline(pod);

        assertThatThrownBy(() -> deploy(pod)).isInstanceOfSatisfying(ValidationFailedException.class,
                e -> assertThat(e.problems()).extracting(DefinitionProblem::code).containsExactly("BPMN_NOT_EXECUTABLE"));
        assertThat(definitions(tenant)).isZero();
    }

    // ---- PRE-016 the guardrail rule set's contract at import and approval ------------------------------------

    /** Case B: the rule set decides with {@code decision: string} and declares no {@code allow}. */
    private static final String DECIDES_WITHOUT_ALLOW = """
            schemaVersion: 1
            ruleSet: agent-output-guardrails
            description: Guardrails that name a decision instead of allow
            hitPolicy: first
            inputs:
              - { name: riskScore, type: integer }
            outputs:
              - { name: decision, type: string }
            rules:
              - id: R1
                when: { riskScore: ">= 70" }
                then: { decision: "'REFER'" }
              - id: R2
                when: {}
                then: { decision: "'OK'" }
            """;

    /**
     * Case C: every matching row answers ({@code collect}). The Rule Engine itself makes a collected output a list, so
     * {@code allow} is {@code list<boolean>} here; both the hit policy and the type break the contract.
     */
    private static final String COLLECTS = """
            schemaVersion: 1
            ruleSet: agent-output-guardrails
            description: Guardrails that collect every matching row
            hitPolicy: collect
            inputs:
              - { name: riskScore, type: integer }
            outputs:
              - { name: allow, type: list<boolean> }
            rules:
              - id: R1
                when: { riskScore: ">= 70" }
                then: { allow: "false" }
              - id: R2
                when: {}
                then: { allow: "true" }
            """;

    /** A rule set that reads an input the agent's answer never carries. */
    private static final String READS_INCOME = """
            schemaVersion: 1
            ruleSet: agent-output-guardrails
            description: Guardrails that read the applicant's income
            hitPolicy: unique
            inputs:
              - { name: riskScore, type: integer }
              - { name: monthlyIncome, type: decimal }
            outputs:
              - { name: allow, type: boolean }
              - { name: reasons, type: string }
            rules:
              - id: R1
                when: { monthlyIncome: "< 10000000" }
                then: { allow: "false", reasons: "'LOW_INCOME'" }
              - id: R2
                when: { monthlyIncome: ">= 10000000" }
                then: { allow: "true", reasons: "''" }
            """;

    private TestEngine draftReplica() {
        TestEngine engine = TestEngine.replica("pod-1", clock).autoApprove(false).withRuleEngine()
                .decisionPort(StarterDecisionPorts::over).async(settings()).agents(assessor(answer(20, "APPROVE")))
                .build();
        engines.add(engine);
        pipeline(engine);
        return engine;
    }

    /** Publishes a version of a guardrail rule set in a tenant, with a second person approving it. */
    private void publish(TestEngine engine, String inTenant, String yaml) {
        engine.ruleIdentity.as(inTenant, "lan");
        io.github.dhluong90.bpm.rules.api.ImportRequest source = io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                yaml.getBytes(StandardCharsets.UTF_8));
        int version = engine.rules.versionService().findRuleSet("agent-output-guardrails").isPresent()
                ? engine.rules.versionService().importVersion("agent-output-guardrails", source).version()
                : engine.rules.versionService().importRuleSet(source).version();
        long requested = engine.rules.versionService().requestPublish("agent-output-guardrails", version,
                WriteOptions.none()).lockVersion();
        engine.ruleIdentity.as(inTenant, "minh");
        engine.rules.versionService().approve("agent-output-guardrails", version,
                new WriteOptions(null, requested, null));
        engine.ruleIdentity.as(null, null);
    }

    private static String guardrailYaml() {
        return new String(TestEngine.resource("/rules/document-assessment-guardrails.yaml"), StandardCharsets.UTF_8);
    }

    private static void refusedNaming(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String check) {
        assertThatThrownBy(call).isInstanceOfSatisfying(GuardrailContractInvalidException.class, e -> {
            assertThat(e.code()).isEqualTo("GUARDRAIL_CONTRACT_INVALID");
            assertThat(e.problems()).extracting(DefinitionProblem::code, DefinitionProblem::elementId)
                    .containsOnly(org.assertj.core.groups.Tuple.tuple("GUARDRAIL_CONTRACT_INVALID",
                            "assess-documents"));
            assertThat(e.getMessage()).contains("step assess-documents").contains(check)
                    .doesNotContain("maxLength").doesNotContain("\"type\"");
        });
    }

    @Test
    void PRE_016_deploy_rejects_guardrail_rule_set_breaking_contract() {
        // Case D: no rule set of that name in the tenant
        TestEngine pod = draftReplica();
        refusedNaming(() -> deploy(pod), "agent-output-guardrails does not exist in this tenant");
        assertThat(definitions(tenant)).isZero();

        // Case B: outputs [decision: string], no allow
        publish(pod, tenant, DECIDES_WITHOUT_ALLOW);
        refusedNaming(() -> deploy(pod), "declares no output allow: boolean (outputs decision: string)");
        assertThat(definitions(tenant)).isZero();

        // Case C: allow: boolean under hit policy collect
        publish(pod, tenant, COLLECTS);
        refusedNaming(() -> deploy(pod), "has hit policy collect; a guardrail needs first or unique");
        assertThat(definitions(tenant)).isZero();

        // A rule set that reads monthlyIncome, which the agent's answer never carries
        publish(pod, tenant, READS_INCOME);
        refusedNaming(() -> deploy(pod), "reads input monthlyIncome, which horus:outputSchema does not declare");
        assertThat(definitions(tenant)).isZero();

        // Case A: allow: boolean and reasons under hit policy first, inputs the schema declares: a draft
        publish(pod, tenant, guardrailYaml());
        pod.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
        DefinitionVersion draft = pod.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource(DEFINITION), null));
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(definitions(tenant)).isOne();
    }

    @Test
    void PRE_016_another_tenants_guardrail_rule_set_does_not_meet_the_contract_and_nothing_is_written() {
        // Given the guardrail rule set published in bank-other only
        TestEngine pod = draftReplica();
        String other = tenant + "-other";
        publish(pod, other, guardrailYaml());

        // When this tenant deploys, then the other tenant's rule set is never seen
        refusedNaming(() -> deploy(pod), "does not exist in this tenant");
        assertThat(definitions(tenant)).isZero();
        assertThat(definitions(other)).isZero();
    }

    @Test
    void PRE_016_the_contract_is_checked_again_at_request_approval_and_at_approval() {
        TestEngine pod = draftReplica();
        publish(pod, tenant, guardrailYaml());
        DefinitionVersion draft = pod.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                TestEngine.resource(DEFINITION), null));

        // When a collect version of the rule set takes effect, then the draft cannot go to approval
        publish(pod, tenant, COLLECTS);
        pod.identity.as(tenant, "lan", "processes:author", "processes:read");
        refusedNaming(() -> pod.services.requestApproval(draft.ref(), draft.lockVersion(), null, null),
                "has hit policy collect");
        assertThat(pod.services.find(draft.ref()).orElseThrow().status()).isEqualTo("DRAFT");

        // Given a compliant version again, the request goes through
        publish(pod, tenant, guardrailYaml());
        DefinitionVersion pending = pod.services.requestApproval(draft.ref(), draft.lockVersion(), null, null);
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");

        // When the rule set loses allow before the approver acts, then approval is refused and nothing changes
        publish(pod, tenant, DECIDES_WITHOUT_ALLOW);
        pod.identity.as(tenant, "minh", "processes:approve", "processes:read");
        refusedNaming(() -> pod.services.approve(draft.ref(), pending.lockVersion(), draft.definitionHash(), "ok", null),
                "declares no output allow");
        DefinitionVersion after = pod.services.find(draft.ref()).orElseThrow();
        assertThat(after.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(after.lockVersion()).isEqualTo(pending.lockVersion());

        // And with the compliant rule set back in effect, the second person approves
        publish(pod, tenant, guardrailYaml());
        pod.identity.as(tenant, "minh", "processes:approve", "processes:read");
        assertThat(pod.services.approve(draft.ref(), pending.lockVersion(), draft.definitionHash(), "ok", null).status())
                .isEqualTo("ACTIVE");
    }

    // ---- PRE-062 park, run, keys ------------------------------------------------------------------------------

    @Test
    void PRE_062_an_agent_step_parks_an_agent_job_with_the_guard_spec_of_the_published_version() {
        TestEngine pod = replica("pod-1", assessor(answer(20, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);

        String loan = apply(pod, "LOAN-2027-0901");

        // The token waits and one agent job is due, leased for PT2S + 30 s, with the runner's attempts
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_ASYNC");
        Map<String, Object> job = job(loan);
        assertThat(job).containsEntry("job_kind", "ASYNC_AGENT").containsEntry("status", "DUE")
                .containsEntry("attempt", 0).containsEntry("max_attempts", 3);
        Map<String, Object> payload = Values.readMap((String) job.get("payload"));
        assertThat(payload).containsEntry("target", ASSESSOR).containsEntry("timeoutMs", 2_000L)
                .containsEntry("businessKey", "LOAN-2027-0901")
                .containsEntry("inputs", Map.of("applicationId", "LOAN-2027-0901", "requestedAmount", 250_000_000L));
        @SuppressWarnings("unchecked")
        Map<String, Object> guard = (Map<String, Object>) payload.get("guard");
        assertThat(guard).containsEntry("decision", "agent-output-guardrails")
                .containsEntry("onViolation", "route-to-human").containsEntry("reviewer", "role:COMPLIANCE_REVIEWER")
                .containsEntry("fourEyes", true).containsKey("outputSchema");
        assertThat(guard.get("outputSchema").toString()).contains("riskScore", "mask=true");
        assertThat(detail(loan, "ASYNC_STEP_SCHEDULED", "jobKind")).isEqualTo("ASYNC_AGENT");
        assertThat(detail(loan, "ASYNC_STEP_SCHEDULED", "leaseMs")).isEqualTo("32000");
    }

    @Test
    void PRE_062_the_model_call_is_keyed_by_the_attempt_id_and_one_bad_answer_is_asked_again() {
        // Given an agent whose first answer breaks the schema and whose second is fine
        StubAgentAdapter assessor = assessor(Map.of("riskScore", "high", "recommendation", "APPROVE"),
                answer(20, "APPROVE"));
        TestEngine pod = replica("pod-1", assessor);
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0902");

        // When the replica runs the first attempt, and the retry after the back-off
        Claim first = claimOne(pod);
        StepCompletion.Outcome firstOutcome = pod.async.runNow(first).orElseThrow();
        Map<String, Object> afterFirst = job(loan);
        clock.advance(Duration.ofSeconds(10));
        Claim second = claimOne(pod);
        StepCompletion.Outcome secondOutcome = pod.async.runNow(second).orElseThrow();

        // Then the bad answer is retried once as a new generation: a new model call key, the same idempotency key
        assertThat(firstOutcome).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        assertThat(afterFirst).containsEntry("status", "DUE").containsEntry("max_attempts", 4);
        assertThat(detail(loan, "ASYNC_ATTEMPT_FAILED", "errorCode")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(detail(loan, "ASYNC_ATTEMPT_FAILED", "schemaRetry")).isEqualTo("1");
        assertThat(secondOutcome).isEqualTo(StepCompletion.Outcome.ACCEPTED);
        List<AgentInvocation> calls = assessor.invocations();
        assertThat(calls).hasSize(2);
        assertThat(calls).extracting(AgentInvocation::modelCallKey).containsExactly(
                ProcessServices.attemptId(first.job().id(), 1), ProcessServices.attemptId(first.job().id(), 2));
        assertThat(calls).extracting(AgentInvocation::idempotencyKey).containsOnly(first.job().id());
        assertThat(calls.get(0).inputs()).isEqualTo(Map.of("applicationId", "LOAN-2027-0902",
                "requestedAmount", 250_000_000L));
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
        assertThat(pod.services.instance(loan).variables()).containsEntry("assessment",
                Map.of("riskScore", 20L, "recommendation", "APPROVE", "summary", SUMMARY));
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "ruleIds")).isEqualTo("[\"R3\"]");
    }

    @Test
    void PRE_062_a_second_bad_answer_is_refused_to_the_four_eyes_review_which_the_starter_cannot_take() {
        // Given an agent that breaks the schema twice
        TestEngine pod = replica("pod-1", assessor(Map.of("riskScore", 140, "recommendation", "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0903");

        // When both attempts run
        pod.async.runNow(claimOne(pod));
        clock.advance(Duration.ofSeconds(10));
        StepCompletion.Outcome outcome = pod.async.runNow(claimOne(pod)).orElseThrow();

        // Then the second is refused with AGENT_SCHEMA_INVALID, the guardrail is never asked, and a reviewer decides
        assertThat(outcome).isEqualTo(StepCompletion.Outcome.REFUSED);
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "code")).isEqualTo("AGENT_SCHEMA_INVALID");
        assertThat(detail(loan, "ASYNC_RESULT_REFUSED", "route")).isEqualTo("review");
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isZero();
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM rle_decision WHERE correlation_id = ?").param(loan)
                .query(Integer.class).single()).isZero();
        assertThat(tokenOn(pod, loan)).isEqualTo("assess-documents:WAITING_USER");
        String taskId = reviewTaskId(loan);
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        assertThatThrownBy(() -> pod.services.claim(taskId, 0, "starter-claims-review"))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void PRE_062_another_tenants_agent_job_is_not_found_and_nothing_is_written() {
        // Given an assessment handed off by the agent
        StubAgentAdapter assessor = assessor(invocation -> new AdapterResult.Accepted());
        TestEngine pod = replica("pod-1", assessor);
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0904");
        assertThat(pod.async.runNow(claimOne(pod))).isEmpty();
        AgentInvocation call = assessor.invocations().get(0);
        int before = history(loan);

        // When a callback client of another tenant reports an answer for it
        pod.identity.as("bank-other", "callback", "processes:step:complete");
        pod.identity.roles = Set.of(ASSESSOR_CALLBACK);

        // Then the step is not found there, and nothing is written
        assertThatThrownBy(() -> pod.services.complete(call.jobId(), call.attemptId(), call.completionToken(),
                AdapterResult.completed(answer(20, "APPROVE")))).isInstanceOf(AsyncStepNotFoundException.class);
        assertThat(history(loan)).isEqualTo(before);
        assertThat(job(loan)).containsEntry("status", "CLAIMED");
    }

    // ---- PRE-063 masking --------------------------------------------------------------------------------------

    @Test
    void PRE_063_an_accepted_agent_answer_is_recorded_masked_by_the_output_schema() {
        TestEngine pod = replica("pod-1", assessor(answer(20, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0905");

        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.ACCEPTED);

        // The summary is marked mask: true, so the immutable history never keeps its text; the step result does
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "output")).isEqualTo(
                "{\"summary\": \"***\", \"riskScore\": 20, \"recommendation\": \"APPROVE\"}");
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "outputFields")).isEqualTo("3");
        assertThat(detail(loan, "ASYNC_RESULT_ACCEPTED", "guardrailDecisionId")).isNotBlank();
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? "
                + "AND details::text LIKE '%079088001234%'").param(loan).query(Integer.class).single()).isZero();
        assertThat(pod.services.instance(loan).variables().get("assessment").toString()).contains("079088001234");
    }

    @Test
    void PRE_063_the_review_task_carries_why_the_answer_was_refused_and_the_masked_answer() {
        // Given the agent recommends approval with a risk score of 85
        TestEngine pod = replica("pod-1", assessor(answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0906");

        // When the answer is recorded
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);

        // Then the compliance reviewer's task carries the reasons, the rule, the decision, the inputs and the answer
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView task = pod.services.task(reviewTaskId(loan));
        assertThat(task.assignee()).isEqualTo("role:COMPLIANCE_REVIEWER");
        assertThat(task.fourEyes()).isTrue();
        assertThat(task.review()).containsEntry("code", "GUARDRAIL_VIOLATION")
                .containsEntry("reasons", List.of("HIGH_RISK_SCORE")).containsEntry("ruleIds", List.of("R2"))
                .containsEntry("inputs", Map.of("applicationId", "LOAN-2027-0906", "requestedAmount", 250_000_000L))
                .containsEntry("output", Map.of("riskScore", 85L, "recommendation", "APPROVE", "summary", "***"))
                .containsEntry("outputFields", 3L)
                .containsEntry("guardrailDecisionId", detail(loan, "ASYNC_RESULT_REFUSED", "guardrailDecisionId"));
        assertThat(task.review().toString()).doesNotContain("079088001234");

        // And the reviewer's answer completes the step
        TaskView claimed = pod.services.claim(task.id(), task.lockVersion(), "thu-claim");
        ProcessInstance after = pod.services.complete(task.id(), Map.of("riskScore", 85, "recommendation", "REFER"),
                claimed.lockVersion(), "thu-complete");
        assertThat(after.variables()).containsEntry("assessment", Map.of("riskScore", 85L, "recommendation", "REFER"));
        assertThat(tokenOn(pod, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    private static final String FIELD_TITLES = "<horus:fieldTitles>{\"inputs\": {\"requestedAmount\": {\"en\": "
            + "\"Requested amount\", \"vi\": \"Số tiền đề nghị\"}}, \"outputs\": {\"riskScore\": {\"en\": "
            + "\"Risk score\", \"vi\": \"Điểm rủi ro\"}}}</horus:fieldTitles>\n        <horus:outputSchema>{";

    @Test
    void PRE_217_the_review_task_carries_the_english_and_vietnamese_titles_the_step_declares() {
        // Given a step that declares titles for requestedAmount and riskScore, and an answer the guardrail refuses
        TestEngine pod = replica("pod-1", assessor(answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod, definition("<horus:outputSchema>{", FIELD_TITLES).getBytes(StandardCharsets.UTF_8));
        String loan = apply(pod, "LOAN-2027-0907");
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);

        // When the reviewer opens the review task
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView task = pod.services.task(reviewTaskId(loan));

        // Then the review carries the titles in both languages per field, and the values are unchanged
        assertThat(task.review()).containsEntry("fieldTitles", Map.of(
                "inputs", Map.of("requestedAmount", Map.of("en", "Requested amount", "vi", "Số tiền đề nghị")),
                "outputs", Map.of("riskScore", Map.of("en", "Risk score", "vi", "Điểm rủi ro"))));
        assertThat(task.review()).containsEntry("outputFields", 3L);
    }

    @Test
    void PRE_217_a_step_without_titles_has_no_fieldTitles_and_a_malformed_set_is_refused_at_import() {
        // Given the plain step: the review has no fieldTitles
        TestEngine pod = replica("pod-1", assessor(answer(85, "APPROVE")));
        publishGuardrails(pod);
        deploy(pod);
        String loan = apply(pod, "LOAN-2027-0908");
        assertThat(pod.async.runNow(claimOne(pod))).contains(StepCompletion.Outcome.REFUSED);
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        assertThat(pod.services.task(reviewTaskId(loan)).review()).doesNotContainKey("fieldTitles");

        // When a version declares a title that is not text
        pod.identity.as(tenant, "pipeline", "processes:deploy", "processes:read");
        byte[] broken = definition("<horus:outputSchema>{", "<horus:fieldTitles>{\"outputs\": {\"riskScore\": "
                + "{\"en\": 5}}}</horus:fieldTitles>\n        <horus:outputSchema>{").getBytes(StandardCharsets.UTF_8);

        // Then the import is refused with FIELD_TITLES_INVALID on the step, naming no title
        assertThatThrownBy(() -> deploy(pod, broken)).isInstanceOfSatisfying(ValidationFailedException.class,
                e -> assertThat(e.problems()).extracting(DefinitionProblem::code).contains("FIELD_TITLES_INVALID"));
    }

    @Test
    void PRE_032_the_catalogue_lists_the_rule_sets_published_in_the_callers_tenant_only() {
        // Given agent-output-guardrails published in this tenant's Rule Engine
        TestEngine pod = replica("pod-1", assessor(answer(85, "APPROVE")));
        publishGuardrails(pod);
        io.github.dhluong90.bpm.process.internal.service.StepCatalogues catalogue =
                new io.github.dhluong90.bpm.process.internal.service.StepCatalogues(() -> pod.identity.tenant,
                        pod.identity, clock, new io.github.dhluong90.bpm.process.postgres.ProcessEngineTransactions(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                                        TestEngine.dataSource())),
                        new io.github.dhluong90.bpm.process.postgres.JdbcCalendarStore(TestEngine.dataSource()),
                        List.of(), List.of(), List.of(), pod.decisions,
                        io.github.dhluong90.bpm.process.spi.RoleCatalogue.ACCEPT_ALL);

        // When an author of that tenant reads the catalogue
        pod.identity.as(tenant, "carol", "processes:author");
        var view = catalogue.stepCatalogue();

        // Then the rule set is a decision rule set and, meeting the guardrail contract, a guardrail rule set
        assertThat(view.decisionRuleSets()).extracting(r -> r.name()).containsExactly("agent-output-guardrails");
        assertThat(view.guardrailRuleSets()).extracting(r -> r.name()).containsExactly("agent-output-guardrails");
        assertThat(view.decisionRuleSets().get(0).outputs()).containsEntry("allow", "boolean");

        // And an author of another tenant sees none of it
        pod.identity.as("bank-other", "carol", "processes:author");
        assertThat(catalogue.stepCatalogue().decisionRuleSets()).isEmpty();
    }

    // ---- PRE-009, PRE-017, PRE-041: the agent step on the async executor --------------------------------------

    @Test
    void PRE_009_agent_step_executes_outside_db_transaction() throws Exception {
        // Given a host with a 2-connection pool, and an agent that holds every call until all 10 are in flight
        CountDownLatch allInFlight = new CountDownLatch(10);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger insideTransaction = new AtomicInteger();
        StubAgentAdapter slowAssessor = assessor(invocation -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            allInFlight.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    return new AdapterResult.Failed("TEST_STALLED", "never released", false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AdapterResult.Failed("INTERRUPTED", "interrupted", true);
            }
            return AdapterResult.completed(answer(20, "APPROVE"));
        });
        try (HikariDataSource pool = TestEngine.pool(2, Duration.ofMillis(500))) {
            TestEngine host = replica(TestEngine.replica("pod-1", clock).dataSource(pool), slowAssessor);
            publishGuardrails(host);
            deploy(host);
            List<String> loans = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                loans.add(apply(host, "LOAN-2027-091" + i));
            }

            // When the replica starts all 10 agent calls while another thread keeps querying the database
            assertThat(host.async.pollOnce()).isEqualTo(10);
            assertThat(allInFlight.await(30, TimeUnit.SECONDS)).isTrue();
            int activeWhileCalling = pool.getHikariPoolMXBean().getActiveConnections();
            ExecutorService other = Executors.newSingleThreadExecutor();
            Future<Integer> queries = other.submit(() -> {
                int ok = 0;
                for (int i = 0; i < 20; i++) {
                    ok += org.springframework.jdbc.core.simple.JdbcClient.create(pool)
                            .sql("SELECT count(*) FROM pre_instance").query(Integer.class).single() >= 10 ? 1 : 0;
                }
                return ok;
            });
            int succeeded = queries.get(30, TimeUnit.SECONDS);
            other.shutdown();
            release.countDown();
            assertThat(host.async.awaitIdle(Duration.ofSeconds(30))).isTrue();

            // Then no connection was held during the calls, every query got one, and every loan moved on once
            assertThat(activeWhileCalling).isZero();
            assertThat(succeeded).isEqualTo(20);
            assertThat(insideTransaction).hasValue(0);
            assertThat(slowAssessor.invocations()).hasSize(10);
            for (String loan : loans) {
                assertThat(tokenOn(host, loan)).isEqualTo("underwrite:WAITING_USER");
                assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 1);
                assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isEqualTo(1);
            }
        }
    }

    @Test
    void PRE_017_agent_lease_is_timeout_plus_margin() {
        // Given the agent step's timeout PT2S and jobs.lease-margin 30 s; pod 1's agent answers after 1.9 s
        AtomicBoolean pod2ClaimedDuringCall = new AtomicBoolean();
        TestEngine[] pod2 = new TestEngine[1];
        TestEngine pod1 = replica("pod-1", assessor(invocation -> {
            clock.advance(Duration.ofMillis(1_900));
            pod2ClaimedDuringCall.set(!pod2[0].async.claim(16).isEmpty());
            return AdapterResult.completed(answer(20, "APPROVE"));
        }));
        pod2[0] = replica("pod-2", assessor(answer(30, "APPROVE")));
        publishGuardrails(pod1);
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0921");

        // When pod 1 claims at T0 and pod 2 polls during the call and after it
        Claim claimed = claimOne(pod1);

        // Then TX1 sets locked_until = T0 + 32 s, pod 2 never claims the live attempt, and TX2 records the answer once
        assertThat(lockedUntil(loan)).isEqualTo(T0.plusSeconds(32));
        assertThat(claimed.job().leaseDuration()).isEqualTo(Duration.ofSeconds(32));
        assertThat(pod1.async.runNow(claimed)).contains(StepCompletion.Outcome.ACCEPTED);
        assertThat(pod2ClaimedDuringCall).isFalse();
        assertThat(pod2[0].async.claim(16)).isEmpty();
        assertThat(job(loan)).containsEntry("status", "DONE").containsEntry("attempt", 1);
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isEqualTo(1);
        assertThat(tokenOn(pod1, loan)).isEqualTo("underwrite:WAITING_USER");
    }

    @Test
    void PRE_017_a_crashed_agent_attempt_is_reclaimed_at_the_end_of_its_lease_with_a_new_model_call_key() {
        // Given pod 1 claims the agent step at T0 and crashes right after TX1
        StubAgentAdapter pod2Assessor = assessor(answer(25, "APPROVE"));
        TestEngine pod1 = replica("pod-1", assessor(answer(20, "APPROVE")));
        TestEngine pod2 = replica("pod-2", pod2Assessor);
        publishGuardrails(pod1);
        deploy(pod1);
        String loan = apply(pod1, "LOAN-2027-0922");
        Claim first = claimOne(pod1);

        // When pod 2 polls just before and at T0 + 32 s
        clock.set(T0.plusSeconds(32).minusMillis(1));
        List<Claim> early = pod2.async.claim(JobStore.ASYNC_AGENT, 16);
        clock.set(T0.plusSeconds(32));
        List<Claim> late = pod2.async.claim(JobStore.ASYNC_AGENT, 16);

        // Then pod 2 reclaims it as attempt 2: the model is asked under a new key, side effects keep the job id
        assertThat(early).isEmpty();
        assertThat(late).hasSize(1);
        assertThat(late.get(0).job().attempt()).isEqualTo(2);
        assertThat(pod2.async.runNow(late.get(0))).contains(StepCompletion.Outcome.ACCEPTED);
        AgentInvocation call = pod2Assessor.invocations().get(0);
        assertThat(call.modelCallKey()).isEqualTo(ProcessServices.attemptId(first.job().id(), 2))
                .isNotEqualTo(ProcessServices.attemptId(first.job().id(), 1));
        assertThat(call.idempotencyKey()).isEqualTo(first.job().id());
        assertThat(call.deadline()).isEqualTo(T0.plusSeconds(34));
        assertThat(pod2.services.instance(loan).variables().get("assessment").toString()).contains("riskScore=25");
    }

    @Test
    void PRE_041_cancel_during_agent_call_discards_late_result() {
        // Given the agent step's job claimed by pod 1, with the agent still working
        TestEngine[] pod = new TestEngine[1];
        pod[0] = replica("pod-1", assessor(invocation -> {
            // When the loan is cancelled at 0.5 s and the agent answers at 2 s
            clock.advance(Duration.ofMillis(500));
            ProcessInstance current = pod[0].services.instance(invocation.instanceId());
            pod[0].services.cancel(invocation.instanceId(), current.lockVersion(), "Applicant withdrew",
                    "cancel-0930");
            clock.advance(Duration.ofMillis(1_500));
            return AdapterResult.completed(answer(20, "APPROVE"));
        }));
        publishGuardrails(pod[0]);
        deploy(pod[0]);
        String loan = apply(pod[0], "LOAN-2027-0930");
        Claim claimed = claimOne(pod[0]);

        Optional<StepCompletion.Outcome> outcome = pod[0].async.runNow(claimed);

        // Then TX2 writes only LATE_RESULT_DISCARDED with the attempt id; no guardrail decision is evaluated
        assertThat(outcome).contains(StepCompletion.Outcome.DISCARDED);
        assertThat(pod[0].services.instance(loan).status()).isEqualTo("CANCELLED");
        assertThat(job(loan)).containsEntry("status", "CANCELLED");
        assertThat(TestEngine.jdbc().sql("SELECT attempt_id FROM pre_history WHERE instance_id = ? AND event_type = "
                + "'LATE_RESULT_DISCARDED'").param(loan).query(String.class).single())
                .isEqualTo(ProcessServices.attemptId(claimed.job().id(), 1));
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED") + history(loan, "ASYNC_RESULT_REFUSED")
                + history(loan, "ASYNC_ATTEMPT_FAILED")).isZero();
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM rle_decision WHERE correlation_id = ?").param(loan)
                .query(Integer.class).single()).isZero();
        assertThat(pod[0].services.instance(loan).variables()).doesNotContainKey("assessment");
    }
}
