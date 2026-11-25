package io.github.dhluong90.bpm.host.autoconfigure;

import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Tenant switching (ADR-0014 decision 5, PD-037): off by default, and when on, an {@code X-Tenant-Id} header is
 * honoured only for a tenant in the verified token's allowed-tenants claim. Lan's token says
 * {@code tenant_id: bank-a, allowed_tenants: [bank-a, bank-a-fin]}; {@code bank-b} is another bank's tenant.
 * Both engines use this one seam, so these tests stand for {@code RLE-018} and {@code PRE-038}.
 */
@ExtendWith(OutputCaptureExtension.class)
class TenantSwitchingTest {

    private static final String SWITCHING = "horus.bpm.tenancy.switching.enabled=true";

    @Test
    void RLE_018_tenant_header_accepted_only_within_allowed_tenants(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    // When lan calls with no header, with bank-a-fin, and with bank-b
                    JsonNode home = json(call(mvc, get("/api/bpm/me").with(lan())));
                    MvcResult switched = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a-fin")
                            .header("X-Correlation-Id", "probe-1"));
                    MvcResult refused = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-b")
                            .header("X-Correlation-Id", "probe-2"));

                    // Then the first is bank-a, the second is bank-a-fin, the third is 403 TENANT_NOT_ALLOWED
                    assertThat(home.path("tenant").path("current").asString()).isEqualTo("bank-a");
                    assertThat(strings(home.path("tenant").path("allowed"))).containsExactly("bank-a", "bank-a-fin");
                    assertThat(json(switched).path("tenant").path("current").asString()).isEqualTo("bank-a-fin");
                    assertThat(strings(json(switched).path("tenant").path("allowed")))
                            .containsExactly("bank-a", "bank-a-fin");
                    assertThat(refused.getResponse().getStatus()).isEqualTo(403);
                    assertThat(json(refused).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                    assertThat(refused.getResponse().getContentAsString()).doesNotContain("bank-a", "lan", "permissions");

                    // And the refusal is in the security log with user, requested tenant and correlation id
                    assertThat(output).contains("tenant.switch.refused reason=not-in-allowed-list user=lan "
                            + "tokenTenant=bank-a requestedTenant=bank-b correlationId="
                            + refused.getResponse().getHeader("X-Correlation-Id") + " clientCorrelationId=probe-2");
                });
    }

    @Test
    void tenant_display_names_in_me_cover_allowed_tenants_only() {
        // Given names for bank-a, bank-a-fin and another bank's tenant bank-b, and lan allowed bank-a and bank-a-fin
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING,
                "horus.bpm.display-names.tenants.bank-a.en=Bank A",
                "horus.bpm.display-names.tenants.bank-a-fin.en=Bank A Finance",
                "horus.bpm.display-names.tenants.bank-b.en=Bank B").run(context -> {
                    // When lan reads /me
                    MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(lan()));

                    // Then both allowed tenants are named, bank-b is neither named nor mentioned
                    JsonNode names = json(me).path("tenant").path("names");
                    assertThat(names.path("bank-a").asString()).isEqualTo("Bank A");
                    assertThat(names.path("bank-a-fin").asString()).isEqualTo("Bank A Finance");
                    assertThat(me.getResponse().getContentAsString()).doesNotContain("bank-b", "Bank B");
                });
    }

    @Test
    void PRE_038_tenant_header_accepted_only_within_allowed_tenants(CapturedOutput output) {
        // Given a host with the process engine only, and minh of bank-a with the same allowed list
        TestHosts.runner().withUserConfiguration(ProcessesOnly.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    // When minh calls with no header, with bank-a-fin, and with bank-b
                    JsonNode none = json(call(mvc, get("/api/bpm/me").with(minh())));
                    JsonNode fin = json(call(mvc, get("/api/bpm/me").with(minh()).header("X-Tenant-Id", "bank-a-fin")));
                    MvcResult other = call(mvc, get("/api/bpm/me").with(minh()).header("X-Tenant-Id", "bank-b"));

                    // Then bank-a, bank-a-fin, and 403 with nothing returned and a log entry
                    assertThat(none.path("tenant").path("current").asString()).isEqualTo("bank-a");
                    assertThat(strings(none.path("permissions"))).containsExactly("processes:read");
                    assertThat(fin.path("tenant").path("current").asString()).isEqualTo("bank-a-fin");
                    assertThat(other.getResponse().getStatus()).isEqualTo(403);
                    assertThat(other.getResponse().getContentAsString()).doesNotContain("permissions", "minh");
                    assertThat(output).contains("tenant.switch.refused reason=not-in-allowed-list user=minh");
                });
    }

    /** The process engine's entry, as its starter contributes it. */
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class ProcessesOnly {
        @org.springframework.context.annotation.Bean
        io.github.dhluong90.bpm.host.spi.ModuleDescriptor processEngineModuleDescriptor() {
            return new io.github.dhluong90.bpm.host.spi.ModuleDescriptor("processes", "1.0.0", "v1", List.of(),
                    java.util.Map.of(), List.of("processes:read"), java.util.Map.of());
        }
    }

    @Test
    void TR_SEC_03_switching_is_off_by_default_so_even_a_listed_tenant_is_refused() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);

            // When lan, whose token lists bank-a-fin, asks for it on a host that did not turn switching on
            MvcResult refused = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a-fin"));
            JsonNode me = json(call(mvc, get("/api/bpm/me").with(lan())));
            JsonNode meta = json(call(mvc, get("/api/bpm/meta").with(lan())));

            // Then 403, /me offers only the signed-in tenant, and /meta says switching is off
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(strings(me.path("tenant").path("allowed"))).containsExactly("bank-a");
            assertThat(meta.path("tenancy").path("switching").asBoolean()).isFalse();
        });
    }

    @Test
    void TR_SEC_03_the_header_alone_never_grants_a_tenant_a_token_without_an_allowed_list_is_refused() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    // When binh, whose token has no allowed_tenants claim, asks for bank-a-fin
                    MvcResult refused = call(TestHosts.mockMvc(context), get("/api/bpm/me")
                            .with(HostMetadataEndpointsTest.caller("binh", "bank-b", "rules:read"))
                            .header("X-Tenant-Id", "bank-a-fin"));

                    // Then 403: only the token decides what is allowed
                    assertThat(refused.getResponse().getStatus()).isEqualTo(403);
                    assertThat(json(refused).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
                });
    }

    @Test
    void TR_SEC_03_malformed_blank_repeated_and_case_changed_headers_are_refused(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);
                    for (String value : List.of("", " ", "BANK-A-FIN", "bank-a-fin ", "bank-a-fin,bank-b", "../bank-b")) {
                        MvcResult result = call(mvc, get("/api/bpm/me").with(lan()).header("X-Tenant-Id", value));
                        assertThat(result.getResponse().getStatus()).as("header '%s'", value).isEqualTo(403);
                    }
                    // Two header lines, one of them listed
                    MvcResult repeated = call(mvc, get("/api/bpm/me").with(lan())
                            .header("X-Tenant-Id", "bank-a-fin", "bank-b"));
                    assertThat(repeated.getResponse().getStatus()).isEqualTo(403);
                    assertThat(output).contains("reason=repeated-header");
                });
    }

    @Test
    void TR_SEC_03_every_accepted_switch_is_written_to_the_security_log_once_per_request(CapturedOutput output) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> {
                    // When lan switches to bank-a-fin on one request, and names her own tenant on another
                    MvcResult switched = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(lan())
                            .header("X-Tenant-Id", "bank-a-fin").header("X-Correlation-Id", "probe-3"));
                    call(TestHosts.mockMvc(context), get("/api/bpm/me").with(lan()).header("X-Tenant-Id", "bank-a"));

                    // Then one entry names user, both tenants and the correlation id; naming bank-a is no switch
                    String log = output.getAll();
                    assertThat(log).contains("tenant.switch.accepted user=lan fromTenant=bank-a "
                            + "requestedTenant=bank-a-fin correlationId=" + switched.getResponse().getHeader("X-Correlation-Id")
                            + " clientCorrelationId=probe-3");
                    assertThat(log.split("tenant.switch.accepted user=", -1)).hasSize(2);
                });
    }

    @Test
    void TR_SEC_03_the_allowed_list_may_be_a_space_separated_claim_under_another_name() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(SWITCHING, "horus.bpm.tenancy.allowed-tenants-claim=entities").run(context -> {
                    JsonNode me = json(call(TestHosts.mockMvc(context), get("/api/bpm/me")
                            .with(jwt().jwt(token -> token.subject("lan").claim("tenant_id", "bank-a")
                                    .claim("entities", "bank-a-fin bank-a-ops").claim("allowed_tenants", List.of("bank-b")))
                                    .authorities(new SimpleGrantedAuthority("rules:read")))
                            .header("X-Tenant-Id", "bank-a-ops")));

                    // The configured claim is read, the default one is not
                    assertThat(me.path("tenant").path("current").asString()).isEqualTo("bank-a-ops");
                    assertThat(strings(me.path("tenant").path("allowed")))
                            .containsExactly("bank-a", "bank-a-fin", "bank-a-ops");
                });
    }

    @Test
    void TR_SEC_03_the_meta_endpoint_reports_switching_when_it_is_on() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(SWITCHING)
                .run(context -> assertThat(json(call(TestHosts.mockMvc(context), get("/api/bpm/meta").with(lan())))
                        .path("tenancy").path("switching").asBoolean()).isTrue());
    }

    @Test
    void TR_SEC_03_the_allowed_claim_must_differ_from_the_tenant_claim() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.tenancy.allowed-tenants-claim=tenant_id")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.tenancy.allowed-tenants-claim"));
    }

    private static JwtRequestPostProcessor lan() {
        return withAllowed("lan", "rules:read", "rules:author");
    }

    private static JwtRequestPostProcessor minh() {
        return withAllowed("minh", "processes:read");
    }

    private static JwtRequestPostProcessor withAllowed(String subject, String... authorities) {
        return jwt().jwt(token -> token.subject(subject).claim("tenant_id", "bank-a")
                        .claim("allowed_tenants", List.of("bank-a", "bank-a-fin")))
                .authorities(java.util.Arrays.stream(authorities)
                        .<org.springframework.security.core.GrantedAuthority>map(SimpleGrantedAuthority::new).toList());
    }
}
