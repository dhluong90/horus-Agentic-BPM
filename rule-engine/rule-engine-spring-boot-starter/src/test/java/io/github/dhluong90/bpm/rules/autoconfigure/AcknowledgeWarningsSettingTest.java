package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

/**
 * {@code horus.bpm.rules.lifecycle.acknowledge-warnings} (RLE-053, PD-104): the default, a host that removes the
 * personal-data code (allowed, logged at WARN, shown in {@code /api/bpm/meta}), and a code this release never raises.
 */
@ExtendWith(OutputCaptureExtension.class)
class AcknowledgeWarningsSettingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PropertiesOnly {
    }

    @Test
    void RLE_053_personal_data_warnings_need_an_acknowledgement_by_default(CapturedOutput output) {
        runner.run(context -> {
            RuleEngineProperties properties = context.getBean(RuleEngineProperties.class);
            assertThat(properties.toSettings().acknowledgeWarnings()).containsExactly("UNMASKED_PERSONAL_DATA");
            assertThat(RuleEngineServicesConfiguration.warnIfApprovalsSkipPersonalDataAcknowledgement(
                    properties.toSettings())).isFalse();
            assertThat(RuleEngineModule.descriptor(Map.of(), null, "single", "confirm",
                    properties.toSettings().acknowledgeWarnings())
                    .settings()).containsEntry("lifecycle.acknowledge-warnings", "UNMASKED_PERSONAL_DATA");
        });
        assertThat(output.getAll()).doesNotContain("acknowledge-warnings");
    }

    @Test
    void RLE_053_removing_the_personal_data_code_is_allowed_logged_at_warn_and_shown_in_meta(CapturedOutput output) {
        runner.withPropertyValues("horus.bpm.rules.lifecycle.acknowledge-warnings=").run(context -> {
            RuleEngineProperties properties = context.getBean(RuleEngineProperties.class);
            List<String> codes = properties.toSettings().acknowledgeWarnings();
            assertThat(codes).isEmpty();
            assertThat(RuleEngineServicesConfiguration.warnIfApprovalsSkipPersonalDataAcknowledgement(
                    properties.toSettings())).isTrue();
            assertThat(RuleEngineModule.descriptor(Map.of(), null, "single", "confirm", codes).settings())
                    .containsEntry("lifecycle.acknowledge-warnings", "");
        });
        assertThat(output.getAll()).contains("WARN")
                .contains("horus.bpm.rules.lifecycle.acknowledge-warnings is []")
                .contains("does not list UNMASKED_PERSONAL_DATA");
    }

    @Test
    void RLE_053_a_code_this_release_never_raises_stops_start_up_naming_the_setting() {
        runner.withPropertyValues("horus.bpm.rules.lifecycle.acknowledge-warnings=UNMASKED_PERSONAL_DATA,OTHER_CODE")
                .run(context -> {
                    RuleEngineProperties properties = context.getBean(RuleEngineProperties.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(properties::toSettings)
                            .hasMessageContaining("horus.bpm.rules.lifecycle.acknowledge-warnings")
                            .hasMessageContaining("OTHER_CODE");
                });
    }
}
