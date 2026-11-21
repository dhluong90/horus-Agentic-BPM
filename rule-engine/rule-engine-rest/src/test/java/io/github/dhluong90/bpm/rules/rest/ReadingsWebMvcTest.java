package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
import io.github.dhluong90.bpm.rules.api.RuleReadings.Reading;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.VersionDiff;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/**
 * The readings endpoints over Spring MVC (RLE-091 to RLE-093): the permission checked first, what reaches the engine,
 * the body that comes back and the refusal of a request that is too large.
 */
class ReadingsWebMvcTest {

    static final String BASE = "/api/bpm/rules/v1/rule-sets/account-tier/versions/";
    static final Map<String, Reading> ROW = Map.of(
            "en", new Reading("Rule R1 applies when Balance is at least 50,000,000 VND.", "Balance >= 50,000,000 VND",
                    "GENERATED"),
            "vi", new Reading("Quy tắc R1 áp dụng khi Balance ít nhất là 50.000.000 VND.", "Balance >= 50.000.000 VND",
                    "GENERATED"));

    Set<String> permissions;
    RuntimeException refusal;
    final List<String> calls = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:simulate");
        refusal = null;
        calls.clear();
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "minh";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        VersionService versions = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> {
                    calls.add(method.getName());
                    if (refusal != null) {
                        throw refusal;
                    }
                    return switch (method.getName()) {
                        case "readings" -> new RuleReadings.RuleSetReadings("account-tier", (int) args[1],
                                List.of("en", "vi"), List.of(new RuleReadings.Row("R1", 1, ROW)));
                        case "readingsDiff" -> new RuleReadings.ReadingsDiff("account-tier", (int) args[1],
                                (int) args[2], List.of("en", "vi"), List.of(
                                        new RuleReadings.ChangedRow("R1", VersionDiff.Change.CHANGED, 1, 1, true, ROW, ROW),
                                        new RuleReadings.ChangedRow("R9", VersionDiff.Change.ADDED, 2, null, true, Map.of(),
                                                ROW)), 3);
                        case "readSimulation" -> {
                            RuleReadings.OutcomeReading outcome = new RuleReadings.OutcomeReading("MATCHED",
                                    List.of("R1"), ROW);
                            yield new RuleReadings.SimulationReadings("account-tier", 2, 1, List.of("en", "vi"),
                                    List.of(new RuleReadings.SampleReading(0, "S1",
                                            args.length > 1 && !((List<?>) args[1]).isEmpty()
                                                    ? (String) ((List<?>) args[1]).get(0) : null,
                                            true, outcome, null)));
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        RuleEngine engine = (RuleEngine) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RuleEngine.class}, (self, method, args) -> {
                    if (!method.getName().equals("simulate")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    calls.add("simulate");
                    return new SimulationResult("account-tier", 2, VersionStatus.DRAFT, 1,
                            Instant.parse("2026-12-01T00:00:00Z"),
                            List.of(new SimulationResult.Sample(0, new DecisionResult.NoMatch(), null, true)));
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(versions, callers),
                new SimulationController(engine, versions, callers)).setControllerAdvice(new ProblemDetails()).build();
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString());
    }

    @Test
    void RLE_091_every_row_comes_back_with_a_full_sentence_and_a_short_form_per_language() throws Exception {
        // When minh, who reads, asks for version 1
        MockHttpServletResponse response = mvc.perform(get(BASE + "1/readings")).andReturn().getResponse();

        // Then 200, with both languages and both styles and the source of each sentence
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("languages").get(1).asString()).isEqualTo("vi");
        JsonNode row = body.get("rows").get(0);
        assertThat(row.get("ruleId").asString()).isEqualTo("R1");
        assertThat(row.get("readings").get("en").get("full").asString())
                .isEqualTo("Rule R1 applies when Balance is at least 50,000,000 VND.");
        assertThat(row.get("readings").get("vi").get("short").asString()).isEqualTo("Balance >= 50.000.000 VND");
        assertThat(row.get("readings").get("en").get("source").asString()).isEqualTo("GENERATED");
        assertThat(calls).containsExactly("readings");
    }

