package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.VersionService;
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

/** {@code GET /approvals} over Spring MVC (RLE-190, #31): the page shape, the paging parameters and the permission. */
class PendingApprovalsWebMvcTest {

    static final String URL = "/api/bpm/rules/v1/approvals";
    static final String HASH = "3f1c9a5e0b7d2c4a6e8f0a1b3c5d7e9f1a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d";

    Set<String> permissions;
    final List<PageRequest> requests = new ArrayList<>();
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        permissions = Set.of("rules:read", "rules:approve");
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "bob";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions;
            }
        };
        VersionService service = (VersionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {VersionService.class}, (self, method, args) -> {
                    if (method.getName().equals("pendingApprovals")) {
                        PageRequest request = (PageRequest) args[0];
                        requests.add(request);
                        if ("bad".equals(request.cursor())) {
                            throw new QueryInvalidException("cursor was not issued by this list; pass nextCursor unchanged");
                        }
                        return new Page<>(List.of(new PendingApproval("card-fee", 2, HASH, 1, "lan",
                                Instant.parse("2026-12-15T01:30:00Z"), List.of("approve", "reject"), Map.of())),
                                "next-cursor");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        mvc = MockMvcBuilders.standaloneSetup(new ApprovalController(service, callers))
                .setControllerAdvice(new ProblemDetails()).build();
    }

    @Test
    void RLE_190_answers_the_page_with_the_hash_the_reviewer_sends_back_and_the_callers_actions() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(URL).param("limit", "20").param("cursor", "c1"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode body = EngineJson.JSON.readTree(response.getContentAsString());
        JsonNode item = body.get("data").get(0);
        assertThat(item.get("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(item.get("version").asInt()).isEqualTo(2);
        assertThat(item.get("canonicalHash").asString()).isEqualTo(HASH);
        assertThat(item.get("lockVersion").asLong()).isEqualTo(1);
        assertThat(item.get("requestedBy").asString()).isEqualTo("lan");
        assertThat(item.get("requestedAt").asString()).isEqualTo("2026-12-15T01:30:00Z");
        assertThat(item.get("allowedActions").toString()).isEqualTo("[\"approve\",\"reject\"]");
        assertThat(item.get("deniedActions").size()).isZero();
        assertThat(body.get("nextCursor").asString()).isEqualTo("next-cursor");
        assertThat(body.get("hasNext").asBoolean()).isTrue();
        assertThat(item.has("definition")).isFalse();
        assertThat(requests).singleElement().satisfies(r -> {
            assertThat(r.limit()).isEqualTo(20);
            assertThat(r.cursor()).isEqualTo("c1");
        });
    }

    @Test
    void RLE_190_a_caller_without_read_is_refused_and_a_bad_limit_or_cursor_is_query_invalid() throws Exception {
        MockHttpServletResponse bad = mvc.perform(get(URL).param("limit", "many")).andReturn().getResponse();
        MockHttpServletResponse forged = mvc.perform(get(URL).param("cursor", "bad")).andReturn().getResponse();
        permissions = Set.of("rules:approve");
        MockHttpServletResponse refused = mvc.perform(get(URL)).andReturn().getResponse();

        assertThat(bad.getStatus()).isEqualTo(400);
        assertThat(EngineJson.JSON.readTree(bad.getContentAsString()).get("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(forged.getStatus()).isEqualTo(400);
        assertThat(forged.getContentAsString()).doesNotContain("bad\"");
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(requests).hasSize(1);
    }
}
