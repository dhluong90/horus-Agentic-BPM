package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.dhluong90.bpm.rules.api.InvalidStateException;
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

/** {@code POST .../versions/{v}:discard} over Spring MVC (RLE-098): the headers it needs and the answer it gives. */
class DiscardDraftWebMvcTest {

    static final String URL = "/api/bpm/rules/v1/rule-sets/card-fee/versions/2:discard";

    Set<String> permissions;
    RuntimeException refusal;
    final List<WriteOptions> options = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:author");
        refusal = null;
        options.clear();
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
                    if (!method.getName().equals("discard")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    options.add((WriteOptions) args[2]);
                    if (refusal != null) {
                        throw refusal;
                    }
                    return new RuleSetVersion("card-fee", 2, VersionStatus.DISCARDED, "FIRST", null, 4, "{}", "h1",
                            SourceFormat.YAML, "s1", null, null, null, null, null, "lan",
                            Instant.parse("2027-02-01T09:00:00Z"), null, null, List.of());
                });
        mvc = MockMvcBuilders.standaloneSetup(new RuleSetController(service, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    static JsonNode json(MockHttpServletResponse response) throws Exception {
        return EngineJson.JSON.readTree(response.getContentAsString());
    }

    @Test
    void RLE_098_a_discard_with_both_headers_is_200_with_the_discarded_version_and_its_new_etag() throws Exception {
        MockHttpServletResponse response = mvc.perform(post(URL).header("Idempotency-Key", "k-920")
                .header("If-Match", "\"3\"").contentType("application/json").content("{\"reason\":\"Wrong base\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("ETag")).isEqualTo("\"4\"");
        assertThat(json(response).get("status").asString()).isEqualTo("DISCARDED");
        assertThat(options).singleElement().satisfies(o -> {
            assertThat(o.idempotencyKey()).isEqualTo("k-920");
            assertThat(o.expectedLockVersion()).isEqualTo(3L);
            assertThat(o.comment()).isEqualTo("Wrong base");
        });
    }

    @Test
    void RLE_098_a_discard_without_if_match_is_428_and_without_a_key_is_400_and_a_reader_is_403() throws Exception {
        MockHttpServletResponse noMatch = mvc.perform(post(URL).header("Idempotency-Key", "k-921"))
                .andReturn().getResponse();
        assertThat(noMatch.getStatus()).isEqualTo(428);
        assertThat(json(noMatch).get("code").asString()).isEqualTo("PRECONDITION_REQUIRED");

        MockHttpServletResponse noKey = mvc.perform(post(URL).header("If-Match", "\"3\"")).andReturn().getResponse();
        assertThat(noKey.getStatus()).isEqualTo(400);
        assertThat(json(noKey).get("code").asString()).isEqualTo("INPUT_INVALID");

        permissions = Set.of("rules:read");
        MockHttpServletResponse reader = mvc.perform(post(URL).header("Idempotency-Key", "k-922")
                .header("If-Match", "\"3\"")).andReturn().getResponse();
        assertThat(reader.getStatus()).isEqualTo(403);
        assertThat(options).isEmpty();
    }

    @Test
    void RLE_098_a_version_that_is_not_a_draft_is_409_invalid_state() throws Exception {
        refusal = new InvalidStateException("card-fee", 2, VersionStatus.PUBLISHED, "discard");

        MockHttpServletResponse response = mvc.perform(post(URL).header("Idempotency-Key", "k-923")
                .header("If-Match", "\"3\"")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("INVALID_STATE");
    }
}
