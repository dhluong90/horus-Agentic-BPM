package com.horus.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Problem bodies are valid JSON whatever the request path, and correlation ids are safe to log. */
class ProblemTest {

    @Test
    void the_body_is_valid_json_even_for_a_hostile_path() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bpm/me\"},{\"x\":\"<script>\\");

        JsonNode body = JsonMapper.builder().build().readTree(Problem.unauthenticated().toJson(request));

        assertThat(body.path("instance").asString()).isEqualTo("/api/bpm/me\"},{\"x\":\"<script>\\");
        assertThat(Problem.unauthenticated().toJson(request)).doesNotContain("<script>");
    }

    @Test
    void the_access_denied_handler_answers_403_permission_denied() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/bpm/rules/v1/rule-sets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        new BpmProblemAccessDeniedHandler().handle(request, response, new AccessDeniedException("denied"));

        JsonNode body = JsonMapper.builder().build().readTree(response.getContentAsString());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(body.path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(body.path("correlationId").asString()).isEqualTo(response.getHeader("X-Correlation-Id"));
    }

    @Test
    void the_entry_point_sends_the_configured_challenge() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new BpmProblemAuthenticationEntryPoint("Basic realm=\"loans\"")
                .commence(new MockHttpServletRequest("GET", "/api/bpm/me"), response, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"loans\"");
    }

    @Test
    void ulids_are_26_crockford_characters_ordered_by_time_and_unique() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(CorrelationIds.newUlid(1_763_510_400_000L));
        }

        assertThat(ids).hasSize(1000).allSatisfy(id -> assertThat(id).matches("^[0-7][0-9A-HJKMNP-TV-Z]{25}$"));
        assertThat(CorrelationIds.newUlid(1_763_510_400_000L).substring(0, 10))
                .isLessThan(CorrelationIds.newUlid(1_763_510_400_001L).substring(0, 10));
    }

    @Test
    void log_values_are_escaped_recoverably_and_capped() {
        assertThat(CorrelationIds.loggable("bank-b")).isEqualTo("bank-b");
        assertThat(CorrelationIds.loggable("lan@bank-a.vn")).isEqualTo("lan%40bank-a.vn");
        assertThat(CorrelationIds.loggable("bank-b\nFAKE ENTRY")).isEqualTo("bank-b%0AFAKE%20ENTRY");
        assertThat(CorrelationIds.loggable("Lê")).isEqualTo("L%C3%AA");
        assertThat(CorrelationIds.loggable(null)).isEqualTo("-");
        assertThat(CorrelationIds.loggable("a".repeat(500))).hasSize(131).endsWith("...");
    }
}
