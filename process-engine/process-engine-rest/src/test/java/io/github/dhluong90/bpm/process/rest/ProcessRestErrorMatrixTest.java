package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The REST layer over in-memory services: every code of the error matrix is an RFC 9457 problem with a stable
 * {@code code}, a {@code correlationId} and a {@code type} URI, and no answer carries a stack trace, a class name or
 * SQL (TR-SEC-08); preconditions and list parameters are checked before the engine runs.
 */
class ProcessRestErrorMatrixTest {

    private static final String BASE = "/api/bpm/processes/v1";
    private static final String INSTANCE = "01JD2X5K9T1Q4N8V2M7B3C6D0E";
    private static final Instant STARTED = Instant.parse("2027-02-01T09:00:00Z");

    private final Fake fake = new Fake();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProcessController(fake, fake, fake, fake, 1_048_576))
                .setControllerAdvice(new ProcessProblemDetails()).build();
    }

    @Test
    void TR_SEC_08_every_code_of_the_error_matrix_is_a_problem_with_code_correlation_id_and_type() throws Exception {
        for (Map.Entry<String, org.springframework.http.HttpStatus> entry : ProcessProblemDetails.STATUS.entrySet()) {
            String code = entry.getKey();
            fake.failure = () -> new ProcessException(code, "Refused for the test");

            MockHttpServletResponse response = perform(get(BASE + "/instances/" + INSTANCE));

            assertThat(response.getStatus()).as(code).isEqualTo(entry.getValue().value());
            assertThat(response.getContentType()).as(code).isEqualTo("application/problem+json");
            JsonNode body = json(response);
            assertThat(body.get("code").stringValue()).isEqualTo(code);
            assertThat(body.get("status").intValue()).isEqualTo(entry.getValue().value());
            assertThat(body.get("type").stringValue())
                    .endsWith("Common-Error-Codes#" + code.toLowerCase(Locale.ROOT));
            assertThat(body.get("correlationId").stringValue()).hasSize(26);
            assertThat(body.get("detail").stringValue()).isEqualTo("Refused for the test");
            assertNoInternals(response);
        }
    }

    @Test
    void TR_SEC_08_codes_that_carry_details_and_headers_keep_them() throws Exception {
        fake.failure = () -> new StepFailedException("price", "DECISION_NO_MATCH", "see the engine log");
        JsonNode step = json(perform(get(BASE + "/instances/" + INSTANCE)));
        assertThat(step.get("nodeId").stringValue()).isEqualTo("price");
        assertThat(step.get("reason").stringValue()).isEqualTo("DECISION_NO_MATCH");

        fake.failure = () -> new ValidationFailedException(List.of(DefinitionProblem.of("HANDLER_NOT_REGISTERED",
                "No ServiceTaskHandler named card-profile is registered in this host", "load-profile")));
        JsonNode validation = json(perform(get(BASE + "/instances/" + INSTANCE)));
        assertThat(validation.at("/problems/0/elementId").stringValue()).isEqualTo("load-profile");

        fake.failure = () -> new ProcessException("IDEMPOTENCY_IN_FLIGHT", "A request with this key is running");
        assertThat(perform(get(BASE + "/instances/" + INSTANCE)).getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void PRE_360_control_a_full_start_backlog_answers_429_with_retry_after_and_a_problem_code() throws Exception {
        fake.failure = () -> new io.github.dhluong90.bpm.process.api.StartArrivalsFullException(
                "Process account-opening already holds 3 arrivals that wait for the other start events", 86_400);

        MockHttpServletResponse response = perform(get(BASE + "/instances/" + INSTANCE));

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("86400");
        JsonNode problem = json(response);
        assertThat(problem.get("code").stringValue()).isEqualTo("START_ARRIVALS_FULL");
        assertThat(problem.get("status").intValue()).isEqualTo(429);
    }

    @Test
    void TR_SEC_08_an_unexpected_failure_is_500_INTERNAL_with_a_correlation_id_only() throws Exception {
        fake.failure = () -> new IllegalStateException(
                "ERROR: duplicate key value violates unique constraint SELECT * FROM pre_instance WHERE tenant_id = 'bank-a'");

        MockHttpServletResponse response = perform(get(BASE + "/instances/" + INSTANCE));

        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = json(response);
        assertThat(body.get("code").stringValue()).isEqualTo("INTERNAL");
        assertThat(body.has("detail")).isFalse();
        assertThat(body.get("correlationId").stringValue()).hasSize(26);
        assertThat(response.getContentAsString()).doesNotContain("pre_instance", "SELECT", "bank-a");
        assertNoInternals(response);
    }

    @Test
    void PRE_071_a_local_handler_that_throws_on_start_is_500_INTERNAL_with_a_correlation_id_only() throws Exception {
        // Given the start's local step limits.reserve throws with the customer's national id in its message
        fake.failure = () -> new io.github.dhluong90.bpm.process.internal.service.LocalHandlerException(
                "limits.reserve", "reserve-limit", new IllegalStateException("limit of customer 079088001234 is frozen"));

        MockHttpServletResponse response = perform(post(BASE + "/instances")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).header("Idempotency-Key", "ln-0042")
                .content("{\"definitionKey\":\"loan-disbursement\",\"businessKey\":\"LN-2026-0042\","
                        + "\"variables\":{\"amountMinor\":50000000,\"currency\":\"VND\"}}"));

        assertThat(response.getStatus()).isEqualTo(500);
        JsonNode body = json(response);
        assertThat(body.get("code").stringValue()).isEqualTo("INTERNAL");
        assertThat(body.has("detail")).isFalse();
        assertThat(body.get("correlationId").stringValue()).hasSize(26);
        assertThat(response.getContentAsString()).doesNotContain("079088001234", "limits.reserve", "IllegalState");
        assertNoInternals(response);
    }

    @Test
    void TR_SEC_08_a_valid_correlation_id_is_echoed_and_an_unsafe_one_is_replaced() throws Exception {
        fake.failure = () -> new ProcessException("INSTANCE_NOT_FOUND", "No instance");

        JsonNode echoed = json(perform(get(BASE + "/instances/" + INSTANCE).header("X-Correlation-Id", "branch-42.req-7")));
        JsonNode replaced = json(perform(get(BASE + "/instances/" + INSTANCE)
                .header("X-Correlation-Id", "<script>alert(1)</script>")));

        assertThat(echoed.get("correlationId").stringValue()).isEqualTo("branch-42.req-7");
        assertThat(replaced.get("correlationId").stringValue()).hasSize(26).doesNotContain("<");
    }

    @Test
    void TR_IDEM_01_cancel_needs_if_match_and_an_idempotency_key_before_the_engine_runs() throws Exception {
        MockHttpServletResponse noIfMatch = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("Idempotency-Key", "cancel-1"));
        MockHttpServletResponse noKey = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("If-Match", "\"2\""));

        assertThat(noIfMatch.getStatus()).isEqualTo(428);
        assertThat(json(noIfMatch).get("code").stringValue()).isEqualTo("PRECONDITION_REQUIRED");
        assertThat(noKey.getStatus()).isEqualTo(428);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void TR_IDEM_03_cancel_passes_the_quoted_or_weak_lock_version_and_the_reason_to_the_engine() throws Exception {
        MockHttpServletResponse response = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("If-Match", "W/\"7\"").header("Idempotency-Key", "cancel-2").contentType("application/json")
                .content("{\"reason\":\"Customer withdrew the card application\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isEqualTo("\"8\"");
        assertThat(fake.calls).containsExactly("cancel " + INSTANCE + " 7 Customer withdrew the card application cancel-2");
    }

    @Test
    void TR_IDEM_03_a_cancel_reason_that_is_not_a_string_or_a_lock_version_that_is_not_a_number_is_400() throws Exception {
        MockHttpServletResponse badReason = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("If-Match", "\"7\"").header("Idempotency-Key", "cancel-3").contentType("application/json")
                .content("{\"reason\":42}"));
        MockHttpServletResponse badVersion = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("If-Match", "\"seven\"").header("Idempotency-Key", "cancel-4"));

        assertThat(json(badReason).get("code").stringValue()).isEqualTo("INPUT_INVALID");
        assertThat(json(badVersion).get("code").stringValue()).isEqualTo("INPUT_INVALID");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void TR_SEC_08_a_cancel_body_above_the_json_limit_is_413_before_it_is_read() throws Exception {
        String big = "{\"reason\":\"" + "x".repeat(300_000) + "\"}";

        MockHttpServletResponse response = perform(post(BASE + "/instances/" + INSTANCE + ":cancel")
                .header("If-Match", "\"1\"").header("Idempotency-Key", "cancel-5").contentType("application/json")
                .content(big));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(json(response).get("code").stringValue()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void PRE_218_the_task_list_has_an_etag_that_follows_its_page_and_content_and_never_crosses_callers() throws Exception {
        // When the list is read, it carries an ETag
        MockHttpServletResponse first = perform(get(BASE + "/tasks"));
        String etag = first.getHeader("ETag");
        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(etag).matches("\"[0-9a-f]{32}\"");

        // Then the same tag is 304 with no body
        MockHttpServletResponse unchanged = perform(get(BASE + "/tasks").header("If-None-Match", etag));
        assertThat(unchanged.getStatus()).isEqualTo(304);
        assertThat(unchanged.getContentAsByteArray()).isEmpty();

        // And a changed task, another page or another tenant's view gives another tag, so the old tag gets the body
        fake.taskStatus = "CLAIMED";
        MockHttpServletResponse changed = perform(get(BASE + "/tasks").header("If-None-Match", etag));
        assertThat(changed.getStatus()).isEqualTo(200);
        assertThat(changed.getHeader("ETag")).isNotEqualTo(etag);
        fake.taskStatus = "OPEN";
        fake.taskNext = "c2";
        assertThat(perform(get(BASE + "/tasks").header("If-None-Match", etag)).getStatus()).isEqualTo(200);
        fake.taskNext = null;
        fake.taskTenant = "bank-b-task";
        assertThat(perform(get(BASE + "/tasks").header("If-None-Match", etag)).getStatus()).isEqualTo(200);

        // And a refused caller never gets 304, whatever tag it sends
        fake.taskTenant = "T1";
        fake.failure = () -> new io.github.dhluong90.bpm.process.api.PermissionDeniedException("Needs processes:task:work");
        MockHttpServletResponse refused = perform(get(BASE + "/tasks").header("If-None-Match", etag));
        assertThat(refused.getStatus()).isEqualTo(403);
    }

    @Test
    void list_parameters_that_are_not_numbers_or_times_are_400_QUERY_INVALID() throws Exception {
        for (String query : List.of("limit=fifty", "startedFrom=yesterday", "startedTo=2027-02-01")) {
            String[] parameter = query.split("=");
            MockHttpServletResponse response = perform(get(BASE + "/instances").param(parameter[0], parameter[1]));

            assertThat(response.getStatus()).as(query).isEqualTo(400);
            assertThat(json(response).get("code").stringValue()).as(query).isEqualTo("QUERY_INVALID");
        }
        assertThat(json(perform(get(BASE + "/definitions").param("limit", "x"))).get("code").stringValue()).isEqualTo("QUERY_INVALID");
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void the_instance_list_passes_statuses_key_range_and_page_and_answers_data_next_cursor_has_next() throws Exception {
        MockHttpServletResponse response = perform(get(BASE + "/instances").param("status", "RUNNING, COMPLETED")
                .param("definitionKey", "card-fee-review").param("startedFrom", "2027-02-01T00:00:00+07:00")
                .param("limit", "2").param("cursor", "c1"));

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.at("/data/0/id").stringValue()).isEqualTo(INSTANCE);
        assertThat(body.at("/data/0/startedAt").stringValue()).isEqualTo("2027-02-01T09:00:00Z");
        assertThat(body.get("nextCursor").stringValue()).isEqualTo("next");
        assertThat(body.get("hasNext").booleanValue()).isTrue();
        assertThat(fake.calls).containsExactly(
                "instances [RUNNING, COMPLETED] card-fee-review 2027-01-31T17:00:00Z null 2 c1");
    }

    @Test
    void a_definition_version_answers_its_hash_as_etag_and_a_version_that_is_not_a_number_is_400() throws Exception {
        MockHttpServletResponse found = perform(get(BASE + "/definitions/card-fee-review/versions/1"));
        MockHttpServletResponse bad = perform(get(BASE + "/definitions/card-fee-review/versions/one"));

        assertThat(found.getHeader("ETag")).isEqualTo("\"" + "a".repeat(64) + "\"");
        assertThat(json(found).get("approvedAt").stringValue()).isEqualTo("2027-02-01T09:00:00Z");
        assertThat(json(bad).get("code").stringValue()).isEqualTo("INPUT_INVALID");
    }

    @Test
    void TR_IDEM_03_claim_and_unclaim_need_if_match_and_an_idempotency_key_before_the_engine_runs() throws Exception {
        String task = "01JD2X5K9T1Q4N8V2M7B3C6D0G";
        for (String action : List.of(":claim", ":unclaim")) {
            assertThat(perform(post(BASE + "/tasks/" + task + action).header("Idempotency-Key", "k")).getStatus())
                    .as(action).isEqualTo(428);
            assertThat(perform(post(BASE + "/tasks/" + task + action).header("If-Match", "\"0\"")).getStatus())
                    .as(action).isEqualTo(428);
        }
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void a_claim_answers_the_task_with_its_lock_version_as_etag_and_the_callers_actions_in_a_fixed_order()
            throws Exception {
        String task = "01JD2X5K9T1Q4N8V2M7B3C6D0G";

        MockHttpServletResponse claimed = perform(post(BASE + "/tasks/" + task + ":claim").header("If-Match", "\"0\"")
                .header("Idempotency-Key", "claim-1"));
        MockHttpServletResponse read = perform(get(BASE + "/tasks/" + task));

        assertThat(claimed.getStatus()).isEqualTo(200);
        assertThat(claimed.getHeader("ETag")).isEqualTo("\"1\"");
        JsonNode body = json(claimed);
        assertThat(body.get("claimedBy").stringValue()).isEqualTo("an");
        assertThat(body.get("allowedActions").toString()).isEqualTo("[\"unclaim\",\"complete\"]");
        assertThat(read.getHeader("ETag")).isEqualTo("\"0\"");
        assertThat(json(read).get("fourEyes").booleanValue()).isTrue();
        assertThat(fake.calls).containsExactly("claim " + task + " 0 claim-1");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static void assertNoInternals(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain("Exception", "\tat ", "io.github.", "java.");
    }

    /** In-memory engine services: answer fixed values, or throw the configured failure. */
    private static final class Fake implements DefinitionService, ProcessEngine, TaskService, ProcessQueryService {
        Supplier<RuntimeException> failure;
        final List<String> calls = new ArrayList<>();

        private void maybeFail() {
            if (failure != null) {
                throw failure.get();
            }
        }

        private static ProcessInstance instance(long lockVersion, String status) {
            return new ProcessInstance(INSTANCE, "card-fee-review", 1, "APP-1001", status, Map.of(), List.of(), List.of(),
                    lockVersion, "tuan", STARTED, null);
        }

        @Override
        public io.github.dhluong90.bpm.process.api.NodeWindowStats nodeWindowStats(String definitionKey, int version,
                java.time.Instant from, java.time.Instant to) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion importDefinition(ImportRequest request) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion version(String key, int version) {
            maybeFail();
            return new DefinitionVersion("01JD2X5K9T1Q4N8V2M7B3C6D0F", key, version, "ACTIVE", "Card fee review",
                    "a".repeat(64), SourceFormat.BPMN_XML, "b".repeat(64), 6, "minh", "lan", STARTED, "IMPORT", 3, STARTED,
                    "lan", STARTED, STARTED, null, null, null, null);
        }

        @Override
        public DefinitionVersion requestApproval(DefinitionRef ref, long expectedLockVersion, Instant effectiveFrom,
                String idempotencyKey) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion approve(DefinitionRef ref, long expectedLockVersion, String expectedDefinitionHash,
                String expectedChangeSetHash, String comment, String idempotencyKey) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion reject(DefinitionRef ref, long expectedLockVersion, String reason,
                String idempotencyKey) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion withdraw(DefinitionRef ref, long expectedLockVersion, String reason,
                String idempotencyKey) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public String sourceDocument(String key, int version) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionVersion retire(DefinitionRef ref, long expectedLockVersion, String reason,
                boolean confirmNoVersionInEffect, String idempotencyKey) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<DefinitionVersion> find(DefinitionRef ref) {
            maybeFail();
            return java.util.Optional.of(version(ref.key(), ref.version()));
        }

        @Override
        public List<DefinitionHistoryEntry> history(DefinitionRef ref) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiff diff(DefinitionRef base, DefinitionRef target) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiffPage diffPage(DefinitionRef base, DefinitionRef target,
                Integer limit, String cursor) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiffPage diffPageFromEffective(DefinitionRef target,
                Integer limit, String cursor) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public io.github.dhluong90.bpm.process.api.GraphDiff diffFromEffective(DefinitionRef target) {
            maybeFail();
            throw new UnsupportedOperationException();
        }

        @Override
        public DefinitionExport exportDefinition(String key, int version, SourceFormat format) {
            maybeFail();
            return new DefinitionExport(key, version, "a".repeat(64), format, "<definitions/>");
        }

        @Override
        public ProcessInstance start(StartRequest request) {
            maybeFail();
            return instance(0, "RUNNING");
        }

        @Override
        public ProcessInstance instance(String instanceId) {
            maybeFail();
            return instance(1, "RUNNING");
        }

        @Override
        public ProcessInstance startAdHocActivity(String instanceId, long expectedLockVersion, String scopeTokenId,
                String activityId, String idempotencyKey) {
            maybeFail();
            calls.add("startAdHoc " + instanceId + " " + scopeTokenId + " " + activityId);
            return instance(expectedLockVersion + 1, "RUNNING");
        }

        @Override
        public ProcessInstance cancel(String instanceId, long expectedLockVersion, String reason, String idempotencyKey) {
            maybeFail();
            calls.add("cancel " + instanceId + " " + expectedLockVersion + " " + reason + " " + idempotencyKey);
            return instance(expectedLockVersion + 1, "CANCELLED");
        }

        @Override
        public TaskView task(String taskId) {
            maybeFail();
            return new TaskView(taskId, INSTANCE, "card-fee-review", 1, "APP-1001", "officer-review", "Officer reviews",
                    "role:CARD_OFFICER", true, "OPEN", 0, null, null, STARTED, null, null, Set.of("claim"));
        }

        @Override
        public TaskView claim(String taskId, long expectedLockVersion, String idempotencyKey) {
            maybeFail();
            calls.add("claim " + taskId + " " + expectedLockVersion + " " + idempotencyKey);
            return new TaskView(taskId, INSTANCE, "card-fee-review", 1, "APP-1001", "officer-review", "Officer reviews",
                    "role:CARD_OFFICER", true, "CLAIMED", expectedLockVersion + 1, "an", STARTED, STARTED, null, null,
                    Set.of("complete", "unclaim"));
        }

        @Override
        public TaskView unclaim(String taskId, long expectedLockVersion, String idempotencyKey) {
            maybeFail();
            calls.add("unclaim " + taskId + " " + expectedLockVersion + " " + idempotencyKey);
            return task(taskId);
        }

        @Override
        public TaskView reassign(String taskId, String assignee, String reason, long expectedLockVersion,
                String idempotencyKey) {
            maybeFail();
            calls.add("reassign " + taskId + " " + assignee + " " + expectedLockVersion + " " + idempotencyKey);
            return task(taskId);
        }

        @Override
        public TaskView delegate(String taskId, String assignee, String reason, long expectedLockVersion,
                String idempotencyKey) {
            maybeFail();
            calls.add("delegate " + taskId + " " + assignee + " " + expectedLockVersion + " " + idempotencyKey);
            return task(taskId);
        }

        @Override
        public ProcessInstance complete(String taskId, Map<String, Object> result, long expectedLockVersion,
                String idempotencyKey) {
            maybeFail();
            return instance(expectedLockVersion + 1, "COMPLETED");
        }

        volatile String taskStatus = "OPEN";
        volatile String taskNext;
        volatile String taskTenant = "T1";

        @Override
        public Page<io.github.dhluong90.bpm.process.api.TaskSummary> tasks(
                io.github.dhluong90.bpm.process.api.TaskQuery query) {
            maybeFail();
            return new Page<>(List.of(new io.github.dhluong90.bpm.process.api.TaskSummary(taskTenant, INSTANCE,
                    "card-fee-review", 1, "APP-1001", "officer-review", "Officer reviews", "role:CARD_OFFICER",
                    taskStatus, 0, null, STARTED, null, "NONE", null)), taskNext);
        }

        @Override
        public Page<DefinitionSummary> definitions(PageRequest page) {
            maybeFail();
            calls.add("definitions " + page.limit() + " " + page.cursor());
            return new Page<>(List.of(), null);
        }

        @Override
        public Page<InstanceSummary> instances(InstanceQuery query) {
            maybeFail();
            calls.add("instances " + query.statuses().stream().sorted((a, b) -> b.compareTo(a)).toList() + " "
                    + query.definitionKey() + " " + query.startedFrom() + " " + query.startedTo() + " "
                    + query.page().limit() + " " + query.page().cursor());
            return new Page<>(List.of(new InstanceSummary(INSTANCE, "card-fee-review", 1, "APP-1001", "RUNNING", 1, "tuan",
                    STARTED, null)), "next");
        }

        @Override
        public NodeCounts liveNodeCounts(String definitionKey, int version) {
            maybeFail();
            return new NodeCounts(definitionKey, version, STARTED, Map.of());
        }

        @Override
        public io.github.dhluong90.bpm.process.api.InstanceActivities activities(String instanceId, int limit) {
            maybeFail();
            return new io.github.dhluong90.bpm.process.api.InstanceActivities(instanceId, STARTED, null, List.of(),
                    List.of(), false);
        }
    }
}
