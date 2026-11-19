package com.horus.bpm.host.autoconfigure;

import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.caller;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.lan;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.minh;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.horus.bpm.host.spi.EngineAccess;
import com.horus.bpm.host.spi.EngineAccessDeniedException;
import com.horus.bpm.host.spi.EngineCaller;
import com.horus.bpm.host.spi.TenantResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The shared caller and permission seam, {@link EngineAccess}, and the controls that stay on when the
 * metadata endpoints are switched off (ADR-0024 decision 5). A stand-in engine endpoint enforces with the
 * seam exactly as an engine controller does. Lan is a pricing author at bank-a, Minh a pricing approver
 * and Thu a teller with no engine permission.
 */
@ExtendWith(OutputCaptureExtension.class)
class EngineAccessTest {

    private static final String METADATA_OFF = "horus.bpm.host-metadata.enabled=false";

    /** A stand-in engine endpoint: refuses unless the caller holds the permission, then names the tenant. */
    @RestController
    static class ProbeEngineEndpoint {

        private final EngineAccess access;

        ProbeEngineEndpoint(EngineAccess access) {
            this.access = access;
        }

        @GetMapping({"/api/bpm/rules/v1/probe", "/api/bpm/processes/v1/probe"})
        String probe(@RequestParam String permission) {
            return access.requirePermission(permission).tenant();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithProbeEngineEndpoint {
        @Bean
        ProbeEngineEndpoint probeEngineEndpoint(EngineAccess access) {
            return new ProbeEngineEndpoint(access);
        }
    }

    /** A host resolver that puts every caller in bank-c. */
    @Configuration(proxyBeanMethods = false)
    static class FixedHostTenant {
        @Bean
        TenantResolver hostTenantResolver() {
            return caller -> Optional.of("bank-c");
        }
    }

    /** A host that replaces the seam: everyone in bank-z, and only rules:read is ever granted. */
    @Configuration(proxyBeanMethods = false)
    static class HostEngineAccess {
        @Bean
        EngineAccess hostEngineAccess() {
            return new EngineAccess() {
                @Override
                public EngineCaller currentCaller() {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    return new EngineCaller(authentication.getName(), null, "bank-z", Set.of(), List.of());
                }

                @Override
                public boolean hasPermission(EngineCaller caller, String permission) {
                    return permission.equals("rules:read");
                }
            };
        }
    }

    /** MockMvc with the engine API cache filter, which a servlet container would apply from its registration. */
    private static MockMvc mockMvcWithCacheFilter(ApplicationContext context) {
        @SuppressWarnings("unchecked")
        FilterRegistrationBean<EngineApiCacheControlFilter> registration =
                context.getBean("horusBpmEngineApiCacheControl", FilterRegistrationBean.class);
        return MockMvcBuilders.webAppContextSetup((WebApplicationContext) context)
                .addFilter(registration.getFilter(), registration.getUrlPatterns().toArray(String[]::new))
                .apply(springSecurity()).build();
    }

    /** The permissions the stand-in engine endpoint lets the caller use, by asking it once per permission. */
    private static List<String> enforced(MockMvc mvc, JwtRequestPostProcessor who) throws Exception {
        List<String> allowed = new ArrayList<>();
        for (String permission : TestHosts.RULES_PERMISSIONS) {
            MvcResult result = call(mvc, get("/api/bpm/rules/v1/probe").param("permission", permission).with(who));
            int status = result.getResponse().getStatus();
            assertThat(status).as(permission).isIn(200, 403);
            if (status == 200) {
                allowed.add(permission);
            } else {
                assertThat(json(result).path("code").asString()).isEqualTo("PERMISSION_DENIED");
            }
        }
        return allowed;
    }

    @Test
    void TR_SEC_09_metadata_switched_off_keeps_no_store_on_engine_responses_and_serves_no_metadata() {
        // Given a host that switches the metadata endpoints off
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, WithProbeEngineEndpoint.class)
                .withPropertyValues(METADATA_OFF)
                .run(context -> {
                    MockMvc mvc = mockMvcWithCacheFilter(context);

                    // When lan calls a rules and a processes engine path
                    for (String path : List.of("/api/bpm/rules/v1/probe", "/api/bpm/processes/v1/probe")) {
                        MvcResult engine = call(mvc, get(path).param("permission", "rules:read").with(lan()));

                        // Then the engine response is still no-store
                        assertThat(engine.getResponse().getStatus()).isEqualTo(200);
                        assertThat(engine.getResponse().getHeaders("Cache-Control")).containsExactly("no-store");
                    }

                    // And the two metadata endpoints are not served
                    assertThat(context).doesNotHaveBean(HostMetadataController.class);
                    assertThat(call(mvc, get("/api/bpm/meta").with(lan())).getResponse().getStatus()).isEqualTo(404);
                    assertThat(call(mvc, get("/api/bpm/me").with(lan())).getResponse().getStatus()).isEqualTo(404);
                });
    }

