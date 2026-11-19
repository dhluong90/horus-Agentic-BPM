package com.horus.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/bpm/meta} and {@code GET /api/bpm/me} in a servlet host with the rules entry (RLE-019),
 * with tenancy from the caller's token (TR-SEC-03). Callers are Lan, a pricing analyst at bank-a, and
 * Minh, a pricing approver; Binh works at bank-b.
 */
@ExtendWith(OutputCaptureExtension.class)
class HostMetadataEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void RLE_019_meta_and_me_describe_host_and_caller() {
        // Given a host with the rules entry and lan holding rules:read and rules:author in bank-a
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);

            // When lan calls both endpoints
            MvcResult meta = call(mvc, get("/api/bpm/meta").with(lan()));
            MvcResult me = call(mvc, get("/api/bpm/me").with(lan()));

            // Then meta describes one rules module and no process entry
            assertThat(meta.getResponse().getStatus()).isEqualTo(200);
            JsonNode metaBody = json(meta);
            assertThat(metaBody.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(metaBody.path("modules")).hasSize(1);
            JsonNode rules = metaBody.path("modules").get(0);
            assertThat(rules.path("module").asString()).isEqualTo("rules");
            assertThat(rules.path("apiVersion").asString()).isEqualTo("v1");
            assertThat(rules.path("engineVersion").asString()).isEqualTo("0.1.3");
            assertThat(rules.path("features").isArray()).isTrue();
            assertThat(metaBody.path("tenancy").path("switching").asBoolean(true)).isFalse();
            assertThat(metaBody.path("limits").path("maxPageSize").asInt()).isEqualTo(200);
            assertThat(metaBody.has("hostId")).isFalse();

            // And me names lan, bank-a only, and exactly the two permissions
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            JsonNode meBody = json(me);
            assertThat(meBody.path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(meBody.path("user").path("id").asString()).isEqualTo("lan");
            assertThat(meBody.path("user").path("displayName").asString()).isEqualTo("Lan Nguyen");
            assertThat(meBody.path("tenant").path("current").asString()).isEqualTo("bank-a");
            assertThat(strings(meBody.path("tenant").path("allowed"))).containsExactly("bank-a");
            assertThat(strings(meBody.path("permissions"))).containsExactly("rules:read", "rules:author");
        });
    }

    @Test
    void RLE_019_role_mapping_lists_rules_approve_and_removing_it_drops_it() {
        // Given minh holds the host role PRICING_APPROVER and rules:read, and the host maps the role
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithApproverMapping.class).run(context -> {
            // When minh calls /me
            JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me").with(minh())));

            // Then rules:approve is listed, from the mapping
            assertThat(strings(me.path("permissions"))).containsExactly("rules:read", "rules:approve");
            assertThat(strings(me.path("roles"))).containsExactly("ROLE_PRICING_APPROVER");
        });

        // When the mapping is removed
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me").with(minh())));

            // Then it is not listed
            assertThat(strings(me.path("permissions"))).containsExactly("rules:read");
        });
    }

    @Test
    void RLE_019_meta_and_me_without_token_return_401_problem_without_stack_trace() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            for (String path : List.of("/api/bpm/meta", "/api/bpm/me")) {
                // When the endpoint is called without a token
                MvcResult result = call(mvc, get(path).header("X-Correlation-Id", "corr-401-check"));

                // Then 401 problem details with code and correlationId, and nothing internal
                assertThat(result.getResponse().getStatus()).isEqualTo(401);
                assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
                assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
                JsonNode problem = json(result);
                assertThat(problem.path("code").asString()).isEqualTo("UNAUTHENTICATED");
                assertThat(problem.path("status").asInt()).isEqualTo(401);
                assertThat(problem.path("type").asString())
                        .isEqualTo("https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#unauthenticated");
                assertThat(problem.path("instance").asString()).isEqualTo(path);
                assertThat(problem.path("correlationId").asString()).isEqualTo("corr-401-check");
                assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Exception", "at com.", "at org.", "\tat ");
            }
        });
    }

    @Test
    void RLE_019_caller_without_engine_permissions_gets_200_and_an_empty_list() {
        // Given a teller with a host role and no engine permission
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithApproverMapping.class).run(context -> {
            MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(caller("thu", "bank-a",
                    "ROLE_TELLER")));

            // Then /me still answers, with no permission, and /meta needs no permission either
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(me).path("permissions").isArray()).isTrue();
            assertThat(json(me).path("permissions")).isEmpty();
            assertThat(call(TestHosts.mockMvc(context), get("/api/bpm/meta").with(caller("thu", "bank-a",
                    "ROLE_TELLER"))).getResponse().getStatus()).isEqualTo(200);
        });
    }

    @Test
    void RLE_019_authorities_outside_the_installed_vocabulary_are_never_listed() {
        // Given a caller whose token carries a misspelt permission, a process permission on a host
        // without the Process Engine, and an OAuth2 scope
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me").with(caller("lan", "bank-a",
                    "rules:delete", "processes:read", "SCOPE_profile", "rules:simulate"))));

            // Then only the rules permission is listed
            assertThat(strings(me.path("permissions"))).containsExactly("rules:simulate");
        });
    }

    @Test
    void RLE_019_responses_are_privately_cacheable_with_an_etag_and_answer_304() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            for (String path : List.of("/api/bpm/meta", "/api/bpm/me")) {
                MvcResult first = call(mvc, get(path).with(lan()));
                String etag = first.getResponse().getHeader("ETag");

                assertThat(first.getResponse().getHeader("Cache-Control")).isEqualTo("private, max-age=60");
                assertThat(etag).startsWith("W/\"");
                MvcResult again = call(mvc, get(path).with(lan()).header("If-None-Match", etag));
                assertThat(again.getResponse().getStatus()).isEqualTo(304);
                assertThat(again.getResponse().getContentAsString()).isEmpty();
            }

            // A different caller sees a different /me, so a different ETag
            String lanTag = call(mvc, get("/api/bpm/me").with(lan())).getResponse().getHeader("ETag");
            String minhTag = call(mvc, get("/api/bpm/me").with(minh())).getResponse().getHeader("ETag");
            assertThat(minhTag).isNotEqualTo(lanTag);
            assertThat(call(mvc, get("/api/bpm/me").with(lan())).getResponse().getHeaders("Vary"))
                    .anySatisfy(vary -> assertThat(vary).contains("Authorization"));
        });
    }

    @Test
    void TR_SEC_03_tenant_comes_from_the_token_and_a_query_parameter_is_ignored() {
        // Given binh works at bank-b
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            // When binh asks for bank-a through a query parameter
            JsonNode me = json(call(TestHosts.mockMvc(context),
                    get("/api/bpm/me").param("tenantId", "bank-a").with(caller("binh", "bank-b", "rules:read"))));

            // Then the answer is bank-b's, and bank-a appears nowhere
            assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-b");
            assertThat(strings(me.path("tenant").path("allowed"))).containsExactly("bank-b");
            assertThat(me.toString()).doesNotContain("bank-a");
        });
    }

    @Test
    void TR_SEC_03_tenant_header_naming_another_tenant_is_403_tenant_not_allowed_and_logged(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            for (String path : List.of("/api/bpm/me", "/api/bpm/meta")) {
                // When lan of bank-a sends X-Tenant-Id: bank-b while switching is off
                MvcResult result = call(mvc, get(path).with(lan()).header("X-Tenant-Id", "bank-b")
                        .header("X-Correlation-Id", "corr-403-" + path.length()));

                // Then 403 TENANT_NOT_ALLOWED with no caller or tenant data, and a security log entry
                assertThat(result.getResponse().getStatus()).isEqualTo(403);
                JsonNode problem = json(result);
                assertThat(problem.path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                assertThat(problem.has("permissions")).isFalse();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("bank-a", "lan");
                assertThat(output).contains("tenant.switch.refused user=lan requestedTenant=bank-b correlationId=corr-403-"
                        + path.length());
            }

            // And the header naming the caller's own tenant is accepted
            assertThat(call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a"))
                    .getResponse().getStatus()).isEqualTo(200);
        });
    }

    @Test
    void TR_SEC_03_token_without_a_tenant_claim_is_401() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MvcResult result = call(TestHosts.mockMvc(context), get("/api/bpm/me")
                    .with(jwt().jwt(token -> token.subject("lan")).authorities(new SimpleGrantedAuthority("rules:read"))));

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(json(result).path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(json(result).path("detail").asString()).contains("tenant_id claim");
        });
    }

    @Test
    void TR_SEC_03_gateway_mode_reads_the_tenant_header_set_by_the_gateway() {
        // Given a host behind a gateway that authenticates and sets X-Tenant-Id
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.resolver=header:X-Tenant-Id")
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    var lanBasic = user("lan").authorities(new SimpleGrantedAuthority("rules:read"));

                    JsonNode me = json(call(mvc, get("/api/bpm/me").with(lanBasic).header("X-Tenant-Id", "bank-a")));
                    MvcResult missing = call(mvc, get("/api/bpm/me").with(lanBasic));

                    assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-a");
                    assertThat(me.path("user").has("displayName")).isFalse();
                    assertThat(missing.getResponse().getStatus()).isEqualTo(401);
                    assertThat(json(missing).path("detail").asString()).contains("X-Tenant-Id header");
                });
    }

    @Test
    void responses_carry_the_callers_correlation_id_or_a_new_ulid() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);

            String echoed = call(mvc, get("/api/bpm/me").with(lan()).header("X-Correlation-Id", "req-42"))
                    .getResponse().getHeader("X-Correlation-Id");
            String spaces = call(mvc, get("/api/bpm/me").with(lan()).header("X-Correlation-Id", "bad id; DROP"))
                    .getResponse().getHeader("X-Correlation-Id");
            String tooLong = call(mvc, get("/api/bpm/me").with(lan()).header("X-Correlation-Id", "a".repeat(65)))
                    .getResponse().getHeader("X-Correlation-Id");

            assertThat(echoed).isEqualTo("req-42");
            assertThat(spaces).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
            assertThat(tooLong).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
        });
    }

    static JwtRequestPostProcessor lan() {
        return caller("lan", "bank-a", "rules:read", "rules:author")
                .jwt(token -> token.subject("lan").claim("tenant_id", "bank-a").claim("name", "Lan Nguyen"));
    }

    static JwtRequestPostProcessor minh() {
        return caller("minh", "bank-a", "ROLE_PRICING_APPROVER", "rules:read");
    }

    static JwtRequestPostProcessor caller(String subject, String tenant, String... authorities) {
        return jwt().jwt(token -> token.subject(subject).claim("tenant_id", tenant))
                .authorities(Arrays.stream(authorities).<GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }

    static MvcResult call(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    static List<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }
}
