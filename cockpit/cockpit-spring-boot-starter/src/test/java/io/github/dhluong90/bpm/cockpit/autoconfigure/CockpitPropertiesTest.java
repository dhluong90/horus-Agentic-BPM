package io.github.dhluong90.bpm.cockpit.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class CockpitPropertiesTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CockpitAutoConfiguration.class));

    @Test
    void CKP_001_defaults_serve_the_cockpit_at_bpm_cockpit_for_host_self_with_host_session() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            CockpitProperties properties = context.getBean(CockpitProperties.class);
            assertThat(properties.path()).isEqualTo("/bpm/cockpit");
            assertThat(properties.authMode()).isEqualTo(CockpitProperties.AuthMode.HOST_SESSION);
            assertThat(new String(context.getBean(CockpitHostRegistry.class).json(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("{\"schemaVersion\":1,\"auth\":{\"mode\":\"host-session\"},"
                            + "\"hosts\":[{\"id\":\"self\",\"label\":\"self\",\"baseUrl\":\"\"}],\"ui\":{\"defaultLocale\":\"en\"}}");
        });
    }

    @Test
    void CKP_001_path_with_a_trailing_slash_stops_the_host_at_start_up() {
        runner.withPropertyValues("horus.bpm.cockpit.path=/ops/").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("no trailing slash"));
    }

    @Test
    void CKP_001_host_id_that_is_not_kebab_case_stops_the_host_at_start_up() {
        runner.withPropertyValues("horus.bpm.host-id=Loans Service").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("kebab-case"));
    }

    @Test
    void CKP_001_oidc_auth_mode_is_refused_until_standalone_sign_in_exists() {
        runner.withPropertyValues("horus.bpm.cockpit.auth-mode=oidc").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void CKP_001_cockpit_can_be_turned_off() {
        runner.withPropertyValues("horus.bpm.cockpit.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(CockpitHostRegistry.class));
    }

    @Test
    void CKP_001_labels_are_escaped_as_json_strings() {
        assertThat(CockpitHostRegistry.quote("Loans \"A\" <b>\n")).isEqualTo("\"Loans \\\"A\\\" \\u003cb>\\n\"");
    }
}
