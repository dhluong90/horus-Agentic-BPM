package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DraftAuthoringDisabledException;
import io.github.dhluong90.bpm.rules.api.IdempotencyInFlightException;
import io.github.dhluong90.bpm.rules.api.IdempotencyKeyReuseException;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.rules.api.RateLimitedException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.SchemaNotReadyException;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The Rule Engine error matrix over Spring MVC (RLE-036, TR-SEC-08): every code the REST module answers is triggered
 * once through the real controllers and the real problem handler, and the matrix, the OpenAPI {@code Problem.code}
 * enum and the wiki error-code page list the same codes with the same status. The services behind the controllers
 * are stand-ins that throw the engine's own exceptions; the example host triggers the same codes end to end.
 */
class ErrorMatrixWebMvcTest {

    private static final Path OPENAPI = Path.of("../api/openapi.yaml");
    private static final Path WIKI = Path.of("../../wiki/Common-Error-Codes.md");
    private static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";
    private static final Set<String> AUTHOR = Set.of("rules:read", "rules:author", "rules:evaluate");
    private static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    /** Answered by the host's tenant seam in front of the engine, not by these controllers (RLE-018, RLE-037). */
    private static final Set<String> HOST_SEAM_CODES = Set.of("TENANT_NOT_ALLOWED");

    /** What the stand-in services do for one case. */
    static final class Scene {
        String caller = "alice";
        Set<String> permissions = AUTHOR;
        RuntimeException thrown;
        DecisionResult.Outcome outcome;
    }

    /**
     * One row of the matrix.
     *
     * @param code the expected code
     * @param status the expected status
     * @param request the request that triggers it
     * @param scene sets up the caller and the stand-in services
     */
    record Case(String code, int status, RequestBuilder request, Consumer<Scene> scene) {
        @Override
        public String toString() {
            return status + " " + code;
        }
    }

