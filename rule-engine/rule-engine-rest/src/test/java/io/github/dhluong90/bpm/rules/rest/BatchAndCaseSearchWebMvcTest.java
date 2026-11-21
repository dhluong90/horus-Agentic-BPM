package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.BatchRequest;
import io.github.dhluong90.bpm.rules.api.BatchResult;
import io.github.dhluong90.bpm.rules.api.CorrelationSearch;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The REST face of batch evaluation and of the two case searches (RLE-067 to RLE-071) over Spring MVC: body limits,
 * the shape of the answer, permissions and what reaches the engine. The services behind the controllers record what
 * they are given; the engine behaviour itself is tested in the core and on PostgreSQL.
 */
class BatchAndCaseSearchWebMvcTest {

    static final Instant NOW = Instant.parse("2027-01-31T22:00:00Z");

    Set<String> permissions;
    final List<BatchRequest> batches = new ArrayList<>();
    final List<CorrelationSearch> correlationSearches = new ArrayList<>();
    final List<InputSearch> inputSearches = new ArrayList<>();
    final List<PageRequest> pages = new ArrayList<>();
    BatchResult answer;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:evaluate", "rules:log:read");
        RuleEngine engine = new RuleEngine() {
            @Override
            public DecisionResult evaluate(String ruleSet, DecisionInput input) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DecisionResult evaluate(DecisionRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BatchResult evaluateBatch(BatchRequest request) {
                batches.add(request);
                return answer;
            }
        };
        DecisionLogQuery log = new DecisionLogQuery() {
            @Override
            public Page<DecisionLogEntry> search(DecisionSearch search, PageRequest page) {
                return new Page<>(List.of(), null);
            }

            @Override
            public Page<DecisionLogEntry> searchByCorrelationId(CorrelationSearch search, PageRequest page) {
                correlationSearches.add(search);
                pages.add(page);
                return new Page<>(List.of(), null);
            }

            @Override
            public Page<DecisionLogEntry> searchByInput(InputSearch search, PageRequest page) {
                inputSearches.add(search);
                pages.add(page);
                return new Page<>(List.of(), null);
            }
        };
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "svc-eod";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        DecisionLogService service = (DecisionLogService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DecisionLogService.class}, (self, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        mvc = MockMvcBuilders.standaloneSetup(new DecisionController(engine, log, callers),
                new DecisionLogController(service, log, callers)).setControllerAdvice(new ProblemDetails()).build();
    }

    static DecisionResult matched(String id) {
        return new DecisionResult(id, "card-fee", 1, NOW, NOW, false,
                new DecisionResult.Matched(List.of("R1"), Map.of("fee", Money.ofMinor("VND", 0))));
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsByteArray());
    }

    @Test
    void RLE_067_the_answer_has_one_entry_per_item_a_decision_or_a_problem_and_the_counts() throws Exception {
        // Given an engine answer with a match, an invalid input, a failed decision and an item never evaluated
        answer = new BatchResult("card-fee", 1, NOW, List.of(
                BatchResult.Item.decided(0, matched("01KF8ZQ3M5T7V9X1B3D5F7H9K2")),
                BatchResult.Item.refused(1, "INPUT_INVALID", "Input txCount must be an integer"),
                BatchResult.Item.decided(2, new DecisionResult("01KF8ZQ3M5T7V9X1B3D5F7H9K4", "card-fee", 1, NOW, NOW,
                        false, new DecisionResult.Failed("UNIQUE_HIT_VIOLATION", "matched 2 rules",
                                List.of("R1", "R2")))),
                BatchResult.Item.refused(3, "EVALUATION_ERROR", "REGEX_TIMEOUT: not evaluated")));

        // When
        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").header("Idempotency-Key", "k-701")
                .content("""
                        {"tenantId":"bank-a","items":[
                          {"inputs":{"tier":"GOLD","txCount":5},"correlationId":"eod-2027-01-31-0000"},
                          {"inputs":{"tier":"SILVER","txCount":"four"}}]}
                        """)).andReturn().getResponse();

