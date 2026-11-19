package com.horus.bpm.host.autoconfigure;

import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.caller;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.lan;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.horus.bpm.host.spi.TenantResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Where the tenant of a request comes from, and what is refused (TR-SEC-03, ADR-0014). Lan works at
 * bank-a and Binh at bank-b.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantResolutionTest {

    private static final String[] GATEWAY = {
        "horus.bpm.tenancy.resolver=header:X-Tenant-Id", "horus.bpm.tenancy.trusted-gateway=true"};

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
            assertThat(output).contains("tenant.unresolved user=lan source=the%20tenant_id%20claim");
        });
    }

    @Test
    void TR_SEC_03_gateway_header_differing_from_the_token_tenant_is_403_and_logged(CapturedOutput output) {
        // Given gateway mode, and a bank-a token that reaches the host with X-Tenant-Id: bank-b
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(GATEWAY)
                .run(context -> {
                    MvcResult result = call(TestHosts.mockMvc(context),
                            get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-b"));

                    // Then the token tenant wins: 403 and a security log entry
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(json(result).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                    assertThat(output).contains("tenant.switch.refused reason=differs-from-token-tenant user=lan "
                            + "tokenTenant=bank-a requestedTenant=bank-b");
                });
    }

    @Test
    void TR_SEC_03_gateway_header_alone_is_accepted_only_for_a_principal_without_a_tenant_claim() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(GATEWAY)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    var gatewayUser = user("lan").authorities(new SimpleGrantedAuthority("rules:read"));

                    JsonNode me = json(call(mvc, get("/api/bpm/me").with(gatewayUser).header("X-Tenant-Id", "bank-a")));
                    MvcResult sameAsToken = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a"));
                    MvcResult missing = call(mvc, get("/api/bpm/me").with(gatewayUser));

                    assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-a");
                    assertThat(me.path("user").has("displayName")).isFalse();
                    assertThat(sameAsToken.getResponse().getStatus()).isEqualTo(200);
                    assertThat(missing.getResponse().getStatus()).isEqualTo(401);
                });
    }

    @Test
    void TR_SEC_03_gateway_mode_outside_dev_does_not_start_without_the_trusted_gateway_setting() {
        var runner = TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.resolver=header:X-Tenant-Id");

        runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("set horus.bpm.tenancy.trusted-gateway=true only when a gateway"));
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("horus.bpm.tenancy.trusted-gateway=true")
                .run(context -> assertThat(context).hasNotFailed());
        // The dev check matches profiles the way @Profile does, default profiles included
        runner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("spring.profiles.default=dev")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void TR_SEC_03_a_tenant_outside_the_allowed_format_is_refused() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(GATEWAY)
                .run(context -> {
                    var gatewayUser = user("lan").authorities(new SimpleGrantedAuthority("rules:read"));
                    MvcResult tooLong = call(TestHosts.mockMvc(context),
                            get("/api/bpm/me").with(gatewayUser).header("X-Tenant-Id", "a".repeat(8192)));
                    MvcResult spaces = call(TestHosts.mockMvc(context),
                            get("/api/bpm/me").with(gatewayUser).header("X-Tenant-Id", "bank a"));

                    assertThat(tooLong.getResponse().getStatus()).isEqualTo(403);
                    assertThat(tooLong.getResponse().getContentAsString()).doesNotContain("aaaa");
                    assertThat(spaces.getResponse().getStatus()).isEqualTo(403);
                });
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
    void TR_SEC_03_a_host_tenant_resolver_replaces_the_built_in_modes_and_cannot_override_a_token_tenant() {
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