    @Test
    void RLE_091_without_rules_read_nothing_reaches_the_engine() throws Exception {
        permissions = Set.of("rules:evaluate");

        MockHttpServletResponse response = mvc.perform(get(BASE + "1/readings")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json(response).get("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_091_a_request_that_covers_too_many_rows_is_422_readings_too_large_with_the_limit_named() throws Exception {
        refusal = new ReadingsTooLargeException("rows", 501, 500);

        MockHttpServletResponse response = mvc.perform(get(BASE + "1/readings")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(422);
        JsonNode body = json(response);
        assertThat(body.get("code").asString()).isEqualTo("READINGS_TOO_LARGE");
        assertThat(body.get("detail").asString()).contains("at most 500 rows").contains("501");
    }

    @Test
    void RLE_092_a_diff_gives_the_old_and_the_new_reading_and_an_added_row_has_no_old_one() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(BASE + "2/diff/readings").param("base", "1"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("base").asInt()).isEqualTo(1);
        assertThat(body.get("unchangedCount").asInt()).isEqualTo(3);
        JsonNode changed = body.get("rows").get(0);
        assertThat(changed.get("change").asString()).isEqualTo("CHANGED");
        assertThat(changed.get("readingChanged").asBoolean()).isTrue();
        assertThat(changed.get("previous").get("en").get("short").asString()).isEqualTo("Balance >= 50,000,000 VND");
        JsonNode added = body.get("rows").get(1);
        assertThat(added.get("change").asString()).isEqualTo("ADDED");
        assertThat(added.get("previous")).isEmpty();
        assertThat(added.has("basePosition")).isFalse();
    }

    @Test
    void RLE_092_base_is_required_and_must_be_a_version_number() throws Exception {
        assertThat(mvc.perform(get(BASE + "2/diff/readings")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get(BASE + "2/diff/readings").param("base", "x")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(mvc.perform(get(BASE + "2/diff/readings").param("base", "0")).andReturn().getResponse().getStatus())
                .isEqualTo(400);
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_093_a_simulation_is_run_and_read_and_the_sample_inputs_are_not_in_the_answer() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2:simulate-readings")
                .contentType("application/json")
                .content("{\"samples\":[{\"inputs\":{\"nationalId\":\"079123456789\"}}]}")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("baselineVersion").asInt()).isEqualTo(1);
        JsonNode sample = body.get("results").get(0);
        assertThat(sample.get("changed").asBoolean()).isTrue();
        assertThat(sample.get("simulated").get("kind").asString()).isEqualTo("MATCHED");
        assertThat(sample.get("baseline").isNull()).isTrue();
        assertThat(response.getContentAsString()).doesNotContain("079123456789");
        assertThat(calls).containsExactly("simulate", "readSimulation");
    }

    @Test
    void RLE_093_both_permissions_are_needed_and_checked_before_the_body_is_read() throws Exception {
        for (Set<String> missing : List.of(Set.of("rules:read"), Set.of("rules:simulate"))) {
            permissions = missing;
            MockHttpServletResponse response = mvc.perform(post(BASE + "2:simulate-readings")
                    .contentType("application/json").content(new byte[4_194_305])).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_093_the_label_of_a_sample_is_passed_on_and_written_back_with_its_reference() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(BASE + "2:simulate-readings")
                .contentType("application/json")
                .content("{\"samples\":[{\"label\":\"Gold, balance 60,000,000 VND\",\"inputs\":{\"tier\":\"GOLD\"}}]}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode sample = json(response).get("results").get(0);
        assertThat(sample.get("reference").asString()).isEqualTo("S1");
        assertThat(sample.get("label").asString()).isEqualTo("Gold, balance 60,000,000 VND");
        // and a sample with no label has a reference and a null label
        MockHttpServletResponse plain = mvc.perform(post(BASE + "2:simulate-readings")
                .contentType("application/json").content("{\"samples\":[{\"inputs\":{\"tier\":\"GOLD\"}}]}"))
                .andReturn().getResponse();
        assertThat(json(plain).get("results").get(0).get("reference").asString()).isEqualTo("S1");
        assertThat(json(plain).get("results").get(0).get("label").isNull()).isTrue();
    }

    @Test
    void RLE_093_a_label_that_is_not_text_of_1_to_100_characters_is_400_and_nothing_runs() throws Exception {
        for (String label : List.of("7", "\"\"", "\"" + "x".repeat(101) + "\"")) {
            MockHttpServletResponse response = mvc.perform(post(BASE + "2:simulate-readings")
                    .contentType("application/json")
                    .content("{\"samples\":[{\"label\":" + label + ",\"inputs\":{}}]}")).andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(400);
        }
        assertThat(calls).isEmpty();
    }
}