        // Then 200 with the version, the clock, the counts and the entries in order
        JsonNode body = json(response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("evalClock").asString()).isEqualTo("2027-01-31T22:00:00Z");
        assertThat(body.path("succeeded").asInt()).isEqualTo(1);
        assertThat(body.path("failed").asInt()).isEqualTo(3);
        JsonNode results = body.path("results");
        assertThat(results).hasSize(4);
        assertThat(results.get(0).path("index").asInt()).isZero();
        assertThat(results.get(0).path("decision").path("matchedRuleIds").get(0).asString()).isEqualTo("R1");
        assertThat(results.get(0).has("problem")).isFalse();
        assertThat(results.get(1).path("problem").path("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(results.get(1).path("problem").path("status").asInt()).isEqualTo(400);
        assertThat(results.get(1).path("problem").has("decisionId")).isFalse();
        assertThat(results.get(2).path("problem").path("code").asString()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(results.get(2).path("problem").path("status").asInt()).isEqualTo(422);
        assertThat(results.get(2).path("problem").path("decisionId").asString()).isEqualTo("01KF8ZQ3M5T7V9X1B3D5F7H9K4");
        assertThat(results.get(2).path("problem").path("matchedRuleIds").toString()).isEqualTo("[\"R1\",\"R2\"]");
        assertThat(results.get(3).path("problem").path("detail").asString()).startsWith("REGEX_TIMEOUT: not evaluated");
        // And inputs are never echoed
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("SILVER", "four");

        // And the engine got the items, the key and no tenant: a tenantId in the body is ignored (RLE-069)
        BatchRequest sent = batches.getFirst();
        assertThat(sent.ruleSet()).isEqualTo("card-fee");
        assertThat(sent.idempotencyKey()).isEqualTo("k-701");
        assertThat(sent.clock()).isNull();
        assertThat(sent.items()).extracting(BatchRequest.Item::correlationId)
                .containsExactly("eod-2027-01-31-0000", null);
        assertThat(sent.items().getFirst().input().get("tier")).isEqualTo("GOLD");
    }

    @Test
    void RLE_068_a_body_over_the_batch_limit_is_413_before_it_is_read_and_nothing_is_evaluated() throws Exception {
        byte[] big = new byte[EngineDefaults.BATCH_BODY_MAX_BYTES + 1];

        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").content(big)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(json(response).path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(batches).isEmpty();
        assertThat(EngineDefaults.BATCH_BODY_MAX_BYTES).isEqualTo(4_194_304);
    }

    @Test
    void RLE_068_a_malformed_body_or_missing_items_is_400_and_a_missing_permission_is_403_before_the_body() throws Exception {
        MockHttpServletResponse noItems = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").content("{\"inputs\":{}}")).andReturn().getResponse();
        MockHttpServletResponse notObject = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").content("{\"items\":[5]}")).andReturn().getResponse();
        MockHttpServletResponse notJson = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").content("{\"items\":")).andReturn().getResponse();
        permissions = Set.of("rules:log:read");
        MockHttpServletResponse denied = mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch")
                .contentType("application/json").content(new byte[EngineDefaults.BATCH_BODY_MAX_BYTES + 1]))
                .andReturn().getResponse();

        assertThat(noItems.getStatus()).isEqualTo(400);
        assertThat(notObject.getStatus()).isEqualTo(400);
        assertThat(json(notObject).path("detail").asString()).contains("items[0]");
        assertThat(notJson.getStatus()).isEqualTo(400);
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(batches).isEmpty();
    }

    @Test
    void RLE_069_a_chosen_clock_reaches_the_engine_which_decides_whether_the_caller_may_use_it() throws Exception {
        answer = new BatchResult("card-fee", 1, NOW, List.of());

        mvc.perform(post("/api/bpm/rules/v1/decisions/card-fee:evaluate-batch").contentType("application/json")
                .content("{\"items\":[{\"inputs\":{}}],\"clock\":\"2027-02-01T00:00:00+07:00\"}")).andReturn();

        assertThat(batches.getFirst().clock()).isEqualTo(Instant.parse("2027-01-31T17:00:00Z"));
    }

    @Test
    void RLE_070_the_correlation_id_parameter_selects_the_case_search_and_ruleSet_narrows_it() throws Exception {
        MockHttpServletResponse all = mvc.perform(get("/api/bpm/rules/v1/decisions")
                .param("correlationId", "LA-2027-000123").param("from", "2027-01-01T00:00:00Z")
                .param("to", "2027-02-01T00:00:00Z").param("limit", "50")).andReturn().getResponse();
        MockHttpServletResponse narrowed = mvc.perform(get("/api/bpm/rules/v1/decisions")
                .param("correlationId", "LA-2027-000123").param("ruleSet", "card-fee")
                .param("from", "2027-01-01T00:00:00Z").param("to", "2027-02-01T00:00:00Z")).andReturn().getResponse();
        MockHttpServletResponse empty = mvc.perform(get("/api/bpm/rules/v1/decisions").param("correlationId", "")
                .param("from", "2027-01-01T00:00:00Z").param("to", "2027-02-01T00:00:00Z")).andReturn().getResponse();

        assertThat(all.getStatus()).isEqualTo(200);
        assertThat(json(all).path("data")).isEmpty();
        assertThat(narrowed.getStatus()).isEqualTo(200);
        assertThat(empty.getStatus()).isEqualTo(200);
        assertThat(correlationSearches).extracting(CorrelationSearch::ruleSet).containsExactly(null, "card-fee", null);
        assertThat(correlationSearches).extracting(CorrelationSearch::correlationId)
                .containsExactly("LA-2027-000123", "LA-2027-000123", "");
        assertThat(correlationSearches.getFirst().from()).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(pages.getFirst().limit()).isEqualTo(50);
    }

    @Test
    void RLE_070_the_case_search_needs_rules_log_read_and_a_period_it_can_parse() throws Exception {
        MockHttpServletResponse badTime = mvc.perform(get("/api/bpm/rules/v1/decisions")
                .param("correlationId", "LA-2027-000123").param("from", "yesterday")).andReturn().getResponse();
        permissions = Set.of("rules:evaluate");
        MockHttpServletResponse denied = mvc.perform(get("/api/bpm/rules/v1/decisions")
                .param("correlationId", "LA-2027-000123").param("from", "2027-01-01T00:00:00Z")
                .param("to", "2027-02-01T00:00:00Z")).andReturn().getResponse();

        assertThat(badTime.getStatus()).isEqualTo(400);
        assertThat(json(badTime).path("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(denied.getStatus()).isEqualTo(403);
        assertThat(correlationSearches).isEmpty();
    }

    @Test
    void RLE_071_the_input_search_reads_a_body_value_of_any_scalar_type_and_refuses_what_it_cannot_read() throws Exception {
        MockHttpServletResponse text = findByInput("{\"ruleSet\":\"loan-pricing\",\"input\":\"productCode\","
                + "\"value\":\"HOME-30Y\",\"from\":\"2027-01-01T00:00:00Z\",\"to\":\"2027-02-01T00:00:00Z\"}");
        MockHttpServletResponse money = findByInput("{\"ruleSet\":\"loan-pricing\",\"input\":\"amount\","
                + "\"value\":{\"currency\":\"VND\",\"amount\":2500000000},\"from\":\"2027-01-01T00:00:00Z\","
                + "\"to\":\"2027-02-01T00:00:00Z\",\"limit\":20}");
        MockHttpServletResponse number = findByInput("{\"ruleSet\":\"loan-pricing\",\"input\":\"marginBps\","
                + "\"value\":30000.00,\"from\":\"2027-01-01T00:00:00Z\",\"to\":\"2027-02-01T00:00:00Z\"}");

        assertThat(text.getStatus()).isEqualTo(200);
        assertThat(money.getStatus()).isEqualTo(200);
        assertThat(number.getStatus()).isEqualTo(200);
        assertThat(inputSearches.get(0).value()).isEqualTo("HOME-30Y");
        assertThat(inputSearches.get(1).value()).isEqualTo(Money.ofMinor("VND", 2_500_000_000L));
        assertThat(inputSearches.get(2).value()).isEqualTo(new java.math.BigDecimal("30000.00"));
        assertThat(pages.get(1).limit()).isEqualTo(20);

        // an unknown member, a list, no value, a bad shape for money and a missing rule set are QUERY_INVALID
        for (String body : List.of(
                "{\"ruleSet\":\"loan-pricing\",\"input\":\"productCode\",\"value\":\"x\",\"from\":\"2027-01-01T00:00:00Z\","
                        + "\"to\":\"2027-02-01T00:00:00Z\",\"tenantId\":\"bank-b\"}",
                "{\"ruleSet\":\"loan-pricing\",\"input\":\"tags\",\"value\":[\"a\"],\"from\":\"2027-01-01T00:00:00Z\","
                        + "\"to\":\"2027-02-01T00:00:00Z\"}",
                "{\"ruleSet\":\"loan-pricing\",\"input\":\"tags\",\"from\":\"2027-01-01T00:00:00Z\","
                        + "\"to\":\"2027-02-01T00:00:00Z\"}",
                "{\"ruleSet\":\"loan-pricing\",\"input\":\"amount\",\"value\":{\"currency\":\"VND\",\"amount\":1.5},"
                        + "\"from\":\"2027-01-01T00:00:00Z\",\"to\":\"2027-02-01T00:00:00Z\"}",
                "{\"input\":\"productCode\",\"value\":\"x\",\"from\":\"2027-01-01T00:00:00Z\","
                        + "\"to\":\"2027-02-01T00:00:00Z\"}")) {
            MockHttpServletResponse refused = findByInput(body);
            assertThat(refused.getStatus()).as(body).isEqualTo(400);
            assertThat(json(refused).path("code").asString()).as(body).isEqualTo("QUERY_INVALID");
        }
        assertThat(inputSearches).hasSize(3);
    }

    @Test
    void RLE_071_a_body_over_16384_bytes_is_413_and_the_search_needs_rules_log_read() throws Exception {
        MockHttpServletResponse large = mvc.perform(post("/api/bpm/rules/v1/decisions:find-by-input")
                .contentType("application/json").content(new byte[EngineDefaults.COMMAND_BODY_MAX_BYTES + 1]))
                .andReturn().getResponse();
        permissions = Set.of("rules:masked:search");
        MockHttpServletResponse denied = findByInput("{\"ruleSet\":\"loan-pricing\",\"input\":\"productCode\","
                + "\"value\":\"HOME-30Y\",\"from\":\"2027-01-01T00:00:00Z\",\"to\":\"2027-02-01T00:00:00Z\"}");

        assertThat(large.getStatus()).isEqualTo(413);
        assertThat(json(large).path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(denied.getStatus()).as("rules:masked:search does not imply rules:log:read").isEqualTo(403);
        assertThat(inputSearches).isEmpty();
    }

    private MockHttpServletResponse findByInput(String body) throws Exception {
        return mvc.perform(post("/api/bpm/rules/v1/decisions:find-by-input").contentType("application/json")
                .content(body)).andReturn().getResponse();
    }
}
