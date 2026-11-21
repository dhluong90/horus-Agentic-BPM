package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.InvalidStateException;
import io.github.dhluong90.bpm.process.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.api.VersionConflictException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Every request and response body of the Process Engine REST layer, as the controller and its problem handler
 * actually write them, validates against {@code process-engine/api/openapi.yaml} (TR-SEC-08 for problems).
 * The engine services behind the controller are small in-memory test doubles; the JSON views, the controller and
 * the problem details are the real ones.
 */
class ProcessRestContractTest {

    static final Path CONTRACT = Path.of("../api/openapi.yaml");
    private static final OpenApiBodies BODIES = OpenApiBodies.load(CONTRACT);
    private static final String BASE = ProcessController.BASE;
    private static final Instant NOW = Instant.parse("2027-01-04T09:00:00Z");

    private final FakeEngine engine = new FakeEngine();
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ProcessController(new FakeDefinitions(), engine, engine, new FakeQueries(), 1_000_000))
            .setControllerAdvice(new ProcessProblemDetails())
            .build();

    @Test
    void import_definition_response_matches_the_contract() throws Exception {
        MvcResult result = exchange(post(BASE + "/definitions").contentType("application/bpmn+xml")
                .header("Idempotency-Key", "import-1")
                .content("<definitions xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"/>"));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void start_get_and_complete_bodies_match_the_contract() throws Exception {
        MvcResult started = exchange(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"definitionKey":"card-fee-review","businessKey":"APP-1002",
                         "variables":{"applicationId":"APP-1002","fee":{"currency":"VND","amount":50000}}}"""));
        assertThat(started.getResponse().getStatus()).isEqualTo(201);

        exchange(get(BASE + "/instances/" + FakeEngine.INSTANCE));

        MvcResult completed = exchange(post(BASE + "/tasks/" + FakeEngine.TASK + ":complete")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"0\"").header("Idempotency-Key", "c-1")
                .content("{\"result\":{\"approved\":true}}"));
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(completed.getResponse().getContentAsString()).contains("\"status\":\"COMPLETED\"");
    }

    @Test
    void list_and_live_count_bodies_match_the_contract() throws Exception {
        MvcResult definitions = exchange(get(BASE + "/definitions").param("limit", "10"));
        assertThat(definitions.getResponse().getStatus()).isEqualTo(200);

        MvcResult counts = exchange(get(BASE + "/definitions/card-fee-review/versions/1/node-stats"));
        assertThat(counts.getResponse().getStatus()).isEqualTo(200);
        assertThat(counts.getResponse().getContentAsString()).contains("\"WAITING_USER\":3");

        assertProblem(get(BASE + "/definitions/no-such-process/versions/1/node-stats"), 404, "DEFINITION_NOT_FOUND");
    }

    @Test
    void PRE_029_the_window_mode_of_the_node_stats_matches_the_contract() throws Exception {
        MvcResult window = exchange(get(BASE + "/definitions/card-fee-review/versions/1/node-stats")
                .param("mode", "window").param("from", "2026-11-20T10:00:00Z").param("to", "2026-11-20T11:00:00Z"));

        assertThat(window.getResponse().getStatus()).isEqualTo(200);
        tools.jackson.databind.JsonNode body = io.github.dhluong90.bpm.process.internal.json.EngineJson.JSON.readTree(window.getResponse().getContentAsString());
        assertThat(body.get("mode").stringValue()).isEqualTo("window");
        assertThat(body.get("asOf").stringValue()).isEqualTo("2026-11-20T10:50:00Z");
        assertThat(body.get("lagSeconds").asInt()).isEqualTo(600);
        assertThat(body.at("/nodes/review/completed").asInt()).isEqualTo(1200);
        assertThat(body.at("/nodes/review/p95Ms").asLong()).isEqualTo(9_900_000L);
        assertThat(body.at("/nodes/start/p95Ms").isNull()).isTrue();
        assertThat(window.getResponse().getHeader("ETag")).isNull();
    }

    @Test
    void PRE_029_the_window_mode_refuses_a_bad_mode_a_bad_time_and_live_with_a_range() throws Exception {
        assertProblem(get(BASE + "/definitions/card-fee-review/versions/1/node-stats").param("mode", "history"), 400,
                "INPUT_INVALID");
        assertProblem(get(BASE + "/definitions/card-fee-review/versions/1/node-stats").param("mode", "window")
                .param("from", "yesterday"), 400, "INPUT_INVALID");
        assertProblem(get(BASE + "/definitions/card-fee-review/versions/1/node-stats").param("from",
                "2026-11-20T10:00:00Z"), 400, "INPUT_INVALID");
        assertProblem(get(BASE + "/definitions/no-such-process/versions/1/node-stats").param("mode", "window"), 404,
                "DEFINITION_NOT_FOUND");
    }

    @Test
    void TR_SEC_08_completing_a_task_twice_is_a_problem_that_matches_the_contract() throws Exception {
        exchange(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "s-1")
                .content("{\"definitionKey\":\"card-fee-review\"}"));
        exchange(post(BASE + "/tasks/" + FakeEngine.TASK + ":complete").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"").header("Idempotency-Key", "c-1").content("{}"));

        MvcResult again = exchange(post(BASE + "/tasks/" + FakeEngine.TASK + ":complete")
                .contentType(MediaType.APPLICATION_JSON).header("If-Match", "\"1\"").header("Idempotency-Key", "c-2")
                .header("X-Correlation-Id", "corr-42").content("{}"));

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(again.getResponse().getContentAsString()).contains("\"code\":\"INVALID_STATE\"")
                .contains("\"correlationId\":\"corr-42\"");
    }

    @Test
    void PRE_025_the_graph_diff_body_and_its_etag_match_the_contract() throws Exception {
        MvcResult result = exchange(get(BASE + "/definitions/kyc-onboarding/versions/2/diff").param("base", "1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"nodesAdded\":[{\"id\":\"fraud-check\",\"name\":\"Fraud check\"")
                .contains("\"path\":\"/assignee/role\",\"from\":\"ops\",\"to\":\"credit-officer\"")
                .contains("\"path\":\"/fourEyes\",\"from\":null,\"to\":true")
                .contains("\"kind\":\"next\"").doesNotContain("<").doesNotContain("Bounds")
                .contains("\"approvableHash\":\"" + "b".repeat(64) + "\"");
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{64}\"");

        MvcResult again = mvc.perform(get(BASE + "/definitions/kyc-onboarding/versions/2/diff").param("base", "1")
                .header("If-None-Match", etag)).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(304);
        assertThat(again.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(exchange(get(BASE + "/definitions/kyc-onboarding/versions/2/diff")).getResponse()
                .getHeader("ETag")).as("base defaults to the effective version").isEqualTo(etag);
    }

    @Test
    void PRE_177_a_truncated_diff_exposes_no_approvable_hash() throws Exception {
        MvcResult result = exchange(get(BASE + "/definitions/kyc-onboarding/versions/4/diff").param("base", "1"));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"truncated\":true").contains("\"approvableHash\":null")
                .doesNotContain("definitionHash").doesNotContain("a".repeat(64)).doesNotContain("c".repeat(64));
    }

    @Test
    void PRE_176_graph_diff_problems_match_the_contract() throws Exception {
        assertProblem(get(BASE + "/definitions/no-effective/versions/2/diff"), 404, "NO_EFFECTIVE_VERSION");
        assertProblem(get(BASE + "/definitions/kyc-onboarding/versions/3/diff").param("base", "1"), 404,
                "DEFINITION_NOT_FOUND");
        MvcResult bad = mvc.perform(get(BASE + "/definitions/kyc-onboarding/versions/2/diff").param("base", "zero"))
                .andReturn();
        BODIES.assertResponseOf(bad);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void TR_SEC_08_every_problem_the_rest_layer_writes_matches_the_contract() throws Exception {
        // 428: neither businessKey nor Idempotency-Key.
        assertProblem(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON)
                .content("{\"definitionKey\":\"card-fee-review\"}"), 428, "PRECONDITION_REQUIRED");
        // 428: completion without If-Match.
        assertProblem(post(BASE + "/tasks/" + FakeEngine.TASK + ":complete").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "c-1").content("{}"), 428, "PRECONDITION_REQUIRED");
        // 400: not JSON. The request breaks the contract on purpose, so only the response is checked.
        MvcResult notJson = mvc.perform(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "s-x").content("not json")).andReturn();
        BODIES.assertResponseOf(notJson);
        assertThat(notJson.getResponse().getStatus()).isEqualTo(400);
        assertThat(notJson.getResponse().getContentAsString()).contains("\"code\":\"INPUT_INVALID\"");
        // 404: no effective version, unknown instance, unknown task.
        assertProblem(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "s-2")
                .content("{\"definitionKey\":\"no-such-process\"}"), 404, "NO_EFFECTIVE_VERSION");
        assertProblem(get(BASE + "/instances/01JD0000000000000000000099"), 404, "INSTANCE_NOT_FOUND");
        assertProblem(post(BASE + "/tasks/01JD0000000000000000000098:complete").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"0\"").header("Idempotency-Key", "c-9").content("{}"), 404, "TASK_NOT_FOUND");
        // 422: a step failed, with nodeId and reason.
        MvcResult failed = assertProblem(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "s-3").content("{\"definitionKey\":\"step-fails\"}"), 422, "STEP_FAILED");
        assertThat(failed.getResponse().getContentAsString()).contains("\"nodeId\":\"price\"")
                .contains("\"reason\":\"DECISION_NO_MATCH\"");
        // 422: an import that is not executable, with its problems.
        MvcResult invalid = assertProblem(post(BASE + "/definitions").contentType("application/xml")
                .content("<invalid/>"), 422, "VALIDATION_FAILED");
        assertThat(invalid.getResponse().getContentAsString()).contains("\"problems\":[");
        // 422 (PRE-016): an agent step whose guardrail rule set breaks the contract, naming the step and the check.
        MvcResult guardrail = assertProblem(post(BASE + "/definitions").contentType("application/xml")
                .content("<guardrail-broken/>"), 422, "GUARDRAIL_CONTRACT_INVALID");
        assertThat(guardrail.getResponse().getContentAsString()).contains("\"elementId\":\"assess-documents\"")
                .contains("declares no output allow: boolean");
        // 409: a stale task lock version.
        exchange(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "s-4")
                .content("{\"definitionKey\":\"card-fee-review\"}"));
        assertProblem(post(BASE + "/tasks/" + FakeEngine.TASK + ":complete").contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"7\"").header("Idempotency-Key", "c-3").content("{}"), 409, "VERSION_CONFLICT");
        // 500: anything unexpected, with a correlation id only.
        assertProblem(post(BASE + "/instances").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "s-5")
                .content("{\"definitionKey\":\"engine-bug\"}"), 500, "INTERNAL");
    }

    private MvcResult assertProblem(RequestBuilder request, int status, String code) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"" + code + "\"");
        return result;
    }

    private MvcResult exchange(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        BODIES.assertExchange(result);
        return result;
    }

    /** Imports return a fixed draft version; a document named {@code <invalid/>} is not executable. */
    static final class FakeDefinitions implements DefinitionService {

        @Override
        public DefinitionVersion importDefinition(ImportRequest request) {
            if (new String(request.source()).contains("<guardrail-broken/>")) {
                throw new io.github.dhluong90.bpm.process.api.GuardrailContractInvalidException(List.of(
                        new DefinitionProblem("GUARDRAIL_CONTRACT_INVALID", "guardrail rule set "
                                + "agent-output-guardrails v1 declares no output allow: boolean (outputs decision: "
                                + "string)", "ServiceTask", "assess-documents", 0)));
            }
            if (new String(request.source()).contains("<invalid/>")) {
                throw new ValidationFailedException(List.of(
                        new DefinitionProblem("NO_START_EVENT", "The process has no start event", "process", "p1", 3),
                        DefinitionProblem.of("NO_END_EVENT", "The process has no end event", null)));
            }
            return new DefinitionVersion("01JD0000000000000000000001", "card-fee-review", 1, "DRAFT", "Card fee review",
                    "a".repeat(64), SourceFormat.BPMN_XML, "b".repeat(64), 4, null, "user:lan", NOW, null, 0, null, null, null, null, null, null, null, null);
        }

        @Override
        public DefinitionVersion version(String key, int version) {
            throw new DefinitionNotFoundException("No definition " + key + " v" + version);
        }

        /** kyc-onboarding v1 to v2: a fraud check added, the approver's role changed, one link replaced by two. */
        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiff diff(DefinitionRef base, DefinitionRef target) {
            if ("kyc-onboarding".equals(target.key()) && target.version() == 4 && base.version() == 1) {
                io.github.dhluong90.bpm.process.api.GraphDiff full = kycDiff();
                return new io.github.dhluong90.bpm.process.api.GraphDiff(full.definitionKey(), full.base(),
                        new io.github.dhluong90.bpm.process.api.GraphDiff.Side(4, "PENDING_APPROVAL", "c".repeat(64)),
                        full.nodesAdded(), full.nodesRemoved(), full.nodesChanged(), full.edgesAdded(),
                        full.edgesRemoved(), true);
            }
            if (!"kyc-onboarding".equals(target.key()) || target.version() != 2 || base.version() != 1) {
                throw new DefinitionNotFoundException("No definition " + target.key() + " in this tenant");
            }
            return kycDiff();
        }

        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiff diffFromEffective(DefinitionRef target) {
            if ("no-effective".equals(target.key())) {
                throw new NoEffectiveVersionException("Definition no-effective has no effective version");
            }
            return diff(DefinitionRef.of(target.key(), 1), target);
        }

        static io.github.dhluong90.bpm.process.api.GraphDiff kycDiff() {
            return new io.github.dhluong90.bpm.process.api.GraphDiff("kyc-onboarding",
                    new io.github.dhluong90.bpm.process.api.GraphDiff.Side(1, "ACTIVE", "a".repeat(64)),
                    new io.github.dhluong90.bpm.process.api.GraphDiff.Side(2, "PENDING_APPROVAL", "b".repeat(64)),
                    List.of(new io.github.dhluong90.bpm.process.api.GraphDiff.Node("fraud-check", "Fraud check",
                            "serviceTask")),
                    List.of(),
                    List.of(new io.github.dhluong90.bpm.process.api.GraphDiff.NodeChange("approve", "Approve", "userTask",
                            "/assignee/role", "ops", "credit-officer"),
                            new io.github.dhluong90.bpm.process.api.GraphDiff.NodeChange("approve", "Approve",
                                    "userTask", "/fourEyes", null, true)),
                    List.of(new io.github.dhluong90.bpm.process.api.GraphDiff.Edge("fraud__approve", "fraud-check",
                            "Fraud check", "approve", "Approve", "next"),
                            new io.github.dhluong90.bpm.process.api.GraphDiff.Edge("verify__fraud", "verify-docs",
                                    "Verify documents", "fraud-check", "Fraud check", "next")),
                    List.of(new io.github.dhluong90.bpm.process.api.GraphDiff.Edge("verify__approve", "verify-docs",
                            "Verify documents", "approve", "Approve", "next")),
                    false);
        }

        @Override
        public DefinitionExport exportDefinition(String key, int version, SourceFormat format) {
            throw new DefinitionNotFoundException("No definition " + key + " version " + version + " in this tenant");
        }

        @Override
        public DefinitionVersion requestApproval(DefinitionRef ref, long expectedLockVersion, Instant effectiveFrom,
                String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String expectedDefinitionHash,
                String comment, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion reject(DefinitionRef ref, long expectedLockVersion, String reason,
                String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion retire(DefinitionRef ref, long expectedLockVersion, String reason,
                boolean confirmNoVersionInEffect, String idempotencyKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<DefinitionVersion> find(DefinitionRef ref) {
            return Optional.empty();
        }

        @Override
        public List<DefinitionHistoryEntry> history(DefinitionRef ref) {
            throw new UnsupportedOperationException();
        }
    }

    /** One definition key with three tokens waiting at its review step. */
    static final class FakeQueries implements ProcessQueryService {

        @Override
        public Page<DefinitionSummary> definitions(PageRequest page) {
            return new Page<>(List.of(), null);
        }

        @Override
        public Page<InstanceSummary> instances(InstanceQuery query) {
            return new Page<>(List.of(), null);
        }

        @Override
        public NodeCounts liveNodeCounts(String definitionKey, int version) {
            if (!"card-fee-review".equals(definitionKey)) {
                throw new DefinitionNotFoundException("No definition " + definitionKey + " version " + version);
            }
            return new NodeCounts(definitionKey, version, NOW, Map.of("review", Map.of("WAITING_USER", 3L)));
        }

        @Override
        public io.github.dhluong90.bpm.process.api.NodeWindowStats nodeWindowStats(String definitionKey, int version,
                java.time.Instant from, java.time.Instant to) {
            if (!"card-fee-review".equals(definitionKey)) {
                throw new DefinitionNotFoundException("No definition " + definitionKey + " version " + version);
            }
            java.time.Instant end = to == null ? java.time.Instant.parse("2026-11-20T11:00:00Z") : to;
            java.time.Instant start = from == null ? end.minus(java.time.Duration.ofHours(24)) : from;
            return new io.github.dhluong90.bpm.process.api.NodeWindowStats(definitionKey, version, start, end,
                    java.time.Instant.parse("2026-11-20T10:50:00Z"), java.time.Duration.ofMinutes(10),
                    Map.of("review", new io.github.dhluong90.bpm.process.api.NodeWindowStats.NodeWindow(1200, 1200, 0, 0,
                            0, 5_400_000L, 4_800_000L, 9_900_000L), "start", new io.github.dhluong90.bpm.process.api
                            .NodeWindowStats.NodeWindow(3, 0, 0, 0, 0, null, null, null)));
        }
    }

    /** One instance with one user task; enough state to answer every operation the way the engine does. */
    static final class FakeEngine implements ProcessEngine, TaskService {

        static final String INSTANCE = "01JD0000000000000000000010";
        static final String TASK = "01JD0000000000000000000011";

        private ProcessInstance current;

        @Override
        public ProcessInstance start(StartRequest request) {
            switch (request.definitionKey()) {
                case "no-such-process" -> throw new NoEffectiveVersionException("No active version of no-such-process");
                case "step-fails" -> throw new StepFailedException("price", "DECISION_NO_MATCH", "no rule matched");
                case "engine-bug" -> throw new IllegalStateException("unexpected");
                default -> { }
            }
            current = new ProcessInstance(INSTANCE, request.definitionKey(), 1, request.businessKey(), "RUNNING",
                    request.variables(),
                    List.of(new ProcessInstance.Token("01JD0000000000000000000012", "SCOPE", null,
                                    request.definitionKey(), "WAITING_SCOPE"),
                            new ProcessInstance.Token("01JD0000000000000000000013", "FLOW",
                                    "01JD0000000000000000000012", "review", "WAITING_USER")),
                    List.of(new ProcessInstance.Task(TASK, "review", "Review the fee", "role:FEE_REVIEWER", "CLAIMED", 0,
                            "user:lan", NOW, null)),
                    0, "user:lan", NOW, null);
            return current;
        }

        @Override
        public ProcessInstance instance(String instanceId) {
            if (current == null || !current.id().equals(instanceId)) {
                throw new InstanceNotFoundException("No instance " + instanceId);
            }
            return current;
        }

        @Override
        public ProcessInstance complete(String taskId, Map<String, Object> result, long expectedLockVersion,
                String idempotencyKey) {
            if (current == null || !TASK.equals(taskId)) {
                throw new TaskNotFoundException("No task " + taskId);
            }
            ProcessInstance.Task task = current.tasks().get(0);
            if (!"CLAIMED".equals(task.status())) {
                throw new InvalidStateException("Task " + taskId + " is " + task.status());
            }
            if (task.lockVersion() != expectedLockVersion) {
                throw new VersionConflictException("Task " + taskId + " is at lock version " + task.lockVersion());
            }
            Map<String, Object> variables = new LinkedHashMap<>(current.variables());
            variables.put("reviewResult", new HashMap<>(result));
            current = new ProcessInstance(INSTANCE, current.definitionKey(), 1, current.businessKey(), "COMPLETED",
                    variables,
                    List.of(new ProcessInstance.Token("01JD0000000000000000000012", "SCOPE", null,
                            current.definitionKey(), "COMPLETED")),
                    List.of(new ProcessInstance.Task(TASK, "review", "Review the fee", "role:FEE_REVIEWER",
                            "COMPLETED", 1, "user:lan", NOW, "user:lan")),
                    current.lockVersion() + 1, current.startedBy(), current.startedAt(), NOW.plusSeconds(60));
            return current;
        }

        @Override
        public ProcessInstance cancel(String instanceId, long expectedLockVersion, String reason, String idempotencyKey) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public TaskView task(String taskId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public TaskView claim(String taskId, long expectedLockVersion, String idempotencyKey) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public TaskView unclaim(String taskId, long expectedLockVersion, String idempotencyKey) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
