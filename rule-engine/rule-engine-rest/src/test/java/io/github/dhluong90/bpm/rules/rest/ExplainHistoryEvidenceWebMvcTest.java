package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.EvidenceExport;
import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.EvidenceRequest;
import io.github.dhluong90.bpm.rules.api.EvidenceTooLargeException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.api.RuleSetHistoryService;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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
 * The REST face of the decision explanation (RLE-030, RLE-031), the rule set history and the evidence export (PD-116)
 * over Spring MVC: permissions, body limits, headers, the shape of the answers and what reaches the services. The
 * services record what they are given; their behaviour is tested in the core and on PostgreSQL.
 */
class ExplainHistoryEvidenceWebMvcTest {

    static final Instant AT = Instant.parse("2027-01-20T03:00:00Z");
    static final String D7 = "01KF2Q8Z0RQ6V2Y8M1C4N5P7T9";

    Set<String> permissions;
    final List<List<String>> languages = new ArrayList<>();
    final List<EvidenceRequest> exports = new ArrayList<>();
    final List<PageRequest> pages = new ArrayList<>();
    RuntimeException refusal;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:log:read");
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "ha";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        DecisionLogService decisions = new DecisionLogService() {
            @Override
            public io.github.dhluong90.bpm.rules.api.LoggedDecision get(String decisionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public io.github.dhluong90.bpm.rules.api.DecisionSearchResult findByMaskedInput(
                    io.github.dhluong90.bpm.rules.api.MaskedInputQuery query) {
                throw new UnsupportedOperationException();
            }

            @Override
            public DecisionExplanation explain(String decisionId, List<String> preferred) {
                languages.add(preferred);
                return d7(preferred.contains("vi") ? "vi" : "en");
            }
        };
        RuleSetHistoryService history = new RuleSetHistoryService() {
            @Override
            public RuleSetHistory history(String ruleSet, PageRequest page) {
                pages.add(page);
                VersionHistoryEntry created = new VersionHistoryEntry(LifecycleAction.CREATED, null, VersionStatus.DRAFT,
                        "lan", null, 0, AT.minusSeconds(3600));
                return new RuleSetHistory(ruleSet, List.of(new RuleSetHistory.VersionLine(1, VersionStatus.DRAFT, null,
                        null, "4be1" + "0".repeat(60), "lan", AT.minusSeconds(3600), null, null, null, null, null, null,
                        null, null, List.of(created), false)),
                        List.of(new RuleSetHistory.RuleSetEvent(RuleSetHistory.Action.EVIDENCE_EXPORTED, "ha", AT,
                                new RuleSetHistory.EvidenceSummary(EvidenceFormat.CSV, List.of(1), List.of(D7),
                                        "9f4a" + "0".repeat(60), 812))), false, null);
            }

            @Override
            public EvidenceExport exportEvidence(String ruleSet, EvidenceRequest request) {
                if (refusal != null) {
                    throw refusal;
                }
                exports.add(request);
                String content = request.format() == EvidenceFormat.CSV ? "# HORUS Rule Engine evidence export\r\nrecord\r\n"
                        : "{\"evidence\":{},\"content\":{}}";
                return new EvidenceExport(ruleSet, request.format(), content, "9f4a" + "0".repeat(60), "ha", AT,
                        request.versions(), request.decisionIds());
            }
        };
        DecisionLogQuery log = (DecisionLogQuery) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DecisionLogQuery.class}, (self, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        mvc = MockMvcBuilders.standaloneSetup(new DecisionLogController(decisions, log, callers),
                new RuleSetHistoryController(history, callers)).setControllerAdvice(new ProblemDetails()).build();
    }

