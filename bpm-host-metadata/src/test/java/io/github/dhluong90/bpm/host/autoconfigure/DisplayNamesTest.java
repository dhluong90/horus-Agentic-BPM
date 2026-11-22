package io.github.dhluong90.bpm.host.autoconfigure;

import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static io.github.dhluong90.bpm.host.autoconfigure.HostMetadataEndpointsTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.host.spi.DisplayNames;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Readable names of roles, groups and users (#442, RLE-196, PRE-206): {@code GET /api/bpm/display-names} in the
 * caller's language from {@code horus.bpm.display-names}, a host's own {@link DisplayNames} bean replacing it, and
 * identifiers without a name left out so the screen shows the identifier.
 */
class DisplayNamesTest {

    private static final String[] NAMES = {
        "horus.bpm.display-names.roles.CARD_OFFICER.en=Card officer",
        "horus.bpm.display-names.roles.CARD_OFFICER.vi=Cán bộ thẻ",
        "horus.bpm.display-names.roles.COMPLIANCE_REVIEWER.en=Compliance reviewer",
        "horus.bpm.display-names.groups.hn-branch.vi=Chi nhánh Hà Nội",
        "horus.bpm.display-names.users.lan.vi=Trần Thị Lan"
    };

    @Test
    void PRE_206_names_come_back_in_the_callers_language_with_the_default_language_as_fallback() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).withPropertyValues(NAMES)
                .run(context -> {
                    MockMvc mvc = TestHosts.mockMvc(context);

                    // When a Vietnamese screen asks for two roles, a group, a user and an unknown role
                    MvcResult result = call(mvc, get("/api/bpm/display-names").with(minh())
                            .header("Accept-Language", "vi-VN,vi;q=0.9")
                            .param("roles", "CARD_OFFICER,COMPLIANCE_REVIEWER,UNKNOWN_ROLE")
                            .param("groups", "hn-branch").param("users", "lan", "an"));
                    JsonNode body = json(result);

                    // Then Vietnamese names where there are any, English otherwise, and unknown ids are left out
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    assertThat(body.path("language").asString()).isEqualTo("vi");
                    assertThat(body.path("roles").path("CARD_OFFICER").asString()).isEqualTo("Cán bộ thẻ");
                    assertThat(body.path("roles").path("COMPLIANCE_REVIEWER").asString()).isEqualTo("Compliance reviewer");
                    assertThat(body.path("roles").has("UNKNOWN_ROLE")).isFalse();
                    assertThat(body.path("groups").path("hn-branch").asString()).isEqualTo("Chi nhánh Hà Nội");
                    assertThat(body.path("users").path("lan").asString()).isEqualTo("Trần Thị Lan");
                    assertThat(body.path("users").has("an")).isFalse();
                    assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("private, no-cache");
                    assertThat(result.getResponse().getHeaders("Vary")).contains("Accept-Language");

                    // And an English screen gets the English name
                    JsonNode english = json(call(mvc, get("/api/bpm/display-names").with(minh())
                            .header("Accept-Language", "en").param("roles", "CARD_OFFICER")));
                    assertThat(english.path("roles").path("CARD_OFFICER").asString()).isEqualTo("Card officer");
                });
    }

    @Test
    void RLE_196_a_host_directory_replaces_the_default_and_receives_the_callers_tenant_and_language() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class, BankDirectory.class)
                .withPropertyValues(NAMES).run(context -> {
                    JsonNode body = json(call(TestHosts.mockMvc(context), get("/api/bpm/display-names").with(minh())
                            .header("Accept-Language", "vi").param("users", "lan").param("roles", "CARD_OFFICER")));

                    // The host's bean answers; the properties are not read, and roles it does not know stay ids
                    assertThat(body.path("users").path("lan").asString()).isEqualTo("Lan (bank-a, vi)");
                    assertThat(body.path("roles").has("CARD_OFFICER")).isFalse();
                });
    }

    @Test
    void PRE_206_too_many_or_malformed_identifiers_are_400_and_an_anonymous_caller_is_401() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class).run(context -> {
            MockMvc mvc = TestHosts.mockMvc(context);
            String many = String.join(",", java.util.stream.IntStream.rangeClosed(1, 101).mapToObj(i -> "r" + i)
                    .toList());

            MvcResult tooMany = call(mvc, get("/api/bpm/display-names").with(minh()).param("roles", many));
            MvcResult malformed = call(mvc, get("/api/bpm/display-names").with(minh()).param("users", "<script>"));
            MvcResult anonymous = call(mvc, get("/api/bpm/display-names").param("roles", "CARD_OFFICER"));

            assertThat(tooMany.getResponse().getStatus()).isEqualTo(400);
            assertThat(json(tooMany).path("code").asString()).isEqualTo("INPUT_INVALID");
            assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
            assertThat(malformed.getResponse().getContentAsString()).doesNotContain("<script>");
            assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
        });
    }

    @Test
    void PRE_206_a_default_language_outside_two_or_three_letters_stops_start_up() {
        TestHosts.runner().withUserConfiguration(TestHosts.RulesWithoutMapping.class)
                .withPropertyValues("horus.bpm.display-names.default-language=English")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.display-names.default-language"));
    }

    private static JwtRequestPostProcessor minh() {
        return jwt().jwt(token -> token.subject("minh").claim("tenant_id", "bank-a"))
                .authorities(new SimpleGrantedAuthority("rules:read"));
    }

    @Configuration(proxyBeanMethods = false)
    static class BankDirectory {
        @Bean
        DisplayNames bankDirectoryNames() {
            return new DisplayNames() {
                @Override
                public Optional<String> user(String tenantId, String userId, Locale locale) {
                    return Optional.of("Lan (" + tenantId + ", " + locale.getLanguage() + ")");
                }
            };
        }
    }
}
