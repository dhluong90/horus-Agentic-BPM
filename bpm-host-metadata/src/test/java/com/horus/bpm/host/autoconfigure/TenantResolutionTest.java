package com.horus.bpm.host.autoconfigure;

import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.caller;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.lan;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.horus.bpm.host.spi.TenantResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Where the tenant of a request comes from, and what is refused (TR-SEC-03, ADR-0014). Lan works at
 * bank-a and Binh at bank-b.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantResolutionTest {

    @Test
    void TR_SEC_03_tenant_comes_from_the_token_and_a_query_parameter_is_ignored() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            // When binh of bank-b asks for bank-a through a query parameter
            JsonNode me = json(call(TestHosts.mockMvc(context),
                    get("/api/bpm/me").param("tenantId", "bank-a").with(caller("binh", "bank-b", "rules:read"))));

            // Then the answer is bank-b's, and bank-a appears nowhere
            assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-b");
            assertThat(strings(me.path("tenant").path("allowed"))).containsExactly("bank-b");
            assertThat(me.toString()).doesNotContain("bank-a");
        });
    }

    @Test
    void TR_SEC_03_tenant_header_naming_another_tenant_is_403_and_logged_with_the_token_tenant(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            for (String path : List.of("/api/bpm/me", "/api/bpm/meta")) {
                // When lan of bank-a sends X-Tenant-Id: bank-b while switching is off
                MvcResult result = call(mvc, get(path).with(lan()).header("X-Tenant-Id", "bank-b")
                        .header("X-Correlation-Id", "probe 1"));

                // Then 403 TENANT_NOT_ALLOWED with no caller data, and a security log entry that names the
                // token tenant and keeps the caller's correlation id apart from the host's
                assertThat(result.getResponse().getStatus()).isEqualTo(403);
                assertThat(json(result).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                assertThat(result.getResponse().getContentAsString()).doesNotContain("bank-a", "lan", "permissions");
                assertThat(output).contains("tenant.switch.refused reason=switching-off user=lan tokenTenant=bank-a "
                        + "requestedTenant=bank-b correlationId=" + result.getResponse().getHeader("X-Correlation-Id")
                        + " clientCorrelationId=probe%201");
            }

            // And the header naming the caller's own tenant is accepted
            assertThat(call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a"))
                    .getResponse().getStatus()).isEqualTo(200);
        });
    }

    @Test
    void TR_SEC_03_an_email_principal_is_logged_percent_encoded_not_dropped(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            call(TestHosts.mockMvc(context), get("/api/bpm/me")
                    .with(caller("lan@bank-a.vn", "bank-a", "rules:read")).header("X-Tenant-Id", "bank-b user=admin"));

            // The firewall already refuses CR and LF in headers; spaces and equals signs are escaped too,
            // so a value cannot forge another field of the entry
            assertThat(output).contains("user=lan%40bank-a.vn tokenTenant=bank-a requestedTenant=bank-b%20user%3Dadmin");
        });
    }

    @Test
    void TR_SEC_03_token_without_a_tenant_claim_is_401_invalid_token_with_a_fixed_detail(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MvcResult result = call(TestHosts.mockMvc(context), get("/api/bpm/me")
                    .with(jwt().jwt(token -> token.subject("lan")).authorities(new SimpleGrantedAuthority("rules:read"))));

            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
            assertThat(json(result).path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(json(result).path("detail").asString())
                    .isEqualTo("No tenant could be resolved for the caller. Signing in again with the same account does not help.");
            assertThat(output).contains("tenant.unresolved user=lan source=the%20tenant_id%20attribute%20of%20the%20principal");
        });
    }

    @Test
    void RLE_037_tenant_from_principal_session_or_token_never_from_browser() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            // Host A: a server-side session principal from an OIDC login, with the attribute tenant_id = bank-a
            var session = authentication(new UsernamePasswordAuthenticationToken(
                    new DefaultOAuth2AuthenticatedPrincipal("lan", Map.of("sub", "lan", "tenant_id", "bank-a"),
                            List.of(new SimpleGrantedAuthority("rules:read"))),
                    null, List.of(new SimpleGrantedAuthority("rules:read"))));
            // Host B: a validated bearer token with the claim tenant_id = bank-a
            var token = caller("lan", "bank-a", "rules:read");

            for (var principal : List.of(session, token)) {
                // When the browser names bank-b in a query parameter and in an extra header
                JsonNode me = json(call(mvc, get("/api/bpm/me").with(principal)
                        .param("tenantId", "bank-b").header("X-Forwarded-Tenant", "bank-b")));

                // Then the request is served for bank-a
                assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-a");
                assertThat(me.toString()).doesNotContain("bank-b");
            }

            // Case D: a session principal without the attribute gets 401 and no data, whatever headers it sends
            MvcResult noTenant = call(mvc, get("/api/bpm/me")
                    .with(user("thu").authorities(new SimpleGrantedAuthority("rules:read")))
                    .header("X-Tenant-Id", "bank-a").header("X-Forwarded-Tenant", "bank-a"));
            assertThat(noTenant.getResponse().getStatus()).isEqualTo(401);
            assertThat(noTenant.getResponse().getContentAsString()).doesNotContain("permissions", "bank-a");

            // And the starter registers only the principal resolver, never one that reads a header
            assertThat(context.getBean(TenantResolver.class)).isInstanceOf(PrincipalTenantResolver.class);
        });

        // Host C: its own resolver replaces the default, which is then not registered
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, FixedHostTenant.class)
                .run(context -> {
                    JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me")
                            .with(user("thu").authorities(new SimpleGrantedAuthority("rules:read")))));

                    assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-c");
                    assertThat(context).doesNotHaveBean(PrincipalTenantResolver.class);
                });
    }

    /** Host C of RLE-037: a host resolver that returns bank-c for every caller. */
    @Configuration(proxyBeanMethods = false)
    static class FixedHostTenant {
        @Bean
        TenantResolver hostTenantResolver() {
            return caller -> Optional.of("bank-c");
        }
    }

    @Test
    void TR_SEC_03_dev_tenant_serves_principals_without_a_tenant_on_the_dev_profile_only() {
        var runner = TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.dev-tenant=bank-a");

        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            JsonNode devUser = json(call(mvc, get("/api/bpm/me")
                    .with(user("lan").authorities(new SimpleGrantedAuthority("rules:read")))));
            JsonNode bankBToken = json(call(mvc, get("/api/bpm/me").with(caller("binh", "bank-b", "rules:read"))));

            assertThat(devUser.path("tenant").path("current").asString()).isEqualTo("bank-a");
            // A principal that carries a tenant keeps it
            assertThat(bankBToken.path("tenant").path("current").asString()).isEqualTo("bank-b");
        });
        // Matched the way @Profile matches, default profiles included
        runner.withPropertyValues("spring.profiles.default=dev").run(context -> assertThat(context).hasNotFailed());
        runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("horus.bpm.tenancy.dev-tenant fixes the tenant for local work and is accepted on "
                        + "the dev profile only"));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void TR_SEC_03_a_tenant_outside_the_allowed_format_is_refused() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MvcResult spaces = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(caller("lan", "bank a", "rules:read")));
            MvcResult tooLong = call(TestHosts.mockMvc(context),
                    get("/api/bpm/me").with(caller("lan", "a".repeat(8192), "rules:read")));

            assertThat(spaces.getResponse().getStatus()).isEqualTo(403);
            assertThat(tooLong.getResponse().getStatus()).isEqualTo(403);
            assertThat(tooLong.getResponse().getContentAsString()).doesNotContain("aaaa");
        });
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.dev-tenant=bank a", "spring.profiles.active=dev")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.tenancy.dev-tenant must be 1 to 64"));
    }

    /** Host-session mode: the tenant is read from the signed-in principal, as a form, SAML or LDAP host would. */
    @Configuration(proxyBeanMethods = false)
    static class SessionTenant {
        @Bean
        TenantResolver sessionTenantResolver() {
            // The host's directory says lan works at bank-a; it reads its own principal type when it has one
            return caller -> {
                String user = caller.principal() instanceof UserDetails details ? details.getUsername() : caller.principalName();
                return user.equals("lan") ? Optional.of("bank-a") : Optional.empty();
            };
        }
    }

    @Test
    void TR_SEC_03_a_host_tenant_resolver_reads_its_own_principal_and_cannot_override_a_token_tenant() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, SessionTenant.class)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    JsonNode lanSession = json(call(mvc, get("/api/bpm/me")
                            .with(user("lan").authorities(new SimpleGrantedAuthority("rules:read")))));
                    MvcResult unknown = call(mvc, get("/api/bpm/me")
                            .with(user("thu").authorities(new SimpleGrantedAuthority("rules:read"))));
                    MvcResult bankBToken = call(mvc, get("/api/bpm/me").with(caller("lan", "bank-b", "rules:read")));

                    assertThat(lanSession.path("tenant").path("current").asString()).isEqualTo("bank-a");
                    assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
                    assertThat(bankBToken.getResponse().getStatus()).isEqualTo(403);
                });
    }
}
