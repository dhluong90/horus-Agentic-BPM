package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.service.AgentMemoryLimits;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor.Claim;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bounded memory of agent steps (#101, PRE-100 to PRE-102), on PostgreSQL with the real Rule Engine.
 *
 * <p>{@code kyc-onboarding}: the agent {@code kyc.extract} reads the applicant's identity document ({@code extract-id}),
 * {@code kyc.screen} screens the name against the sanctions lists ({@code screen-sanctions}), and {@code kyc.assess}
 * rates the onboarding risk ({@code assess-risk}). Each step's guardrail refuses an answer whose confidence is below
 * 80 and sends it to a KYC reviewer. {@code assess-risk} declares as memory the applicant's name and date of birth and
 * the number of sanctions hits, and nothing else. The clock is a {@link TestClock}; no test sleeps.
 */
class AgentMemoryIT {

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String REVIEWER_ROLE = "KYC_REVIEWER";
    private static final String ID_NUMBER = "079090001234";
    private static final MaskingKey HOST_KEY = MaskingKey.of("k1", "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8));
    private static final AtomicInteger TENANTS = new AtomicInteger();

    private static final String DEFINITION = """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:horus="urn:horus-bpm:bpmn:extensions:v1" id="Definitions_kyc-onboarding"
                              targetNamespace="urn:horus-bpm:examples" expressionLanguage="urn:horus-bpm:expr:v1">
              <bpmn:process id="kyc-onboarding" name="KYC onboarding" isExecutable="true">
                <bpmn:startEvent id="start"/>
                <bpmn:serviceTask id="extract-id" horus:type="agentTask" horus:agent="kyc.extract"
                                  horus:resultVariable="identity">
                  <bpmn:extensionElements>
                    <horus:input name="documentId" value="${instance.documentId}"/>
                    <horus:outputSchema>{"type": "object", "additionalProperties": false,
                      "required": ["fullName", "dob", "idNumber", "confidence"],
                      "properties": {"fullName": {"type": "string"}, "dob": {"type": "string"},
                        "idNumber": {"type": "string", "mask": true}, "address": {"type": "string"},
                        "confidence": {"type": "integer", "minimum": 0, "maximum": 100}}}</horus:outputSchema>
                    <horus:guardrail decision="kyc-extract-guardrails" onViolation="route-to-human"
                                     reviewer="role:KYC_REVIEWER"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="screen-sanctions" horus:type="agentTask" horus:agent="kyc.screen"
                                  horus:resultVariable="screening">
                  <bpmn:extensionElements>
                    <horus:input name="fullName" value="${instance.identity.fullName}"/>
                    <horus:outputSchema>{"type": "object", "additionalProperties": false,
                      "required": ["hits", "listVersion", "confidence"],
                      "properties": {"hits": {"type": "integer", "minimum": 0}, "listVersion": {"type": "string"},
                        "confidence": {"type": "integer", "minimum": 0, "maximum": 100}}}</horus:outputSchema>
                    <horus:guardrail decision="kyc-screening-guardrails" onViolation="route-to-human"
                                     reviewer="role:KYC_REVIEWER"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:serviceTask id="assess-risk" horus:type="agentTask" horus:agent="kyc.assess"
                                  horus:timeout="PT0.2S" horus:resultVariable="risk">
                  <bpmn:extensionElements>
                    <horus:input name="customerId" value="${instance.customerId}"/>
                    <horus:outputSchema>{"type": "object", "additionalProperties": false,
                      "required": ["riskBand", "confidence"],
                      "properties": {"riskBand": {"type": "string", "enum": ["LOW", "MEDIUM", "HIGH"]},
                        "confidence": {"type": "integer", "minimum": 0, "maximum": 100}}}</horus:outputSchema>
                    <horus:guardrail decision="kyc-risk-guardrails" onViolation="route-to-human"
                                     reviewer="role:KYC_REVIEWER"/>
                    <horus:memory from="extract-id" fields="fullName,dob" as="applicant"/>
                    <horus:memory from="screen-sanctions" fields="hits" as="screening"/>
                    <horus:retry max="1" backoff="PT1S"/>
                  </bpmn:extensionElements>
                </bpmn:serviceTask>
                <bpmn:userTask id="approve" horus:assignee="role:KYC_OFFICER" horus:resultVariable="decision"/>
                <bpmn:endEvent id="end"/>
                <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="extract-id"/>
                <bpmn:sequenceFlow id="f2" sourceRef="extract-id" targetRef="screen-sanctions"/>
                <bpmn:sequenceFlow id="f3" sourceRef="screen-sanctions" targetRef="assess-risk"/>
                <bpmn:sequenceFlow id="f4" sourceRef="assess-risk" targetRef="approve"/>
                <bpmn:sequenceFlow id="f5" sourceRef="approve" targetRef="end"/>
              </bpmn:process>
            </bpmn:definitions>
            """;

    private static final String APPLICANT = "<horus:memory from=\"extract-id\" fields=\"fullName,dob\" as=\"applicant\"/>";
    private static final String SCREENING = "<horus:memory from=\"screen-sanctions\" fields=\"hits\" as=\"screening\"/>";

    private final TestClock clock = TestClock.at(T0);
    private final String tenant = "bank-kyc-" + TENANTS.incrementAndGet();
    private final List<TestEngine> engines = new ArrayList<>();
    /** Rule sets live in the shared database: published once per test's tenant. */
    private boolean published;
    private final StubAgentAdapter extractor = StubAgentAdapter.of("kyc.extract", invocation -> AdapterResult.completed(
            Map.of("fullName", "Nguyen Van A", "dob", "1990-04-12", "idNumber", ID_NUMBER, "address",
                    "12 Le Loi, Q1", "confidence", 97)));
    private StubAgentAdapter screener = StubAgentAdapter.of("kyc.screen", invocation -> AdapterResult.completed(
            Map.of("hits", 0, "listVersion", "2026-11-15", "confidence", 99)));
    private StubAgentAdapter assessor = StubAgentAdapter.of("kyc.assess", invocation -> AdapterResult.completed(
            Map.of("riskBand", "LOW", "confidence", 90)));

    @BeforeEach
    void clean() {
        TestEngine.reset();
    }

    @AfterEach
    void stop() {
        engines.forEach(TestEngine::close);
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------

    private TestEngine replica(AgentMemoryLimits limits) {
        TestEngine engine = TestEngine.replica("pod-1", clock).withRuleEngine()
                .decisionPort(StarterDecisionPorts::over).agents(extractor, screener, assessor)
                .inputMasker((tenantId, plain) -> HOST_KEY.forTenant(tenantId).fingerprint(plain))
                .memoryLimits(limits).build();
        engines.add(engine);
        pipeline(engine);
        guardrails(engine);
        return engine;
    }

    private void guardrails(TestEngine engine) {
        if (published) {
            return;
        }
        published = true;
        publish(engine, "kyc-extract-guardrails", "fullName: string, dob: string, idNumber: string, "
                + "address: string, confidence: integer");
        publish(engine, "kyc-screening-guardrails", "hits: integer, listVersion: string, confidence: integer");
        publish(engine, "kyc-risk-guardrails", "riskBand: string, confidence: integer");
    }

    private TestEngine replica() {
        return replica(AgentMemoryLimits.DEFAULTS);
    }

    private void pipeline(TestEngine engine) {
        engine.identity.as(tenant, "pipeline", "processes:deploy", "processes:start", "processes:read",
                "processes:task:work");
    }

    /** A guardrail rule set on the step's answer: a confidence below 80 is refused. */
    private void publish(TestEngine engine, String name, String inputs) {
        StringBuilder declared = new StringBuilder();
        for (String input : inputs.split(", ")) {
            String[] parts = input.split(": ");
            declared.append("  - { name: ").append(parts[0]).append(", type: ").append(parts[1]).append(" }\n");
        }
        String yaml = "schemaVersion: 1\nruleSet: " + name + "\nhitPolicy: first\ninputs:\n" + declared
                + "outputs:\n  - { name: allow, type: boolean }\n  - { name: reasons, type: string }\n"
                + "rules:\n  - id: R1\n    when: { confidence: \"< 80\" }\n"
                + "    then: { allow: \"false\", reasons: \"'LOW_CONFIDENCE'\" }\n"
                + "  - id: R2\n    when: {}\n    then: { allow: \"true\", reasons: \"''\" }\n";
        engine.ruleIdentity.as(tenant, "lan");
        engine.rules.versionService().importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                yaml.getBytes(StandardCharsets.UTF_8)));
        // The guardrail reads the whole answer, personal data included; its owner acknowledges that once.
        List<io.github.dhluong90.bpm.rules.api.AcknowledgedWarning> personal = new ArrayList<>();
        for (String input : List.of("fullName", "dob", "idNumber", "address")) {
            if (inputs.contains(input + ":")) {
                personal.add(new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning("UNMASKED_PERSONAL_DATA", input));
            }
        }
        long requested = engine.rules.versionService().requestPublish(name, 1,
                new WriteOptions(null, null, null, null, personal)).lockVersion();
        engine.ruleIdentity.as(tenant, "minh");
        engine.rules.versionService().approve(name, 1, new WriteOptions(null, requested, null, null, personal));
        engine.ruleIdentity.as(null, null);
    }

    private static void deploy(TestEngine engine, String xml) {
        engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                xml.strip().getBytes(StandardCharsets.UTF_8), null));
    }

    /** The definition with assess-risk's two entries replaced by the given memory. */
    private static String withMemory(String memory) {
        return DEFINITION.replace(APPLICANT, memory).replace(SCREENING, "");
    }

    private static List<DefinitionProblem> refused(TestEngine engine, String xml) {
        try {
            deploy(engine, xml);
        } catch (ValidationFailedException e) {
            return e.problems();
        }
        throw new AssertionError("The definition was accepted");
    }

    private static String onboard(TestEngine engine, String customerId) {
        return engine.services.start(new StartRequest("kyc-onboarding", customerId, Map.of("customerId", customerId,
                "documentId", "DOC-" + customerId, "channel", "BRANCH"), null)).id();
    }

    /** Claims the one due agent job and runs it on this thread. */
    private static java.util.Optional<StepCompletion.Outcome> answerNext(TestEngine engine) {
        List<Claim> claims = engine.async.claim(JobStore.ASYNC_AGENT, 16);
        assertThat(claims).hasSize(1);
        return engine.async.runNow(claims.get(0));
    }

    private static Map<String, Object> details(String instanceId, String type, String node) {
        return Values.readMap(TestEngine.jdbc().sql("SELECT details::text FROM pre_history WHERE instance_id = ? AND "
                + "event_type = ? AND node_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1").params(instanceId, type, node)
                .query(String.class).single());
    }

    private static Map<String, Object> job(String instanceId, String node) {
        return TestEngine.jdbc().sql("SELECT status, attempt FROM pre_timer WHERE instance_id = ? AND node_id = ?")
                .params(instanceId, node).query().singleRow();
    }

    private static int definitions() {
        return TestEngine.jdbc().sql("SELECT count(*) FROM pre_definition").query(Integer.class).single();
    }

    private static Map<String, Object> memory(String applicantName, String dob, long hits) {
        return Map.of("applicant", Map.of("fullName", applicantName, "dob", dob), "screening", Map.of("hits", hits));
    }

    // ---- PRE-100 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_100_an_agent_step_receives_only_the_declared_fields_the_same_on_every_attempt() throws Exception {
        // Given the identity and the screening were recorded, and the risk assessor never answers its first attempt
        CountDownLatch never = new CountDownLatch(1);
        AtomicInteger assessments = new AtomicInteger();
        assessor = StubAgentAdapter.of("kyc.assess", invocation -> {
            if (assessments.incrementAndGet() == 1) {
                try {
                    never.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return AdapterResult.completed(Map.of("riskBand", "LOW", "confidence", 90));
        });
        TestEngine pod = replica();
        deploy(pod, DEFINITION);
        String kyc = onboard(pod, "CUS-2027-0100");
        answerNext(pod);
        answerNext(pod);

        // When assess-risk's first attempt times out after 0.2 s and the retry a second later answers
        assertThat(pod.async.pollOnce()).isEqualTo(1);
        assertThat(pod.async.awaitIdle(Duration.ofSeconds(30))).isTrue();
        assertThat(job(kyc, "assess-risk")).containsEntry("status", "DUE").containsEntry("attempt", 1);
        clock.advance(Duration.ofSeconds(1));
        assertThat(answerNext(pod)).contains(StepCompletion.Outcome.ACCEPTED);

        // Then both attempts sent the same three fields, with a new model call key and the same idempotency key
        List<AgentInvocation> attempts = assessor.invocations();
        assertThat(attempts).hasSize(2);
        assertThat(attempts).extracting(AgentInvocation::memory).containsOnly(
                memory("Nguyen Van A", "1990-04-12", 0L));
        assertThat(attempts.get(0).modelCallKey()).isNotEqualTo(attempts.get(1).modelCallKey());
        assertThat(attempts.get(0).idempotencyKey()).isEqualTo(attempts.get(1).idempotencyKey());
        assertThat(attempts.get(0).inputs()).isEqualTo(Map.of("customerId", "CUS-2027-0100"));
        // And address, idNumber and listVersion were never sent
        assertThat(attempts.toString() + attempts.get(0).memory() + attempts.get(1).memory())
                .doesNotContain("Le Loi", ID_NUMBER, "2026-11-15");
        // And history records the resolved memory when the step parked and with the answer
        assertThat(details(kyc, "ASYNC_STEP_SCHEDULED", "assess-risk")).containsEntry("memory",
                memory("Nguyen Van A", "1990-04-12", 0L)).doesNotContainKey("memoryMissing");
        assertThat(details(kyc, "ASYNC_RESULT_ACCEPTED", "assess-risk")).containsEntry("memory",
                memory("Nguyen Van A", "1990-04-12", 0L));
        assertThat(pod.services.instance(kyc).variables()).containsEntry("risk", Map.of("riskBand", "LOW",
                "confidence", 90L));
    }

    @Test
    void PRE_100_memory_carries_the_reviewers_answer_never_the_refused_one() {
        // Given the screener answers no hit with a confidence of 40, which its guardrail refuses
        screener = StubAgentAdapter.of("kyc.screen", invocation -> AdapterResult.completed(
                Map.of("hits", 0, "listVersion", "2026-11-15", "confidence", 40)));
        TestEngine pod = replica();
        deploy(pod, DEFINITION);
        String kyc = onboard(pod, "CUS-2027-0101");
        answerNext(pod);
        assertThat(answerNext(pod)).contains(StepCompletion.Outcome.REFUSED);

        // When a KYC reviewer finds one hit and completes the review with it
        String taskId = TestEngine.jdbc().sql("SELECT id FROM pre_task WHERE instance_id = ? AND node_id = "
                + "'screen-sanctions'").param(kyc).query(String.class).single();
        pod.identity.as(tenant, "thu", "processes:task:work", "processes:read");
        pod.identity.roles = Set.of(REVIEWER_ROLE);
        TaskView claimed = pod.services.claim(taskId, pod.services.task(taskId).lockVersion(), "thu-claim");
        pod.services.complete(taskId, Map.of("hits", 1, "listVersion", "2026-11-15", "confidence", 100),
                "Partial match on the national list", claimed.lockVersion(), "thu-complete");
        pipeline(pod);
        answerNext(pod);

        // Then assess-risk received the reviewer's hit, never the refused answer
        assertThat(assessor.invocations()).singleElement()
                .satisfies(i -> assertThat(i.memory()).isEqualTo(memory("Nguyen Van A", "1990-04-12", 1L)));
        assertThat(TestEngine.jdbc().sql("SELECT source FROM pre_step_result WHERE instance_id = ? AND node_id = "
                + "'screen-sanctions'").param(kyc).query(String.class).single()).isEqualTo("REVIEW");
    }

    // ---- PRE-101 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_101_memory_naming_an_unknown_or_non_upstream_step_or_a_wildcard_is_refused_at_import() {
        TestEngine pod = replica();
        String nonUpstream = withMemory("<horus:memory from=\"extract-id\" fields=\"fullName\" as=\"applicant\"/>")
                .replace("<horus:input name=\"fullName\" value=\"${instance.identity.fullName}\"/>",
                        "<horus:input name=\"fullName\" value=\"${instance.identity.fullName}\"/>"
                                + "<horus:memory from=\"assess-risk\" fields=\"riskBand\" as=\"risk\"/>");

        // (a) an unknown step, (b) a step that is not upstream, (c) a wildcard
        assertThat(refused(pod, withMemory("<horus:memory from=\"extract-idd\" fields=\"fullName\" as=\"applicant\"/>")))
                .singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("AGENT_MEMORY_INVALID");
                    assertThat(p.elementId()).isEqualTo("assess-risk");
                    assertThat(p.message()).contains("applicant", "extract-idd", "does not have");
                });
        assertThat(refused(pod, nonUpstream)).singleElement().satisfies(p -> {
            assertThat(p.elementId()).isEqualTo("screen-sanctions");
            assertThat(p.message()).contains("risk", "assess-risk", "not upstream");
        });
        assertThat(refused(pod, withMemory("<horus:memory from=\"extract-id\" fields=\"*\" as=\"applicant\"/>")))
                .singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("AGENT_MEMORY_INVALID");
                    assertThat(p.message()).contains("wildcards are never allowed");
                });

        // Then nothing was written
        assertThat(definitions()).isZero();
    }

    @Test
    void PRE_101_more_entries_than_the_host_allows_are_refused_at_import() {
        // Given (d) 17 entries against the default 16, and 2 entries on a host that allows 1
        StringBuilder seventeen = new StringBuilder();
        for (int i = 1; i <= 17; i++) {
            seventeen.append("<horus:memory from=\"extract-id\" fields=\"fullName\" as=\"a").append(i).append("\"/>");
        }
        TestEngine pod = replica();
        TestEngine strict = replica(new AgentMemoryLimits(1, AgentMemoryLimits.MAX_BYTES));

        // Then both are refused naming the step and the bound, and nothing is written
        assertThat(refused(pod, withMemory(seventeen.toString()))).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("AGENT_MEMORY_INVALID");
            assertThat(p.elementId()).isEqualTo("assess-risk");
            assertThat(p.message()).isEqualTo("17 memory entries, at most 16 (agents.memory.max-entries)");
        });
        assertThat(refused(strict, DEFINITION)).singleElement().satisfies(p ->
                assertThat(p.message()).isEqualTo("2 memory entries, at most 1 (agents.memory.max-entries)"));
        assertThat(definitions()).isZero();
    }

    @Test
    void PRE_101_memory_above_the_byte_limit_fails_the_step_before_any_call() {
        // Given (e) a host that allows 64 bytes of memory, while the three fields take 83
        TestEngine pod = replica(new AgentMemoryLimits(AgentMemoryLimits.MAX_ENTRIES, 64));
        deploy(pod, DEFINITION);
        String kyc = onboard(pod, "CUS-2027-0105");
        answerNext(pod);

        // When screen-sanctions' answer moves the instance to assess-risk
        answerNext(pod);

        // Then the step failed in the same transaction with AGENT_MEMORY_TOO_LARGE: its job is FAILED at attempt 0,
        // never claimed, the assessor was never called, and one incident is open
        assertThat(job(kyc, "assess-risk")).containsEntry("status", "FAILED").containsEntry("attempt", 0);
        assertThat(details(kyc, "ASYNC_ATTEMPT_FAILED", "assess-risk")).containsEntry("errorCode",
                "AGENT_MEMORY_TOO_LARGE").containsEntry("retryable", false).containsEntry("final", true);
        assertThat(pod.services.openIncidents(kyc)).singleElement().satisfies(i -> {
            assertThat(i.errorCode()).isEqualTo("AGENT_MEMORY_TOO_LARGE");
            assertThat(i.nodeId()).isEqualTo("assess-risk");
            assertThat(i.causeSummary()).contains("at most 64").doesNotContain("Nguyen");
        });
        clock.advance(Duration.ofMinutes(10));
        assertThat(pod.async.claim(JobStore.ASYNC_AGENT, 16)).isEmpty();
        assertThat(assessor.invocations()).isEmpty();
        assertThat(pod.services.instance(kyc).tokens()).anySatisfy(t -> {
            assertThat(t.nodeId()).isEqualTo("assess-risk");
            assertThat(t.status()).isEqualTo("WAITING_ASYNC");
        });
    }

    @Test
    void PRE_101_a_required_entry_without_a_recorded_result_fails_the_step_and_an_optional_one_is_listed_missing() {
        // Given (f) screening runs only for branch onboarding; online onboarding goes from extract-id to assess-risk
        String branching = DEFINITION
                .replace("<bpmn:sequenceFlow id=\"f2\" sourceRef=\"extract-id\" targetRef=\"screen-sanctions\"/>",
                        "<bpmn:exclusiveGateway id=\"channel\" default=\"online\"/>"
                                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"extract-id\" targetRef=\"channel\"/>"
                                + "<bpmn:sequenceFlow id=\"branch\" sourceRef=\"channel\" targetRef=\"screen-sanctions\">"
                                + "<bpmn:conditionExpression>channel == 'BRANCH'</bpmn:conditionExpression>"
                                + "</bpmn:sequenceFlow>"
                                + "<bpmn:sequenceFlow id=\"online\" sourceRef=\"channel\" targetRef=\"assess-risk\"/>");
        TestEngine pod = replica();
        deploy(pod, branching);
        String required = pod.services.start(new StartRequest("kyc-onboarding", "CUS-2027-0106", Map.of(
                "customerId", "CUS-2027-0106", "documentId", "DOC-0106", "channel", "ONLINE"), null)).id();

        // When an online onboarding reaches assess-risk
        answerNext(pod);

        // Then the step failed with AGENT_MEMORY_MISSING before any call, naming the entry, with an incident
        assertThat(job(required, "assess-risk")).containsEntry("status", "FAILED");
        assertThat(details(required, "ASYNC_ATTEMPT_FAILED", "assess-risk")).containsEntry("errorCode",
                "AGENT_MEMORY_MISSING").containsEntry("memoryEntry", "screening");
        assertThat(pod.services.openIncidents(required)).singleElement()
                .satisfies(i -> assertThat(i.errorCode()).isEqualTo("AGENT_MEMORY_MISSING"));
        assertThat(assessor.invocations()).isEmpty();

        // And with required="false" the entry is left out and listed as missing
        deploy(pod, branching.replace("as=\"screening\"/>", "as=\"screening\" required=\"false\"/>")
                .replace("id=\"kyc-onboarding\"", "id=\"kyc-onboarding-online\""));
        String optional = pod.services.start(new StartRequest("kyc-onboarding-online", "CUS-2027-0107", Map.of(
                "customerId", "CUS-2027-0107", "documentId", "DOC-0107", "channel", "ONLINE"), null)).id();
        answerNext(pod);
        answerNext(pod);
        assertThat(assessor.invocations()).singleElement().satisfies(i -> assertThat(i.memory()).isEqualTo(
                Map.of("applicant", Map.of("fullName", "Nguyen Van A", "dob", "1990-04-12"))));
        assertThat(details(optional, "ASYNC_STEP_SCHEDULED", "assess-risk"))
                .containsEntry("memoryMissing", List.of("screening"));
    }

    // ---- PRE-102 ----------------------------------------------------------------------------------------------

    @Test
    void PRE_102_a_masked_field_without_include_masked_is_refused_at_import() {
        TestEngine pod = replica();

        assertThat(refused(pod, withMemory("<horus:memory from=\"extract-id\" fields=\"fullName,idNumber\" "
                + "as=\"applicant\"/>"))).singleElement().satisfies(p -> {
                    assertThat(p.code()).isEqualTo("AGENT_MEMORY_MASKED_FIELD");
                    assertThat(p.elementId()).isEqualTo("assess-risk");
                    assertThat(p.message()).contains("idNumber", "masked", "includeMasked");
                });
        assertThat(definitions()).isZero();
    }

    @Test
    void PRE_102_include_masked_sends_the_plain_value_hashes_it_in_history_and_shows_in_the_canonical_form() {
        // Given the entry lists idNumber with includeMasked
        TestEngine pod = replica();
        String declared = withMemory("<horus:memory from=\"extract-id\" fields=\"fullName,idNumber\" "
                + "as=\"applicant\" includeMasked=\"true\"/>");
        deploy(pod, declared);

        // When an onboarding runs to assess-risk
        String kyc = onboard(pod, "CUS-2027-0112");
        answerNext(pod);
        answerNext(pod);
        answerNext(pod);

        // Then the assessor received the plain id number, and history keeps only its keyed fingerprint
        assertThat(assessor.invocations()).singleElement().satisfies(i -> assertThat(i.memory()).isEqualTo(
                Map.of("applicant", Map.of("fullName", "Nguyen Van A", "idNumber", ID_NUMBER))));
        String fingerprint = HOST_KEY.forTenant(tenant).fingerprint(ID_NUMBER);
        assertThat(details(kyc, "ASYNC_STEP_SCHEDULED", "assess-risk")).containsEntry("memory",
                Map.of("applicant", Map.of("fullName", "Nguyen Van A", "idNumber", fingerprint)));
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND details::text "
                + "LIKE '%" + ID_NUMBER + "%'").param(kyc).query(Integer.class).single()).isZero();
        // And the stored definition, which the hash and the approval cover, shows includeMasked on the step
        assertThat(TestEngine.jdbc().sql("SELECT definition_json::text FROM pre_definition WHERE key_name = "
                + "'kyc-onboarding'").query(String.class).single()).contains("\"includeMasked\": true");
    }

    @Test
    void PRE_102_memory_reads_only_the_results_of_its_own_instance_and_tenant() {
        // Given two onboardings of the same definition whose identity documents name different applicants
        StubAgentAdapter perCustomer = StubAgentAdapter.of("kyc.extract", invocation -> AdapterResult.completed(Map.of(
                "fullName", "DOC-CUS-2027-0121".equals(invocation.inputs().get("documentId")) ? "Nguyen Van A"
                        : "Tran Thi B", "dob", "1990-04-12", "idNumber", ID_NUMBER, "confidence", 97)));
        TestEngine pod = TestEngine.replica("pod-1", clock).withRuleEngine().decisionPort(StarterDecisionPorts::over)
                .agents(perCustomer, screener, assessor).build();
        engines.add(pod);
        pipeline(pod);
        guardrails(pod);
        deploy(pod, DEFINITION);
        String first = onboard(pod, "CUS-2027-0121");
        String second = onboard(pod, "CUS-2027-0122");

        // When both run to assess-risk, one step of each at a time
        for (int step = 0; step < 3; step++) {
            List<Claim> claims = pod.async.claim(JobStore.ASYNC_AGENT, 16);
            assertThat(claims).hasSize(2);
            claims.forEach(pod.async::runNow);
        }

        // Then each assessment received its own applicant
        Map<String, String> names = new java.util.HashMap<>();
        assessor.invocations().forEach(i -> names.put(i.instanceId(), String.valueOf(((Map<?, ?>) i.memory()
                .get("applicant")).get("fullName"))));
        assertThat(names).isEqualTo(Map.of(first, "Nguyen Van A", second, "Tran Thi B"));
        // And the recorded results are read per instance and per tenant only
        JdbcProcessStore store = new JdbcProcessStore(TestEngine.dataSource());
        assertThat(store.stepResults(tenant, first, List.of("extract-id"))).hasSize(1);
        assertThat(store.stepResults("bank-other", first, List.of("extract-id", "screen-sanctions"))).isEmpty();
        assertThat(TestEngine.jdbc().sql("SELECT count(DISTINCT instance_id) FROM pre_step_result WHERE tenant_id = ?")
                .param(tenant).query(Integer.class).single()).isEqualTo(2);
        // And steps no later step reads are not kept
        assertThat(TestEngine.jdbc().sql("SELECT count(*) FROM pre_step_result WHERE node_id = 'assess-risk'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void PRE_102_memory_that_reads_a_variable_or_another_instance_is_refused_at_import() {
        TestEngine pod = replica();

        for (String from : List.of("${instance.customer}", "instance.identity", "other-instance:extract-id")) {
            assertThat(refused(pod, withMemory("<horus:memory from=\"" + from + "\" fields=\"fullName\" "
                    + "as=\"applicant\"/>"))).as(from).isNotEmpty()
                    .allSatisfy(p -> assertThat(p.code()).isEqualTo("AGENT_MEMORY_INVALID"));
        }
        assertThat(definitions()).isZero();
    }

}
