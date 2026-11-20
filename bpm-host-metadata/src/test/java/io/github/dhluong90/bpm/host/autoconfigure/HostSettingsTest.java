package io.github.dhluong90.bpm.host.autoconfigure;

import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.strings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Host settings that adapt the metadata module to the host: one challenge for every {@code 401}, the identity
 * provider's claim names, and the page-size limit {@code /api/bpm/meta} reports. Callers work at bank-a.
 */
class HostSettingsTest {

    private static final String BASIC_LOANS = "horus.bpm.security.challenge=Basic realm=\"loans\"";

    @Test
    void TR_SEC_08_the_security_chain_and_the_endpoint_send_the_same_challenge() {
        // Given horus.bpm.security.challenge set to Basic realm="loans", and a chain that injects the module's beans
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues(BASIC_LOANS)
                .run(chainRefuses -> {
                    // When the host's chain refuses an anonymous call to /api/bpm/me
                    MvcResult fromChain = call(TestHosts.mockMvc(chainRefuses), get("/api/bpm/me"));

                    TestHosts.runner(TestHosts.PermitAllSecurity.class)
                            .withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                            .withPropertyValues(BASIC_LOANS)
                            .run(endpointRefuses -> {
                                // And the endpoint itself refuses it, behind a chain that lets it through
                                MvcResult fromEndpoint = call(TestHosts.mockMvc(endpointRefuses), get("/api/bpm/me"));

                                // Then both are 401 UNAUTHENTICATED with the one configured challenge
                                assertThat(fromChain.getResponse().getStatus()).isEqualTo(401);
                                assertThat(fromEndpoint.getResponse().getStatus()).isEqualTo(401);
                                assertThat(fromChain.getResponse().getHeader("WWW-Authenticate"))
                                        .isEqualTo("Basic realm=\"loans\"")
                                        .isEqualTo(fromEndpoint.getResponse().getHeader("WWW-Authenticate"));
                                assertThat(json(fromChain).path("code").asString()).isEqualTo("UNAUTHENTICATED");
                                assertThat(json(fromEndpoint).path("code").asString()).isEqualTo("UNAUTHENTICATED");
                            });
                });
    }

    @Test
    void TR_UI_08_claim_names_of_another_identity_provider_fill_groups_and_display_name() {
        // Given an ADFS-style host: groups in the Group claim URI, the display name in unique_name
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.security.groups-claim=http://schemas.xmlsoap.org/claims/Group",
                        "horus.bpm.security.display-name-claims=unique_name,upn")
                .run(context -> {
                    // When Hoa, a branch supervisor at bank-a, calls /api/bpm/me with such a token
                    MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(jwt()
                            .jwt(token -> token.subject("hoa").claim("tenant_id", "bank-a")
                                    .claim("name", "not read here")
                                    .claim("unique_name", "Hoa Tran")
                                    .claim("http://schemas.xmlsoap.org/claims/Group", List.of("HN-Branch", "Supervisors")))
                            .authorities(new SimpleGrantedAuthority("rules:read"))));

                    // Then the display name and the groups come from the configured claims
                    JsonNode body = json(me);
                    assertThat(me.getResponse().getStatus()).isEqualTo(200);
                    assertThat(body.path("user").path("displayName").asString()).isEqualTo("Hoa Tran");
                    assertThat(strings(body.path("groups"))).containsExactly("HN-Branch", "Supervisors");
                });
    }

    @Test
    void TR_UI_08_without_claim_settings_the_defaults_apply() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            HostMetadataProperties.Security security = context.getBean(HostMetadataProperties.class).security();
            assertThat(security.groupsClaim()).isEqualTo("groups");
            assertThat(security.displayNameClaims()).containsExactly("name", "preferred_username");

            // A Keycloak token without name falls back to preferred_username; groups come from groups
            MvcResult me = call(TestHosts.mockMvc(context), get("/api/bpm/me").with(jwt()
                    .jwt(token -> token.subject("tuan").claim("tenant_id", "bank-a")
                            .claim("preferred_username", "tuan.le").claim("groups", List.of("Tellers")))
                    .authorities(new SimpleGrantedAuthority("rules:evaluate"))));
            assertThat(json(me).path("user").path("displayName").asString()).isEqualTo("tuan.le");
            assertThat(strings(json(me).path("groups"))).containsExactly("Tellers");
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "horus.bpm.security.groups-claim=                      | horus.bpm.security.groups-claim",
        "horus.bpm.security.groups-claim=my groups             | horus.bpm.security.groups-claim",
        "horus.bpm.security.display-name-claims=               | horus.bpm.security.display-name-claims",
        "horus.bpm.security.display-name-claims=name,full name | horus.bpm.security.display-name-claims",
        "horus.bpm.limits.max-page-size=201                    | horus.bpm.limits.max-page-size",
        "horus.bpm.limits.max-page-size=0                      | horus.bpm.limits.max-page-size"
    })
    void TR_UI_08_an_empty_or_invalid_value_stops_the_start_up_naming_the_property(String setting, String property) {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(setting)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining(property));
    }

    @Test
    void TR_UI_08_meta_reports_the_page_size_limit_the_host_lowered() {
        // Given a host that lowers the largest page to 50
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.limits.max-page-size=50")
                .run(context -> {
                    MvcResult meta = call(TestHosts.mockMvc(context),
                            get("/api/bpm/meta").with(HostMetadataEndpointsTest.lan()));

                    // Then /api/bpm/meta reports 50
                    assertThat(json(meta).path("limits").path("maxPageSize").asInt()).isEqualTo(50);
                });
    }

    @Test
    void the_builder_gives_the_same_entry_as_the_constructor() {
        Map<String, List<String>> roles = Map.of("ROLE_PRICING_APPROVER", List.of("rules:approve"));

        ModuleDescriptor built = ModuleDescriptor.builder("rules", "0.2.0", "v1")
                .features(List.of("rules.decision-log"))
                .settings(Map.of("four-eyes", true))
                .permissions(TestHosts.RULES_PERMISSIONS)
                .rolePermissions(roles)
                .build();

        assertThat(built).isEqualTo(new ModuleDescriptor("rules", "0.2.0", "v1", List.of("rules.decision-log"),
                Map.of("four-eyes", true), TestHosts.RULES_PERMISSIONS, roles));
        assertThat(ModuleDescriptor.builder("processes", "0.2.0", "v1").build().permissions()).isEmpty();
    }
}
