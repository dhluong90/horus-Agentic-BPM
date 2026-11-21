package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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

/** {@code POST .../versions/{v}:validate} over Spring MVC (RLE-090): what reaches the engine, and the body it answers. */
class ValidateDraftWebMvcTest {

    static final String URL = "/api/bpm/rules/v1/rule-sets/card-fee/versions/3:validate";

    Set<String> permissions;
    RuntimeException refusal;
    final List<String> calls = new ArrayList<>();
    final List<ImportRequest> requests = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:author");
        refusal = null;
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
        VersionService service = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> {
                    if (!method.getName().equals("validateDraft")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    calls.add(args[0] + " v" + args[1]);
                    requests.add((ImportRequest) args[2]);
                    if (refusal != null) {
                        throw refusal;
                    }
                    return new DraftValidation("card-fee", 3, false,
                            List.of(new DraftValidation.Problem("R2", "balance", "TYPE_MISMATCH", 8,
                                    Map.of("en", "The cell mixes values.", "vi", "Ô này trộn các giá trị."))),
                            false, List.of());
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(service, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString());
    }

    @Test
    void RLE_090_a_cell_problem_is_answered_200_on_its_rule_and_column_with_no_key_needed() throws Exception {
        // When lan posts the edited table with no Idempotency-Key and no If-Match
        MockHttpServletResponse response = mvc.perform(post(URL).contentType("application/yaml")
                .content("ruleSet: card-fee\n")).andReturn().getResponse();

        // Then 200, not valid, the problem on R2 / balance with a code, a position and both languages
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("valid").asBoolean()).isFalse();
        JsonNode problem = body.get("problems").get(0);
        assertThat(problem.get("ruleId").asString()).isEqualTo("R2");
        assertThat(problem.get("column").asString()).isEqualTo("balance");
        assertThat(problem.get("code").asString()).isEqualTo("TYPE_MISMATCH");
        assertThat(problem.get("position").asInt()).isEqualTo(8);
        assertThat(problem.get("message").get("vi").asString()).isEqualTo("Ô này trộn các giá trị.");
        assertThat(body.get("problemsTruncated").asBoolean()).isFalse();
        assertThat(body.get("readings")).isEmpty();
        assertThat(calls).containsExactly("card-fee v3");
        assertThat(requests.getFirst().idempotencyKey()).isNull();
    }

    @Test
    void RLE_090_a_reader_is_refused_before_the_body_is_read() throws Exception {
        permissions = Set.of("rules:read");

        MockHttpServletResponse response = mvc.perform(post(URL).contentType("application/yaml")
                .content("ruleSet: card-fee\n")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json(response).get("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_090_a_body_over_the_import_limit_is_413_and_never_reaches_the_engine() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(URL).contentType("application/yaml")
                .content(new byte[1_048_577])).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(json(response).get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_090_a_version_that_is_not_a_draft_is_409_invalid_state() throws Exception {
        refusal = new InvalidStateException("card-fee", 3, VersionStatus.PUBLISHED, "validate");

        MockHttpServletResponse response = mvc.perform(post(URL).contentType("application/yaml")
                .content("ruleSet: card-fee\n")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_STATE");
    }
}