    @Test
    void TR_SEC_03_metadata_switched_off_keeps_the_default_tenant_resolver_and_a_host_resolver_still_wins() {
        // Given the metadata endpoints off and no host resolver: the default resolver and seam are present
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(METADATA_OFF)
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantResolver.class);
                    assertThat(context.getBean(TenantResolver.class)).isInstanceOf(PrincipalTenantResolver.class);
                    assertThat(context.getBean(EngineAccess.class)).isInstanceOf(DefaultEngineAccess.class);
                    assertThat(context).hasSingleBean(BpmProblemAuthenticationEntryPoint.class);
                    assertThat(context).hasSingleBean(BpmProblemAccessDeniedHandler.class);
                    assertThat(context).hasSingleBean(EngineAccessProblems.class);
                });

        // Given a host resolver: the host's bean wins, and engine paths are served for its tenant
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, FixedHostTenant.class,
                        WithProbeEngineEndpoint.class)
                .withPropertyValues(METADATA_OFF)
                .run(context -> {
                    assertThat(context).hasSingleBean(TenantResolver.class);
                    assertThat(context).doesNotHaveBean(PrincipalTenantResolver.class);
                    MvcResult engine = call(TestHosts.mockMvc(context), get("/api/bpm/rules/v1/probe")
                            .param("permission", "rules:read").with(user("thu").authorities(new SimpleGrantedAuthority("rules:read"))));
                    assertThat(engine.getResponse().getContentAsString()).isEqualTo("bank-c");
                });
    }

    @Test
    void RLE_019_me_lists_exactly_the_permissions_the_engine_endpoints_allow_for_each_user() {
        // Given the host maps PRICING_APPROVER to rules:approve
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithApproverMapping.class, WithProbeEngineEndpoint.class)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    EngineAccess access = context.getBean(EngineAccess.class);
                    JwtRequestPostProcessor thu = caller("thu", "bank-a", "ROLE_TELLER");

                    for (JwtRequestPostProcessor who : List.of(lan(), minh(), thu)) {
                        // When /me is called and the engine endpoint is tried with every rules permission
                        List<String> listed = strings(json(call(mvc, get("/api/bpm/me").with(who))).path("permissions"));

                        // Then /me lists exactly what the endpoint allows, in the engine's order
                        assertThat(listed).isEqualTo(enforced(mvc, who));
                    }
                    // And the users differ, so the check is not vacuous
                    assertThat(enforced(mvc, lan())).containsExactly("rules:read", "rules:author");
                    assertThat(enforced(mvc, minh())).containsExactly("rules:read", "rules:approve");
                    assertThat(enforced(mvc, thu)).isEmpty();

                    // And the seam refuses a permission no installed engine declares, so a typo in an engine fails loudly
                    EngineCaller lanCaller = new EngineCaller("lan", null, "bank-a", Set.of("rules:aprove"), List.of());
                    assertThatThrownBy(() -> access.hasPermission(lanCaller, "rules:aprove"))
                            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("rules:aprove");
                });
    }

    @Test
    void TR_UI_08_me_reports_from_a_host_engine_access_that_replaces_the_default() {
        // Given a host that declares its own seam
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, HostEngineAccess.class,
                        WithProbeEngineEndpoint.class)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    assertThat(context).doesNotHaveBean(DefaultEngineAccess.class);

                    // When lan, who holds rules:read and rules:author, calls /me and the engine endpoint
                    JsonNode me = json(call(mvc, get("/api/bpm/me").with(lan())));

                    // Then both answer from the host's seam
                    assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-z");
                    assertThat(strings(me.path("permissions"))).containsExactly("rules:read").isEqualTo(enforced(mvc, lan()));
                });
    }

    @Test
    void RLE_037_engine_path_tenant_mismatch_is_403_with_the_same_security_log_entry_as_me(CapturedOutput output) {
        for (boolean metadataOn : List.of(true, false)) {
            TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, WithProbeEngineEndpoint.class)
                    .withPropertyValues("horus.bpm.host-metadata.enabled=" + metadataOn)
                    .run(context -> {
                        MockMvc mvc = TestHosts.mockMvc(context);
                        List<String> paths = metadataOn
                                ? List.of("/api/bpm/rules/v1/probe?permission=rules:read", "/api/bpm/me")
                                : List.of("/api/bpm/rules/v1/probe?permission=rules:read");
                        for (String path : paths) {
                            // When lan of bank-a sends X-Tenant-Id: bank-b while switching is off
                            MvcResult result = call(mvc, get(path).with(lan()).header("X-Tenant-Id", "bank-b"));

                            // Then 403 TENANT_NOT_ALLOWED as a problem, and one security log entry of the same shape
                            assertThat(result.getResponse().getStatus()).isEqualTo(403);
                            assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
                            assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
                            JsonNode problem = json(result);
                            assertThat(problem.path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                            assertThat(problem.path("correlationId").asString())
                                    .isEqualTo(result.getResponse().getHeader("X-Correlation-Id"));
                            assertThat(result.getResponse().getContentAsString()).doesNotContain("bank-a", "lan");
                            assertThat(output).contains("tenant.switch.refused reason=switching-off user=lan "
                                    + "tokenTenant=bank-a requestedTenant=bank-b correlationId="
                                    + result.getResponse().getHeader("X-Correlation-Id") + " clientCorrelationId=-");
                        }
                    });
        }

        // And a host resolver that names another tenant than the token is refused on the engine path too
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, FixedHostTenant.class,
                        WithProbeEngineEndpoint.class)
                .run(context -> {
                    MvcResult result = call(TestHosts.mockMvc(context),
                            get("/api/bpm/rules/v1/probe").param("permission", "rules:read").with(lan()));
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(output).contains("tenant.switch.refused reason=differs-from-token-tenant user=lan "
                            + "tokenTenant=bank-a requestedTenant=bank-c");
                });
    }

    @Test
    void engine_endpoint_without_a_caller_gets_the_401_problem_with_the_configured_challenge() {
        // Given a host chain that lets every request through, and a non-bearer challenge
        TestHosts.runner(TestHosts.PermitAllSecurity.class)
                .withUserConfiguration(TestHosts.RulesWithoutMapping.class, WithProbeEngineEndpoint.class)
                .withPropertyValues("horus.bpm.security.challenge=Basic realm=\"loans\"")
                .run(context -> {
                    MvcResult result = call(TestHosts.mockMvc(context),
                            get("/api/bpm/rules/v1/probe").param("permission", "rules:read"));

                    // Then the seam refuses it, with the host's challenge, as /me would
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                    assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"loans\"");
                    assertThat(json(result).path("code").asString()).isEqualTo("UNAUTHENTICATED");

                    // And the entry point bean for the host's chain sends the same challenge
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    context.getBean(BpmProblemAuthenticationEntryPoint.class)
                            .commence(new MockHttpServletRequest("GET", "/api/bpm/rules/v1/probe"), response, null);
                    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"loans\"");
                });
    }

    @Test
    void outside_a_request_the_seam_resolves_the_caller_with_no_header_to_check() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            EngineAccess access = context.getBean(EngineAccess.class);
            var job = new TestingAuthenticationToken(new Object(), null, "rules:evaluate");
            job.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(job);
            try {
                // A principal without a tenant is refused outside a request too
                assertThatThrownBy(access::currentCaller).isInstanceOfSatisfying(EngineAccessDeniedException.class,
                        refused -> {
                            assertThat(refused.code()).isEqualTo("UNAUTHENTICATED");
                            assertThat(refused.reason()).isEqualTo(EngineAccessDeniedException.Reason.NO_TENANT);
                            assertThat(refused.challenge()).isEqualTo("Bearer error=\"invalid_token\"");
                            assertThat(refused.correlationId()).matches(HostMetadataEndpointsTest.ULID);
                        });

                // A host job running as lan with her token gets her tenant; there is no X-Tenant-Id to compare
                Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("lan").claim("tenant_id", "bank-a").build();
                SecurityContextHolder.getContext().setAuthentication(
                        new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("rules:evaluate"))));
                EngineCaller lan = access.currentCaller();
                assertThat(lan.userId()).isEqualTo("lan");
                assertThat(lan.tenant()).isEqualTo("bank-a");
                assertThat(access.hasPermission(lan, "rules:evaluate")).isTrue();
                assertThat(access.hasPermission(lan, "rules:approve")).isFalse();
            } finally {
                SecurityContextHolder.clearContext();
            }
            assertThatThrownBy(access::currentCaller).isInstanceOfSatisfying(EngineAccessDeniedException.class,
                    refused -> assertThat(refused.status()).isEqualTo(401));
        });
    }
}
