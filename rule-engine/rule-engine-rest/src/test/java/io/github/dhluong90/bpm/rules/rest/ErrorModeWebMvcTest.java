package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionLogService;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/** RLE-232: a version and a decision row carry the error mode they evaluate in, the decision row as stored with the decision. */
class ErrorModeWebMvcTest {

    static final String RULES = "/api/bpm/rules/v1";
    static final Instant AT = Instant.parse("2026-11-28T09:00:00Z");
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "minh";
            }

            @Override
            public Set<String> currentPermissions() {
                return Set.of("rules:read", "rules:log:read");
            }
        };
        VersionService versions = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> switch (method.getName()) {
                    case "find" -> Optional.of(new RuleSetVersion("card-fee", 1, VersionStatus.PUBLISHED, "FIRST", AT, 2,
                            "{\"schemaVersion\":1}", "h1", SourceFormat.DMN_XML, "s1", null, "lan", AT, "minh", AT,
                            "lan", AT, null, null, List.of()));
                    case "listVersions" -> new Page<>(List.of(new VersionSummary("card-fee", 1,
                            VersionStatus.PUBLISHED, "FIRST", AT, 2, "h1", "lan", AT, "lan", AT, "minh", AT)), null);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        DecisionLogService decisions = (DecisionLogService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DecisionLogService.class}, (self, method, args) -> {
                    if (!method.getName().equals("get")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    // D1 was made in strict; D0 is a row logged before the mode was stored
                    String mode = args[0].equals("D0") ? null : "strict";
                    return new LoggedDecision(args[0].toString(), "card-fee", 1, "FIRST", "OK", null, "{}", "{}",
                            List.of("R1"), "pricing", null, AT, AT, 40, false, null, mode);
                });
        DecisionLogQuery log = (DecisionLogQuery) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DecisionLogQuery.class}, (self, method, args) -> {
                    if (!method.getName().equals("search")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    return new Page<>(List.of(entry("D1", "strict"), entry("D0", null)), null);
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(versions, callers),
                new DecisionLogController(decisions, log, callers), new DecisionController((RuleEngine) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {RuleEngine.class}, (self, method, args) -> {
                            throw new UnsupportedOperationException(method.getName());
                        }), log, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    static DecisionLogEntry entry(String id, String mode) {
        return new DecisionLogEntry(id, "card-fee", 1, "FIRST", DecisionLogEntry.Outcome.MATCHED, List.of("R1"),
                Map.of(), Map.of(), "pricing", null, AT, AT, AT, 40, false, null, mode);
    }

    JsonNode read(String url) throws Exception {
        return EngineJson.JSON.readTree(mvc.perform(get(url)).andReturn().getResponse().getContentAsString());
    }

    @Test
    void RLE_232_a_version_and_a_version_row_show_strict_for_an_imported_table() throws Exception {
        // Given a version imported from a DMN table, which keeps the strict mode (PD-143)
        // When it is read alone and in the list
        // Then both carry errorMode strict
        assertThat(read(RULES + "/rule-sets/card-fee/versions/1").get("errorMode").asString()).isEqualTo("strict");
        assertThat(read(RULES + "/rule-sets/card-fee/versions").get("data").get(0).get("errorMode").asString())
                .isEqualTo("strict");
    }

    @Test
    void RLE_232_a_decision_row_shows_the_error_mode_it_was_made_in() throws Exception {
        assertThat(read(RULES + "/decisions/D1").get("errorMode").asString()).isEqualTo("strict");
        assertThat(read(RULES + "/decisions").get("data").get(0).get("errorMode").asString()).isEqualTo("strict");
    }

    @Test
    void RLE_232_a_decision_row_without_a_stored_mode_shows_unknown_never_strict() throws Exception {
        // Given decision D0 logged before the mode was stored
        // When it is read alone and in the list
        // Then the mode is unknown
        assertThat(read(RULES + "/decisions/D0").get("errorMode").asString()).isEqualTo("unknown");
        assertThat(read(RULES + "/decisions").get("data").get(1).get("errorMode").asString()).isEqualTo("unknown");
    }

    @Test
    void RLE_232_a_version_whose_document_names_a_mode_shows_that_mode() {
        // Given a canonical document that declares spec (stage S1)
        var node = RestSupport.withErrorMode(EngineJson.NODES.objectNode(), "{\"schemaVersion\":1,\"errorMode\":\"spec\"}");
        assertThat(node.get("errorMode").asString()).isEqualTo("spec");
    }
}