    static DecisionExplanation d7(String language) {
        DecisionExplanation e = new DecisionExplanation(D7, "card-fee", 1, "FIRST", "OK", null,
                Instant.parse("2026-11-20T10:00:00Z"), List.of(new DecisionExplanation.MatchedRow("R2", 2,
                        List.of(new DecisionExplanation.Condition("txCount", "/when/txCount", ">= 3", "5", false, false,
                                true)), "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}")),
                "{\"fee\":{\"currency\":\"VND\",\"amount\":10000}}",
                List.of(new DecisionExplanation.Reason("RULE_MATCHED", ordered("ruleId", "R2", "version", 1))), null, null);
        return e.withText(language, "en".equals(language)
                ? "Rule R2 of card-fee version 1 matched because tx count is 5, which is at least 3. Result: fee 10,000 VND."
                : "Quy tắc R2 của card-fee phiên bản 1 khớp vì tx count là 5, tức là ít nhất 3. Kết quả: fee 10.000 VND.");
    }

    /** Parameters in a fixed order, as the engine builds them. */
    static Map<String, Object> ordered(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(k1, v1);
        params.put(k2, v2);
        return params;
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsByteArray());
    }

    @Test
    void RLE_030_explain_answers_the_contract_shape_and_passes_the_callers_languages() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/decisions/" + D7 + "/explain")
                .header("Accept-Language", "vi-VN, en;q=0.8, *;q=0.1")).andReturn().getResponse();

        JsonNode body = json(response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(body.path("hitPolicy").asString()).isEqualTo("FIRST");
        assertThat(body.path("clock").asString()).isEqualTo("2026-11-20T10:00:00Z");
        assertThat(body.at("/matched/0/conditions/0").toString()).isEqualTo("{\"input\":\"txCount\",\"path\":"
                + "\"/when/txCount\",\"test\":\">= 3\",\"value\":5,\"masked\":false,\"missing\":false,\"result\":true}");
        assertThat(body.at("/matched/0/outputs/fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":10000}");
        assertThat(body.path("reasons").toString())
                .isEqualTo("[{\"code\":\"RULE_MATCHED\",\"params\":{\"ruleId\":\"R2\",\"version\":1}}]");
        assertThat(body.path("language").asString()).isEqualTo("vi");
        assertThat(languages.getLast()).containsExactly("vi", "en");

        mvc.perform(get("/api/bpm/rules/v1/decisions/" + D7 + "/explain?lang=en").header("Accept-Language", "vi"))
                .andReturn();
        assertThat(languages.getLast()).containsExactly("en", "vi");
        mvc.perform(get("/api/bpm/rules/v1/decisions/" + D7 + "/explain").header("Accept-Language", ";;;"))
                .andReturn();
        assertThat(languages.getLast()).isEmpty();
    }

    @Test
    void RLE_031_explain_needs_rules_log_read() throws Exception {
        permissions = Set.of("rules:read");

        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/decisions/" + D7 + "/explain"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json(response).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(languages).isEmpty();
    }

    @Test
    void history_answers_versions_events_and_rule_set_events() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/api/bpm/rules/v1/rule-sets/card-fee/history?limit=2"))
                .andReturn().getResponse();

        JsonNode body = json(response);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(body.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(body.at("/data/0/version").asInt()).isEqualTo(1);
        assertThat(body.at("/data/0/effectiveTo").isNull()).isTrue();
        assertThat(body.at("/data/0/events/0/action").asString()).isEqualTo("CREATED");
        assertThat(body.at("/data/0/eventsTruncated").asBoolean()).isFalse();
        assertThat(body.at("/ruleSetEvents/0/action").asString()).isEqualTo("EVIDENCE_EXPORTED");
        assertThat(body.at("/ruleSetEvents/0/evidence/format").asString()).isEqualTo("csv");
        assertThat(body.at("/ruleSetEvents/0/evidence/decisionIds/0").asString()).isEqualTo(D7);
        assertThat(body.path("hasNext").asBoolean()).isFalse();
        assertThat(pages.getLast().limit()).isEqualTo(2);

        assertThat(mvc.perform(get("/api/bpm/rules/v1/rule-sets/card-fee/history?limit=two")).andReturn().getResponse()
                .getStatus()).isEqualTo(400);
        permissions = Set.of("rules:log:read");
        assertThat(mvc.perform(get("/api/bpm/rules/v1/rule-sets/card-fee/history")).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    void evidence_returns_the_file_with_its_digests_and_passes_the_key_and_records() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/api/bpm/rules/v1/rule-sets/card-fee/evidence")
                .contentType("application/json").header("Idempotency-Key", "k-118")
                .header("X-Correlation-Id", "audit-case-17")
                .content("{\"format\":\"csv\",\"versions\":[2,1],\"decisionIds\":[\"" + D7 + "\"]}"))
                .andReturn().getResponse();

        byte[] file = response.getContentAsByteArray();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"card-fee-evidence-20270120T030000Z.csv\"");
        assertThat(response.getHeader("Content-Digest")).isEqualTo("sha-256=:"
                + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(file)) + ":");
        assertThat(response.getHeader("X-Evidence-Sha256")).isEqualTo("9f4a" + "0".repeat(60));
        assertThat(new String(file, StandardCharsets.UTF_8)).startsWith("# HORUS Rule Engine evidence export");
        EvidenceRequest sent = exports.getFirst();
        assertThat(sent.format()).isEqualTo(EvidenceFormat.CSV);
        assertThat(sent.versions()).containsExactly(2, 1);
        assertThat(sent.decisionIds()).containsExactly(D7);
        assertThat(sent.idempotencyKey()).isEqualTo("k-118");
        assertThat(sent.correlationId()).isEqualTo("audit-case-17");

        MockHttpServletResponse json = mvc.perform(post("/api/bpm/rules/v1/rule-sets/card-fee/evidence")
                .contentType("application/json").content("{\"format\":\"json\",\"versions\":[1]}")).andReturn()
                .getResponse();
        assertThat(json.getContentType()).startsWith("application/json");
        assertThat(json.getHeader("Content-Disposition")).endsWith(".json\"");
    }

    @Test
    void evidence_refuses_bad_bodies_missing_permission_and_too_large_files() throws Exception {
        String path = "/api/bpm/rules/v1/rule-sets/card-fee/evidence";
        assertThat(status(post(path).contentType("application/json").content("{\"format\":\"pdf\",\"versions\":[1]}")))
                .isEqualTo(400);
        assertThat(status(post(path).contentType("application/json").content("{\"versions\":[1]}"))).isEqualTo(400);
        assertThat(status(post(path).contentType("application/json")
                .content("{\"format\":\"csv\",\"versions\":[1],\"tenantId\":\"bank-b\"}"))).isEqualTo(400);
        assertThat(status(post(path).contentType("application/json").content("{\"format\":\"csv\",\"versions\":\"1\"}")))
                .isEqualTo(400);
        assertThat(status(post(path).contentType("application/json")
                .content("{\"format\":\"csv\",\"decisionIds\":[" + "\"x\",".repeat(100) + "\"x\"]}"))).isEqualTo(400);
        assertThat(status(post(path).contentType("application/json")
                .content("{\"format\":\"csv\",\"versions\":[1],\"pad\":\"" + "x".repeat(16_400) + "\"}")))
                .isEqualTo(413);
        assertThat(exports).isEmpty();

        refusal = new EvidenceTooLargeException(8_388_608);
        MockHttpServletResponse tooLarge = mvc.perform(post(path).contentType("application/json")
                .content("{\"format\":\"csv\",\"versions\":[1]}")).andReturn().getResponse();
        assertThat(tooLarge.getStatus()).isEqualTo(422);
        assertThat(json(tooLarge).path("code").asString()).isEqualTo("EVIDENCE_TOO_LARGE");

        permissions = Set.of("rules:log:read");
        refusal = null;
        assertThat(status(post(path).contentType("application/json").content("{\"format\":\"csv\",\"versions\":[1]}")))
                .isEqualTo(403);
    }

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }
}