    private Scene scene;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        scene = new Scene();
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return scene.caller;
            }

            @Override
            public Set<String> currentPermissions() {
                return scene.permissions;
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(
                        new RuleSetController(standIn(VersionService.class), callers),
                        new DecisionController(standIn(RuleEngine.class), standIn(io.github.dhluong90.bpm.rules.api.DecisionLogQuery.class), callers),
                        new SimulationController(standIn(RuleEngine.class), standIn(VersionService.class), callers),
                        new DecisionLogController(standIn(DecisionLogService.class),
                        standIn(io.github.dhluong90.bpm.rules.api.DecisionLogQuery.class), callers),
                        new RuleSetHistoryController(standIn(io.github.dhluong90.bpm.rules.api.RuleSetHistoryService.class),
                                callers))
                .setControllerAdvice(new ProblemDetails())
                .build();
    }

    static Stream<Case> matrix() {
        byte[] yaml = "ruleSet: card-fee\n".getBytes(StandardCharsets.UTF_8);
        byte[] gold = "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8);
        return Stream.of(
                new Case("INPUT_INVALID", 400,
                        post("/api/bpm/rules/v1/rule-sets").contentType("text/csv").content("tier,fee"), s -> { }),
                new Case("QUERY_INVALID", 400, get("/api/bpm/rules/v1/rule-sets").param("limit", "ten"), s -> { }),
                new Case("UNAUTHENTICATED", 401, get("/api/bpm/rules/v1/rule-sets/card-fee"), s -> s.caller = null),
                new Case("PERMISSION_DENIED", 403, get("/api/bpm/rules/v1/rule-sets/card-fee"),
                        s -> s.permissions = Set.of("rules:evaluate")),
                new Case("SELF_APPROVAL_FORBIDDEN", 403,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve").header("If-Match", "\"1\""),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new SelfApprovalForbiddenException("card-fee", 2);
                        }),
                new Case("DRAFT_AUTHORING_DISABLED", 403,
                        put("/api/bpm/rules/v1/rule-sets/card-fee/versions/2").contentType("application/yaml")
                                .header("Idempotency-Key", "k-102").header("If-Match", "\"1\"").content(yaml),
                        s -> s.thrown = new DraftAuthoringDisabledException("card-fee", 2)),
                new Case("CLOCK_NOT_ALLOWED", 403,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate").contentType("application/json")
                                .content("{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5},\"clock\":\"2026-11-20T10:00:00Z\"}"),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.ClockNotAllowedException()),
                new Case("RULE_SET_NOT_FOUND", 404, get("/api/bpm/rules/v1/rule-sets/card-fee"), s -> { }),
                new Case("VERSION_NOT_FOUND", 404, get("/api/bpm/rules/v1/rule-sets/card-fee/versions/9"), s -> { }),
                new Case("DECISION_NOT_FOUND", 404, get("/api/bpm/rules/v1/decisions/01KDG7Z0RQ6V2Y8M1C4N5P7T9W"),
                        s -> {
                            s.permissions = Set.of("rules:log:read");
                            s.thrown = new DecisionNotFoundException("01KDG7Z0RQ6V2Y8M1C4N5P7T9W");
                        }),
                new Case("NO_EFFECTIVE_VERSION", 404,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate").contentType("application/json").content(gold),
                        s -> s.thrown = new NoEffectiveVersionException("card-fee")),
                new Case("VERSION_CONFLICT", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish").header("If-Match", "\"1\""),
                        s -> s.thrown = new VersionConflictException("Expected lock version 1, found 2")),
                new Case("CONTENT_HASH_REQUIRED", 400,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve").header("If-Match", "\"1\""),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new io.github.dhluong90.bpm.rules.api.ContentHashRequiredException();
                        }),
                new Case("CONTENT_CHANGED", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve").header("If-Match", "\"3\""),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new io.github.dhluong90.bpm.rules.api.ContentChangedException(
                                    "changed since it was reviewed", "a".repeat(64), 3L);
                        }),
                new Case("INVALID_STATE", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve").header("If-Match", "\"1\""),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new InvalidStateException("card-fee", 2, VersionStatus.DRAFT, "approve");
                        }),
                new Case("IDEMPOTENCY_IN_FLIGHT", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions").contentType("application/yaml")
                                .header("Idempotency-Key", "k-101").content(yaml),
                        s -> s.thrown = new IdempotencyInFlightException()),
                new Case("RULE_SET_EXISTS", 409,
                        post("/api/bpm/rules/v1/rule-sets").contentType("application/yaml").content(yaml),
                        s -> s.thrown = new RuleSetExistsException("card-fee")),
                new Case("LAST_VERSION_IN_EFFECT", 409,
                        post("/api/bpm/rules/v1/rule-sets/tet-fee-waiver/versions/1:retire")
                                .contentType("application/json").header("If-Match", "\"2\"")
                                .header("Idempotency-Key", "k-102").content("{\"reason\":\"Tet promotion ended\"}"),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new io.github.dhluong90.bpm.rules.api.LastVersionInEffectException(
                                    "tet-fee-waiver", 1, "confirm", null, null);
                        }),
                new Case("AUTHOR_UNKNOWN", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/1:approve").header("If-Match", "\"1\"")
                                .header("Idempotency-Key", "k-104"),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new io.github.dhluong90.bpm.rules.api.AuthorUnknownException("card-fee", 1);
                        }),
                new Case("WARNINGS_NOT_ACKNOWLEDGED", 409,
                        post("/api/bpm/rules/v1/rule-sets/kyc-routing/versions/1:approve")
                                .contentType("application/json").header("If-Match", "\"2\"")
                                .header("Idempotency-Key", "k-103").content("{\"comment\":\"ok\"}"),
                        s -> {
                            s.permissions = APPROVER;
                            s.thrown = new io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException(
                                    "kyc-routing", 1, List.of(new io.github.dhluong90.bpm.rules.api
                                            .WarningsNotAcknowledgedException.Unacknowledged("UNMASKED_PERSONAL_DATA",
                                                    "nationalId", "national id")),
                                    List.of("UNMASKED_PERSONAL_DATA"));
                        }),
                new Case("PAYLOAD_TOO_LARGE", 413,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:approve").contentType("application/json")
                                .header("If-Match", "\"1\"").content(new byte[16_385]),
                        s -> s.permissions = APPROVER),
                new Case("PRECONDITION_REQUIRED", 428,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish"),
                        s -> s.thrown = new PreconditionRequiredException(":request-publish")),
                new Case("RATE_LIMITED", 429,
                        post("/api/bpm/rules/v1/decisions:find-by-masked-input").contentType("application/json")
                                .content("{\"ruleSet\":\"minor-review\",\"input\":\"nationalId\",\"value\":\"079203001234\","
                                        + "\"from\":\"2027-01-01T00:00:00Z\",\"to\":\"2027-01-31T00:00:00Z\"}"),
                        s -> {
                            s.permissions = Set.of("rules:masked:search");
                            s.thrown = new RateLimitedException("At most 30 masked-input searches per minute", 30);
                        }),
                new Case("BATCH_TOO_LARGE", 422,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch").contentType("application/json")
                                .content("{\"items\":[{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}]}"),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.BatchTooLargeException(1001, 1000)),
                new Case("HISTORY_TOO_LARGE", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee:compare").contentType("application/json")
                                .content("{\"base\":1,\"candidate\":2,\"from\":\"2027-01-01T00:00:00Z\","
                                        + "\"to\":\"2027-02-01T00:00:00Z\"}"),
                        s -> {
                            s.permissions = Set.of("rules:read", "rules:simulate", "rules:log:read");
                            s.thrown = new io.github.dhluong90.bpm.rules.api.HistoryTooLargeException("card-fee", 10_000,
                                    false, 5_000);
                        }),
                new Case("EVIDENCE_TOO_LARGE", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/evidence").contentType("application/json")
                                .content("{\"format\":\"csv\",\"versions\":[1]}"),
                        s -> {
                            s.permissions = Set.of("rules:read", "rules:log:read");
                            s.thrown = new io.github.dhluong90.bpm.rules.api.EvidenceTooLargeException(8_388_608);
                        }),
                new Case("READINGS_TOO_LARGE", 422,
                        get("/api/bpm/rules/v1/rule-sets/card-fee/versions/1/readings"),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException("rows", 501, 500)),
                new Case("SIMULATION_REPORT_REQUIRED", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish").header("If-Match", "\"1\""),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.SimulationReportRequiredException("card-fee", 2)),
                new Case("SIMULATION_REPORT_OUT_OF_DATE", 409,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish").header("If-Match", "\"1\""),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.SimulationReportOutOfDateException("card-fee", 2, "r1")),
                new Case("SIMULATION_REPORT_NOT_FOUND", 404,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish").header("If-Match", "\"1\""),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.SimulationReportNotFoundException("card-fee", 2, "r1")),
                new Case("EFFECTIVE_DATE_IN_PAST", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish").header("If-Match", "\"1\""),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.EffectiveDateInPastException("card-fee", 2,
                                java.time.Instant.parse("2026-11-01T00:00:00Z"), java.time.Instant.parse("2026-11-02T00:00:00Z"))),
                new Case("VALIDATION_FAILED", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions").contentType("application/yaml").content(yaml),
                        s -> s.thrown = new ValidationFailedException(List.of(new Violation("R3", "fee",
                                "output fee is money and needs a currency")))),
                new Case("CMMN_UNSUPPORTED", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions").contentType("application/xml").content(yaml),
                        s -> s.thrown = io.github.dhluong90.bpm.rules.api.UnsupportedStandardException.cmmn(
                                "http://www.omg.org/spec/CMMN/20151109/MODEL", 1)),
                new Case("DMN_UNSUPPORTED_ELEMENT", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions").contentType("application/xml").content(yaml),
                        s -> s.thrown = io.github.dhluong90.bpm.rules.api.UnsupportedStandardException.dmnElements(List.of(
                                new io.github.dhluong90.bpm.rules.api.UnsupportedElement("decisionService", "ds-1", 70, "S2")))),
                new Case("EXPRESSION_LANGUAGE_UNSUPPORTED", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions").contentType("application/xml").content(yaml),
                        s -> s.thrown = io.github.dhluong90.bpm.rules.api.UnsupportedStandardException.expressionLanguage(
                                "urn:groovy", new io.github.dhluong90.bpm.rules.api.UnsupportedElement("inputExpression",
                                        null, 12, "not planned"))),
                new Case("IDEMPOTENCY_KEY_REUSE_MISMATCH", 422,
                        post("/api/bpm/rules/v1/rule-sets/card-fee/versions/2:request-publish")
                                .header("Idempotency-Key", "k-101"),
                        s -> s.thrown = new IdempotencyKeyReuseException()),
                new Case("REPLAY_NOT_POSSIBLE", 422, post("/api/bpm/rules/v1/decisions/01KDG7Z0RQ6V2Y8M1C4N5P7T9W:replay"),
                        s -> {
                            s.permissions = Set.of("rules:log:read");
                            s.thrown = new io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException(
                                    "01KDG7Z0RQ6V2Y8M1C4N5P7T9W", List.of("nationalId"));
                        }),
                new Case("EVALUATION_ERROR", 422,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate").contentType("application/json").content(gold),
                        s -> s.outcome = new DecisionResult.Failed("EVALUATION_ERROR", "Division by zero in output fee")),
                new Case("UNIQUE_HIT_VIOLATION", 422,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate").contentType("application/json").content(gold),
                        s -> s.outcome = new DecisionResult.Failed("UNIQUE_HIT_VIOLATION",
                                "Rule set 'card-fee' v1 matched 2 rules (R2, R4); expected exactly 1", List.of("R2", "R4"))),
                new Case("INTERNAL_ERROR", 500, get("/api/bpm/rules/v1/rule-sets/card-fee"),
                        s -> s.thrown = injectedSqlFailure()),
                new Case("DECISION_LOG_BACKLOG", 503,
                        post("/api/bpm/rules/v1/decisions/card-fee:evaluate").contentType("application/json").content(gold),
                        s -> s.thrown = new io.github.dhluong90.bpm.rules.api.DecisionLogBacklogException(100_001, 100_000)),
                new Case("SCHEMA_NOT_READY", 503, get("/api/bpm/rules/v1/rule-sets"),
                        s -> s.thrown = new SchemaNotReadyException("Schema at V4, V5 pending; migrate-on-start is false")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void RLE_036_every_rest_error_is_problem_details_with_stable_code(Case row) throws Exception {
        // Given
        row.scene().accept(scene);

        // When
        MockHttpServletResponse response = mvc.perform(row.request()).andReturn().getResponse();

        // Then
        JsonNode problem = EngineJson.JSON.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(row.status());
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(problem.path("type").asString()).isEqualTo(TYPE_BASE + row.code().toLowerCase(java.util.Locale.ROOT));
        assertThat(problem.path("status").asInt()).isEqualTo(row.status());
        assertThat(problem.path("code").asString()).isEqualTo(row.code());
        assertThat(problem.path("title").asString()).isNotBlank();
        assertThat(problem.path("detail").asString()).isNotBlank();
        assertThat(problem.path("correlationId").asString()).isNotBlank();
        String retryAfter = switch (row.code()) {
            case "IDEMPOTENCY_IN_FLIGHT", "DECISION_LOG_BACKLOG" -> "1";
            case "RATE_LIMITED" -> "30";
            default -> null;
        };
        assertThat(response.getHeader("Retry-After")).isEqualTo(retryAfter);
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Exception", "\tat ", "java.", "SQL", "rle_");
    }

    @Test
    void TR_SEC_10_a_masking_key_mismatch_problem_has_exactly_the_documented_fields_and_no_key_material() throws Exception {
        // Given a decision the engine refused because the masking key does not match the one first recorded for its id,
        // with the detail the engine builds and check values that must not appear anywhere
        String checkValue = "9f2c4be1a07d55c3e8b6a1d4f0c97e2b3a5d6c8e1f4a7b0c2d5e8f1a4b7c0d3e";
        scene.outcome = new DecisionResult.Failed("EVALUATION_ERROR",
                io.github.dhluong90.bpm.rules.internal.service.DefaultRuleEngine.maskingKeyMismatchDetail("minor-review"));
        byte[] body = "{\"inputs\":{\"nationalId\":\"079090001234\",\"age\":16}}".getBytes(StandardCharsets.UTF_8);

        // When it is answered
        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/minor-review:evaluate")
                .contentType("application/json").content(body).header("X-Correlation-Id", "01KDG7Z0RQ6V2Y8M1C4N5P7T9W"))
                .andReturn().getResponse();

        // Then the whole Problem body is these fields and these values: no check value, key, key id, tenant, input or stack
        assertThat(response.getStatus()).isEqualTo(422);
        JsonNode problem = EngineJson.JSON.readTree(response.getContentAsByteArray());
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "code", "detail",
                "correlationId", "decisionId", "matchedRuleIds");
        assertThat(problem.path("type").asString()).isEqualTo(TYPE_BASE + "evaluation_error");
        assertThat(problem.path("title").asString()).isEqualTo("Unprocessable Content");
        assertThat(problem.path("status").asInt()).isEqualTo(422);
        assertThat(problem.path("code").asString()).isEqualTo("EVALUATION_ERROR");
        assertThat(problem.path("detail").asString()).isEqualTo("MASKING_KEY_MISMATCH: the masking key in use does not"
                + " match the key first recorded for it, so the inputs of minor-review cannot be logged");
        assertThat(problem.path("correlationId").asString()).isEqualTo("01KDG7Z0RQ6V2Y8M1C4N5P7T9W");
        assertThat(problem.path("decisionId").asString()).isEqualTo("01KDG7Z0RQ6V2Y8M1C4N5P7T9W");
        assertThat(problem.path("matchedRuleIds").size()).isZero();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).doesNotContain(checkValue, "079090001234", "hmac", "k2", "bank-a", "Exception", "\tat ", "java.",
                "rle_");
    }

    @Test
    void TR_SEC_01_a_decision_past_its_step_budget_names_the_limit_it_reached() throws Exception {
        // Given a large collect tariff table that ran past the step budget on this input
        scene.outcome = new DecisionResult.Failed("EVALUATION_ERROR",
                "EVALUATION_BUDGET_EXCEEDED: the decision took more than 100000 evaluation steps", List.of(), "STEPS");
        byte[] gold = "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8);

        // When
        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate")
                .contentType("application/json").content(gold)).andReturn().getResponse();

        // Then the stable field tells a rule problem (STEPS) from a slow machine (TIME), not only the detail text
        JsonNode problem = EngineJson.JSON.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(problem.path("code").asString()).isEqualTo("EVALUATION_ERROR");
        assertThat(problem.path("limit").asString()).isEqualTo("STEPS");
        assertThat(problem.path("detail").asString()).startsWith("EVALUATION_BUDGET_EXCEEDED:");
    }

    @Test
    void TR_SEC_01_a_failure_that_reached_no_limit_has_no_limit_field() throws Exception {
        scene.outcome = new DecisionResult.Failed("EVALUATION_ERROR", "Division by zero in output fee");
        byte[] gold = "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate")
                .contentType("application/json").content(gold)).andReturn().getResponse();

        assertThat(EngineJson.JSON.readTree(response.getContentAsByteArray()).has("limit")).isFalse();
    }

    @Test
    void TR_SEC_08_injected_internal_failure_is_500_without_internals_and_logged_with_the_correlation_id()
            throws Exception {
        // Given the version repository fails as a missing table would
        scene.thrown = injectedSqlFailure();
        List<LogRecord> log = Collections.synchronizedList(new ArrayList<>());
        Logger logger = Logger.getLogger(ProblemDetails.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                log.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(capture);
        try {
            // When
            MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/rule-sets/card-fee")
                    .header(ProblemDetails.CORRELATION_HEADER, "fee-review-0042")).andReturn().getResponse();

            // Then
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentType()).isEqualTo("application/problem+json");
            assertThat(EngineJson.JSON.readTree(body).path("code").asString()).isEqualTo("INTERNAL_ERROR");
            assertThat(EngineJson.JSON.readTree(body).path("correlationId").asString()).isEqualTo("fee-review-0042");
            assertThat(body).doesNotContain("rle_version", "SQLException", "RuntimeException", "relation rle", "\tat ",
                    "java.", "com.horus");
            assertThat(log).anySatisfy(record -> {
                assertThat(record.getMessage()).contains("fee-review-0042");
                assertThat(record.getThrown()).hasRootCauseInstanceOf(SQLException.class);
            });
        } finally {
            logger.removeHandler(capture);
        }
    }

    @Test
    void TR_SEC_08_an_engine_code_outside_the_matrix_is_an_internal_error_without_its_message() throws Exception {
        // Given a host extension throws an engine exception with a code the matrix does not know
        scene.thrown = new io.github.dhluong90.bpm.rules.api.HorusBpmException("CHANGE_TICKET_MISSING",
                "Ticket lookup failed on jdbc:postgresql://ops-db/tickets");

        // When
        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/rule-sets")).andReturn().getResponse();

        // Then
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":\"INTERNAL_ERROR\"").doesNotContain("jdbc", "ops-db", "CHANGE_TICKET_MISSING");
    }

    @Test
    void RLE_036_matrix_openapi_and_wiki_list_the_same_codes_with_the_same_status() throws Exception {
        // Given
        Map<String, Integer> tested = new LinkedHashMap<>();
        matrix().forEach(row -> tested.put(row.code(), row.status()));
        Map<String, Integer> handled = new LinkedHashMap<>();
        ProblemDetails.STATUS.forEach((code, status) -> handled.put(code, status.value()));
        handled.put("INTERNAL_ERROR", 500);

        // When
        JsonNode spec = EngineJson.YAML.readTree(Files.readAllBytes(OPENAPI));
        Set<String> documented = new TreeSet<>();
        spec.at("/components/schemas/Problem/properties/code/enum").forEach(code -> documented.add(code.asString()));
        documented.removeAll(HOST_SEAM_CODES);
        Map<String, Integer> wiki = wikiIndex();

        // Then
        assertThat(tested).isEqualTo(handled);
        assertThat(documented).isEqualTo(new TreeSet<>(tested.keySet()));
        tested.forEach((code, status) -> assertThat(wiki).as("wiki index row for %s", code).containsEntry(code, status));
        String page = Files.readString(WIKI);
        tested.keySet().forEach(code -> assertThat(page).as("wiki section for %s", code).contains("\n### " + code + "\n"));
    }

    private static Map<String, Integer> wikiIndex() throws java.io.IOException {
        Map<String, Integer> rows = new LinkedHashMap<>();
        Matcher row = Pattern.compile("(?m)^\\| (\\d{3}) \\| \\[([A-Z_]+)]\\(#[a-z_]+\\) \\| (rules|both) \\|")
                .matcher(Files.readString(WIKI));
        while (row.find()) {
            rows.put(row.group(2), Integer.parseInt(row.group(1)));
        }
        return rows;
    }

    private static RuntimeException injectedSqlFailure() {
        return new IllegalStateException("java.sql.SQLException: relation rle_version does not exist",
                new SQLException("relation rle_version does not exist", "42P01"));
    }

    /** A service whose every method follows the scene: throw its exception, or answer "nothing found". */
    private <T> T standIn(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "stand-in " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (scene.thrown != null) {
                throw scene.thrown;
            }
            if (method.getReturnType() == Optional.class) {
                return Optional.empty();
            }
            if (method.getReturnType() == DecisionResult.class && scene.outcome != null) {
                return new DecisionResult("01KDG7Z0RQ6V2Y8M1C4N5P7T9W", "card-fee", 1,
                        Instant.parse("2026-12-15T02:00:00Z"), scene.outcome);
            }
            throw new UnsupportedOperationException("the scene does not answer " + method.getName());
        });
        return type.cast(proxy);
    }
}
