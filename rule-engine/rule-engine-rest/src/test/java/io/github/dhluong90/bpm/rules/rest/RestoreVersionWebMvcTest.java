package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;

/** {@code POST .../versions?copyOf={v}} over Spring MVC (RLE-062, RLE-063): what reaches the engine, and what does not. */
class RestoreVersionWebMvcTest {

    static final String BASE = "/api/bpm/rules/v1/rule-sets/card-fee/versions";

    Set<String> permissions;
    final List<String> calls = new ArrayList<>();
    final List<WriteOptions> options = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:author");
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
                    if (method.getName().equals("copyVersion")) {
                        calls.add(args[0] + " v" + args[1]);
                        options.add((WriteOptions) args[2]);
                        return new RuleSetVersion("card-fee", 3, VersionStatus.DRAFT, "FIRST", null, 0, "{}", "h1",
                                SourceFormat.YAML, "s1", null, null, null, null, null, "lan",
                                Instant.parse("2027-02-01T09:00:00Z"), null, (Integer) args[1], List.of());
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(service, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString());
    }

    @Test
    void RLE_062_copy_of_a_version_answers_201_with_the_new_draft_and_passes_the_key_on() throws Exception {
        // When lan sends ?copyOf=1 with no body and Idempotency-Key k-601
        MockHttpServletResponse response = mvc.perform(post(BASE).param("copyOf", "1")
                .header("Idempotency-Key", "k-601")).andReturn().getResponse();

        // Then 201 with Location, the draft naming v1, and the engine was asked once with the key
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Location")).isEqualTo(BASE + "/3");
        JsonNode body = json(response);
        assertThat(body.get("version").asInt()).isEqualTo(3);
        assertThat(body.get("status").asString()).isEqualTo("DRAFT");
        assertThat(body.get("copiedFrom").asInt()).isEqualTo(1);
        assertThat(calls).containsExactly("card-fee v1");
        assertThat(options).singleElement().satisfies(o -> assertThat(o.idempotencyKey()).isEqualTo("k-601"));
    }

    @Test
    void RLE_063_a_bad_copy_of_or_a_body_with_it_is_input_invalid_and_never_reaches_the_engine() throws Exception {
        for (String bad : List.of("0", "-1", "abc", "1.5", "99999999999")) {
            MockHttpServletResponse response = mvc.perform(post(BASE).param("copyOf", bad)).andReturn().getResponse();
            assertThat(response.getStatus()).as("copyOf=" + bad).isEqualTo(400);
            assertThat(json(response).get("code").asString()).isEqualTo("INPUT_INVALID");
        }
        MockHttpServletResponse withBody = mvc.perform(post(BASE).param("copyOf", "1")
                .contentType("application/yaml").content("ruleSet: card-fee\n")).andReturn().getResponse();
        assertThat(withBody.getStatus()).isEqualTo(400);
        assertThat(json(withBody).get("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(calls).isEmpty();
    }

    @Test
    void RLE_063_a_reader_is_refused_before_anything_is_read() throws Exception {
        permissions = Set.of("rules:read");

        MockHttpServletResponse response = mvc.perform(post(BASE).param("copyOf", "1")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(json(response).get("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(calls).isEmpty();
    }
}
