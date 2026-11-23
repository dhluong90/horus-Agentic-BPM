package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Reading;
import io.github.dhluong90.bpm.rules.api.RuleSetTemplate;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationReportOutOfDateException;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequiredException;
import io.github.dhluong90.bpm.rules.api.SimulationReportService;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The templates, simulation report and history readings endpoints over Spring MVC (PD-112, PD-115, RLE-197 to
 * RLE-202): the permission checked first, what reaches the engine, the body that comes back and the problem codes.
 */
class SimulationReportWebMvcTest {

    static final String BASE = "/api/bpm/rules/v1/rule-sets/card-fee/versions/";
    static final Map<String, Reading> FIFTY = Map.of("en", new Reading("Rule R2 always applies. Result: fee is 50,000 VND.",
            "R2: fee 50,000 VND", "GENERATED"));
    static final Map<String, Reading> THIRTY = Map.of("en", new Reading("Rule R2 always applies. Result: fee is 30,000 VND.",
            "R2: fee 30,000 VND", "GENERATED"));
    static final Instant NOV_01 = Instant.parse("2026-11-01T00:00:00Z");

    Set<String> permissions;
    RuntimeException refusal;
    final List<String> calls = new ArrayList<>();
    final List<Object> arguments = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:simulate", "rules:author", "rules:log:read");
        refusal = null;
        calls.clear();
        arguments.clear();
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "lan";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        SimulationReport report = new SimulationReport("01KB2R6Q3C0000000000000001", "card-fee", 2, "a".repeat(64),
                SimulationReport.Status.CURRENT, false, "lan", NOV_01,
                new SimulationReport.Scope(SimulationReportRequest.Source.REPLAY, NOV_01, NOV_01.plusSeconds(86_400),
                        100, false),
                new SimulationReport.Counts(100, 18, 80, 0, 2), List.of("en"),
                List.of(new SimulationReport.Row(null, "01KB2R6Q3C0000000000000009", "CHANGED",
                        new RuleReadings.OutcomeReading("MATCHED", List.of("R2"), FIFTY),
                        new RuleReadings.OutcomeReading("MATCHED", List.of("R2"), THIRTY))),
                false);
        SimulationReportService reports = (SimulationReportService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SimulationReportService.class}, (self, method, args) -> {
                    calls.add(method.getName());
                    arguments.add(args[args.length - 1]);
                    if (refusal != null) {
                        throw refusal;
                    }
                    return switch (method.getName()) {
                        case "create", "get" -> report;
                        case "list" -> List.of(new SimulationReport(report.id(), "card-fee", 2, report.canonicalHash(),
                                report.status(), true, "lan", NOV_01, report.scope(), report.counts(), List.of("en"),
                                List.of(), false));
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        VersionService versions = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> {
                    calls.add(method.getName());
                    arguments.add(args[args.length - 1]);
                    if (refusal != null) {
                        throw refusal;
                    }
                    return switch (method.getName()) {
                        case "requestPublish" -> new RuleSetVersion("card-fee", 2, VersionStatus.PENDING_APPROVAL, "first",
                                null, 1L, "{}", "a".repeat(64),
                                io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, "b".repeat(64), null, "lan", NOV_01,
                                null, null, "lan", NOV_01, null, null, List.of(), null);
                        case "readHistorySimulation" -> new RuleReadings.HistoryReadings("card-fee", 2,
                                VersionStatus.DRAFT, NOV_01, NOV_01.plusSeconds(86_400), false, List.of("en"),
                                List.of(new RuleReadings.HistoryItemReading("01KB2R6Q3C0000000000000001", 1, NOV_01, true,
                                                null, List.of(), new RuleReadings.OutcomeReading("MATCHED", List.of("R2"),
                                                        THIRTY),
                                                new RuleReadings.OutcomeReading("MATCHED", List.of("R2"), FIFTY)),
                                        new RuleReadings.HistoryItemReading("01KB2R6Q3C0000000000000000", 1, NOV_01,
                                                false, RuleReadings.REPLAY_NOT_POSSIBLE, List.of("nationalId"), null,
                                                null)));
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        RuleEngine engine = (RuleEngine) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuleEngine.class}, (self, method, args) -> {
                    calls.add(method.getName());
                    arguments.add(args[args.length - 1]);
                    return new HistorySimulationResult("card-fee", 2, VersionStatus.DRAFT, NOV_01,
                            NOV_01.plusSeconds(86_400), false, List.of());
                });
        io.github.dhluong90.bpm.rules.api.RuleSetTemplateService templates = () -> {
            calls.add("templates");
            return List.of(new RuleSetTemplate("fee-by-tier", Map.of("en", "Fee by tier", "vi", "Phí theo hạng"),
                    Map.of("en", "A fee by tier", "vi", "Phí theo hạng"), RuleSetTemplate.BUILT_IN, "first",
                    List.of(new RuleSetTemplate.Input("tier", "string", List.of("GOLD", "PLATINUM", "CLASSIC"))),
                    List.of(new RuleSetTemplate.Output("fee", "money")),
                    List.of(new RuleSetTemplate.Row("R1", Map.of("tier", "== 'GOLD'"), Map.of("fee", "0 VND")),
                            new RuleSetTemplate.Row("R2", Map.of(), Map.of("fee", "50000 VND")))));
        };
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(versions, callers),
                new SimulationController(engine, versions, callers), new SimulationReportController(reports, callers),
                new RuleSetTemplateController(templates, callers)).setControllerAdvice(new ProblemDetails()).build();
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString());
    }

    @Test
    void RLE_197_the_templates_come_back_with_both_languages_inputs_outputs_and_first_rows() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/rule-set-templates")).andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode template = json(response).get("templates").get(0);
        assertThat(template.get("id").asString()).isEqualTo("fee-by-tier");
        assertThat(template.get("labels").get("vi").asString()).isEqualTo("Phí theo hạng");
        assertThat(template.get("source").asString()).isEqualTo("BUILT_IN");
        assertThat(template.get("hitPolicy").asString()).isEqualTo("first");
        assertThat(template.get("inputs").get(0).get("allowed").get(2).asString()).isEqualTo("CLASSIC");
        assertThat(template.get("outputs").get(0).get("type").asString()).isEqualTo("money");
        assertThat(template.get("rows").get(0).get("when").get("tier").asString()).isEqualTo("== 'GOLD'");
        assertThat(template.get("rows").get(1).get("when").size()).isZero();
        assertThat(template.get("rows").get(1).get("then").get("fee").asString()).isEqualTo("50000 VND");
    }

    @Test
    void RLE_197_a_reader_without_rules_author_gets_403_and_nothing_is_read() throws Exception {
        permissions = Set.of("rules:read");

        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/rule-set-templates")).andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json(response).get("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_199_a_replay_report_is_created_with_201_and_carries_counts_and_changed_rows() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2/simulation-reports")
                .header("Idempotency-Key", "rep-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"REPLAY\",\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-12-01T00:00:00Z\","
                        + "\"limit\":100,\"tenantId\":\"bank-b\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(body.get("counts").get("changed").asInt()).isEqualTo(18);
        assertThat(body.get("counts").get("notReplayable").asInt()).isEqualTo(2);
        assertThat(body.get("status").asString()).isEqualTo("CURRENT");
        assertThat(body.get("rows").get(0).get("before").get("readings").get("en").get("short").asString())
                .isEqualTo("R2: fee 50,000 VND");
        assertThat(body.get("rows").get(0).get("after").get("readings").get("en").get("short").asString())
                .isEqualTo("R2: fee 30,000 VND");
        SimulationReportRequest sent = (SimulationReportRequest) arguments.getFirst();
        assertThat(sent.source()).isEqualTo(SimulationReportRequest.Source.REPLAY);
        assertThat(sent.limit()).isEqualTo(100);
        assertThat(sent.idempotencyKey()).isEqualTo("rep-1");
        assertThat(sent.from()).isEqualTo(NOV_01);
    }

    @Test
    void RLE_199_a_sample_report_reads_the_inputs_and_ignores_a_label() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2/simulation-reports")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"source\":\"SAMPLES\",\"samples\":[{\"label\":\"VIP 079201001234\","
                        + "\"inputs\":{\"tier\":\"GOLD\",\"txCount\":3}}]}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        SimulationReportRequest sent = (SimulationReportRequest) arguments.getFirst();
        assertThat(sent.source()).isEqualTo(SimulationReportRequest.Source.SAMPLES);
        assertThat(sent.samples()).singleElement().satisfies(sample -> assertThat(sample.get("tier")).isEqualTo("GOLD"));
        assertThat(response.getContentAsString()).doesNotContain("079201001234");
    }

    @Test
    void RLE_199_without_rules_simulate_nothing_is_read_and_a_bad_source_is_400() throws Exception {
        permissions = Set.of("rules:read", "rules:author");
        MockHttpServletResponse denied = mvc.perform(post(BASE + "2/simulation-reports")
                .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"SAMPLES\"}")).andReturn().getResponse();
        permissions = Set.of("rules:simulate");
        MockHttpServletResponse badSource = mvc.perform(post(BASE + "2/simulation-reports")
                .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"MONTH\"}")).andReturn().getResponse();
        MockHttpServletResponse badWindow = mvc.perform(post(BASE + "2/simulation-reports")
                .contentType(MediaType.APPLICATION_JSON).content("{\"source\":\"REPLAY\",\"from\":\"yesterday\"}"))
                .andReturn().getResponse();

        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(badSource.getStatus()).isEqualTo(400);
        assertThat(json(badSource).get("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(badWindow.getStatus()).isEqualTo(400);
        assertThat(json(badWindow).get("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_200_a_report_is_read_by_id_and_listed_without_rows() throws Exception {
        MockHttpServletResponse one = mvc.perform(get(BASE + "2/simulation-reports/01KB2R6Q3C0000000000000001"))
                .andReturn().getResponse();
        MockHttpServletResponse all = mvc.perform(get(BASE + "2/simulation-reports")).andReturn().getResponse();

        assertThat(one.getStatus()).isEqualTo(200);
        assertThat(json(one).get("rows").size()).isEqualTo(1);
        assertThat(json(one).get("rows").get(0).get("decisionId").asString()).isEqualTo("01KB2R6Q3C0000000000000009");
        assertThat(all.getStatus()).isEqualTo(200);
        assertThat(json(all).get("reports").get(0).get("attached").asBoolean()).isTrue();
        assertThat(json(all).get("reports").get(0).get("rows").size()).isZero();
    }

    @Test
    void RLE_200_reading_needs_rules_read_and_an_unknown_report_is_404() throws Exception {
        refusal = new SimulationReportNotFoundException("card-fee", 2, "nope");
        MockHttpServletResponse missing = mvc.perform(get(BASE + "2/simulation-reports/nope")).andReturn().getResponse();
        permissions = Set.of("rules:simulate");
        MockHttpServletResponse denied = mvc.perform(get(BASE + "2/simulation-reports")).andReturn().getResponse();

        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(json(missing).get("code").asString()).isEqualTo("SIMULATION_REPORT_NOT_FOUND");
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void RLE_201_request_publish_sends_the_report_id_to_the_engine() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2:request-publish")
                .contentType(MediaType.APPLICATION_JSON).content("{\"simulationReportId\":\"01KB2R6Q3C0000000000000001\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(((WriteOptions) arguments.getFirst()).simulationReportId()).isEqualTo("01KB2R6Q3C0000000000000001");
    }

    @Test
    void RLE_201_request_publish_without_a_body_still_works_and_a_report_member_must_be_text() throws Exception {
        MockHttpServletResponse plain = mvc.perform(post(BASE + "2:request-publish")).andReturn().getResponse();
        MockHttpServletResponse bad = mvc.perform(post(BASE + "2:request-publish")
                .contentType(MediaType.APPLICATION_JSON).content("{\"simulationReportId\":7}")).andReturn().getResponse();

        assertThat(plain.getStatus()).isEqualTo(200);
        assertThat(((WriteOptions) arguments.getFirst()).simulationReportId()).isNull();
        assertThat(bad.getStatus()).isEqualTo(400);
    }

    @Test
    void RLE_201_the_three_report_problems_have_their_codes_and_statuses() throws Exception {
        refusal = new SimulationReportRequiredException("card-fee", 2);
        MockHttpServletResponse required = mvc.perform(post(BASE + "2:request-publish")).andReturn().getResponse();
        refusal = new SimulationReportOutOfDateException("card-fee", 2, "r1");
        MockHttpServletResponse outOfDate = mvc.perform(post(BASE + "2:request-publish")).andReturn().getResponse();
        refusal = new SimulationReportNotFoundException("card-fee", 2, "r1");
        MockHttpServletResponse notFound = mvc.perform(post(BASE + "2:request-publish")).andReturn().getResponse();

        assertThat(required.getStatus()).isEqualTo(409);
        assertThat(json(required).get("code").asString()).isEqualTo("SIMULATION_REPORT_REQUIRED");
        assertThat(outOfDate.getStatus()).isEqualTo(409);
        assertThat(json(outOfDate).get("code").asString()).isEqualTo("SIMULATION_REPORT_OUT_OF_DATE");
        assertThat(notFound.getStatus()).isEqualTo(404);
        assertThat(json(notFound).get("code").asString()).isEqualTo("SIMULATION_REPORT_NOT_FOUND");
    }

    @Test
    void RLE_202_history_readings_read_both_sides_and_name_a_masked_input_without_its_value() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2:simulate-history-readings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-12-01T00:00:00Z\"}")).andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("decisionCount").asInt()).isEqualTo(2);
        assertThat(body.get("replayedCount").asInt()).isEqualTo(1);
        assertThat(body.get("notReplayableCount").asInt()).isEqualTo(1);
        JsonNode first = body.get("results").get(0);
        assertThat(first.get("simulated").get("readings").get("en").get("short").asString()).isEqualTo("R2: fee 30,000 VND");
        assertThat(first.get("logged").get("readings").get("en").get("short").asString()).isEqualTo("R2: fee 50,000 VND");
        assertThat(first.get("code").isNull()).isTrue();
        JsonNode masked = body.get("results").get(1);
        assertThat(masked.get("code").asString()).isEqualTo("REPLAY_NOT_POSSIBLE");
        assertThat(masked.get("maskedInputs").get(0).asString()).isEqualTo("nationalId");
        assertThat(masked.get("simulated").isNull()).isTrue();
        assertThat(masked.get("logged").isNull()).isTrue();
        // Without a limit the run is the most one request reads
        HistorySimulation sent = (HistorySimulation) arguments.getFirst();
        assertThat(sent.limit()).isEqualTo(500);
    }

    @Test
    void RLE_202_each_of_the_three_permissions_is_needed_and_a_limit_over_500_is_422() throws Exception {
        for (Set<String> missing : List.of(Set.of("rules:log:read", "rules:read"), Set.of("rules:simulate", "rules:read"),
                Set.of("rules:simulate", "rules:log:read"))) {
            permissions = missing;
            assertThat(mvc.perform(post(BASE + "2:simulate-history-readings").contentType(MediaType.APPLICATION_JSON)
                    .content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        permissions = Set.of("rules:simulate", "rules:log:read", "rules:read");
        MockHttpServletResponse tooLarge = mvc.perform(post(BASE + "2:simulate-history-readings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-12-01T00:00:00Z\",\"limit\":501}"))
                .andReturn().getResponse();

        assertThat(tooLarge.getStatus()).isEqualTo(422);
        assertThat(json(tooLarge).get("code").asString()).isEqualTo(new ReadingsTooLargeException("decisions", 501, 500).code());
        assertThat(calls).isEmpty();
    }
}
