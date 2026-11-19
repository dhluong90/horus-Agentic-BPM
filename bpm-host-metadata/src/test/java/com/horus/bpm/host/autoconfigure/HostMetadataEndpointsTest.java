package com.horus.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/bpm/meta} and {@code GET /api/bpm/me} in a servlet host with the rules entry (RLE-019).
 * Callers are Lan, a pricing author at bank-a, and Minh, a pricing approver; Thu is a teller with no
 * engine permission. Tenancy cases are in {@link TenantResolutionTest}.
 */
class HostMetadataEndpointsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final String ULID = "^[0-7][0-9A-HJKMNP-TV-Z]{25}$";

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

            // Then rules:approve is listed, from the mapping, and the mapped role is shown
            assertThat(strings(me.path("permissions"))).containsExactly("rules:read", "rules:approve");
            assertThat(strings(me.path("roles"))).containsExactly("ROLE_PRICING_APPROVER");
        });

        // When the mapping is removed
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me").with(minh())));

            // Then it is not listed, and the role no longer means anything to the engines
            assertThat(strings(me.path("permissions"))).containsExactly("rules:read");
            assertThat(me.path("roles")).isEmpty();
        });
    }

    @Test
    void RLE_019_meta_and_me_without_token_return_401_problem_without_stack_trace() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            for (String path : List.of("/api/bpm/meta", "/api/bpm/me")) {
                // When the endpoint is called without a token
                MvcResult result = call(mvc, get(path).header("X-Correlation-Id", "corr-401-check"));

                // Then 401 problem details with code and a host-issued correlationId, and nothing internal
                assertThat(result.getResponse().getStatus()).isEqualTo(401);
                assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
                assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
                JsonNode problem = json(result);
                assertThat(problem.path("code").asString()).isEqualTo("UNAUTHENTICATED");
                assertThat(problem.path("status").asInt()).isEqualTo(401);
                assertThat(problem.path("type").asString())
                        .isEqualTo("https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#unauthenticated");
                assertThat(problem.path("instance").asString()).isEqualTo(path);
                assertThat(problem.path("correlationId").asString()).matches(ULID)
                        .isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
                assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("Exception", "at com.", "at org.", "\tat ", "corr-401-check");
            }
        });
    }

    @Test
    void RLE_019_host_chain_that_lets_requests_through_still_gets_a_401_problem_from_the_endpoints() {
        // Given a host whose chain permits /api/bpm/** and wires no engine handler
        TestHosts.runner(TestHosts.PermitAllSecurity.class).withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    for (String path : List.of("/api/bpm/meta", "/api/bpm/me")) {
                        // When an anonymous request reaches the controller
                        MvcResult result = call(mvc, get(path));

                        // Then the controller's own check refuses it, with the challenge and no data
                        assertThat(result.getResponse().getStatus()).isEqualTo(401);
                        assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer");
                        assertThat(json(result).path("code").asString()).isEqualTo("UNAUTHENTICATED");
                        assertThat(result.getResponse().getContentAsString()).doesNotContain("modules", "permissions");
                    }
                });
    }

    @Test
    void RLE_019_caller_without_engine_permissions_gets_200_and_an_empty_list() {
        // Given a teller with a host role that no engine maps, and no engine permission
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithApproverMapping.class).run(context -> {
            JwtRequestPostProcessor thu = caller("thu", "bank-a", "ROLE_TELLER");
            MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(thu));

            // Then /me still answers, with no permission and no role, and /meta needs no permission either
            assertThat(me.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(me).path("permissions").isArray()).isTrue();
            assertThat(json(me).path("permissions")).isEmpty();
            assertThat(json(me).path("roles")).isEmpty();
            assertThat(call(TestHosts.mockMvc(context), get("/api/bpm/meta").with(thu)).getResponse().getStatus())
                    .isEqualTo(200);
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
            assertThat(me.path("roles")).isEmpty();
        });
    }

    @Test
    void RLE_019_meta_is_cacheable_for_a_minute_and_me_is_revalidated_every_time() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            MvcResult meta = call(mvc, get("/api/bpm/meta").with(lan()));
            MvcResult me = call(mvc, get("/api/bpm/me").with(lan()));

            assertThat(meta.getResponse().getHeader("Cache-Control")).isEqualTo("private, max-age=60");
            assertThat(me.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-cache");
            assertThat(String.join(", ", me.getResponse().getHeaders("Vary")))
                    .isEqualTo("Authorization, Cookie, X-Tenant-Id");
            for (MvcResult first : List.of(meta, me)) {
                String etag = first.getResponse().getHeader("ETag");
                assertThat(etag).startsWith("W/\"");
                MvcResult again = call(mvc, get(first.getRequest().getRequestURI()).with(lan()).header("If-None-Match", etag));
                assertThat(again.getResponse().getStatus()).isEqualTo(304);
                assertThat(again.getResponse().getContentAsString()).isEmpty();
            }

            // A different caller sees a different /me, so a different ETag
            String minhTag = call(mvc, get("/api/bpm/me").with(minh())).getResponse().getHeader("ETag");
            assertThat(minhTag).isNotEqualTo(me.getResponse().getHeader("ETag"));
        });
    }

    @Test
    void RLE_019_me_varies_on_the_configured_tenant_header_too() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.resolver=header:X-Bank-Tenant", "horus.bpm.tenancy.trusted-gateway=true")
                .run(context -> {
                    MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(lan()).header("X-Bank-Tenant", "bank-a"));

                    assertThat(me.getResponse().getStatus()).isEqualTo(200);
                    assertThat(String.join(", ", me.getResponse().getHeaders("Vary")))
                            .isEqualTo("Authorization, Cookie, X-Tenant-Id, X-Bank-Tenant");
                });
    }

    @Test
    void responses_carry_a_host_issued_correlation_id_whatever_the_caller_sends() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);

            String withClientId = call(mvc, get("/api/bpm/me").with(lan()).header("X-Correlation-Id", "req-42"))
                    .getResponse().getHeader("X-Correlation-Id");
            String withoutClientId = call(mvc, get("/api/bpm/me").with(lan())).getResponse().getHeader("X-Correlation-Id");

            assertThat(withClientId).matches(ULID);
            assertThat(withoutClientId).matches(ULID).isNotEqualTo(withClientId);
        });
    }

    @Test
    void endpoints_can_be_switched_off() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.host-metadata.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HostMetadataController.class));
    }

    /** A stand-in for an engine endpoint, to check the host layer's cache policy. */
    @RestController
    static class StandInEngineEndpoint {
        @GetMapping("/api/bpm/rules/v1/rule-sets")
        String ruleSets() {
            return "[]";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithStandInEngineEndpoint {
        @Bean
        StandInEngineEndpoint standInEngineEndpoint() {
            return new StandInEngineEndpoint();
        }
    }

    @Test
    void TR_SEC_09_engine_api_responses_are_no_store_and_meta_keeps_its_own_policy() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, WithStandInEngineEndpoint.class)
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    FilterRegistrationBean<EngineApiCacheControlFilter> registration =
                            context.getBean("horusBpmEngineApiCacheControl", FilterRegistrationBean.class);
                    MockMvc mvc = MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                            .addFilter(registration.getFilter(), "/api/bpm/rules/*", "/api/bpm/processes/*")
                            .apply(springSecurity()).build();

                    MvcResult engine = call(mvc, get("/api/bpm/rules/v1/rule-sets").with(lan()));
                    MvcResult meta = call(mvc, get("/api/bpm/meta").with(lan()));

                    assertThat(registration.getUrlPatterns()).containsExactly("/api/bpm/rules/*", "/api/bpm/processes/*");
                    assertThat(engine.getResponse().getStatus()).isEqualTo(200);
                    assertThat(engine.getResponse().getHeaders("Cache-Control")).containsExactly("no-store");
                    assertThat(meta.getResponse().getHeaders("Cache-Control")).containsExactly("private, max-age=60");
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
