package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** RLE-084, PD-139: approval is bound to the reviewed content by default; turning that off is dev-only. */
class ApprovalContentHashSettingTest {

    private static final EngineSettings OFF = EngineSettings.defaults().withRequireContentHash(false);

    private static MockEnvironment profiles(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void RLE_084_the_hash_is_required_by_default_and_published_in_meta() {
        assertThat(EngineSettings.defaults().requireContentHash()).isTrue();
        assertThat(RuleEngineModule.descriptor(Map.of(), null, "single", "confirm").settings())
                .containsEntry("approval.require-content-hash", true);
        assertThat(RuleEngineServicesConfiguration.warnIfApprovalsAreNotBoundToContent(
                EngineSettings.defaults())).isFalse();
        assertThatCode(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(
                EngineSettings.defaults(), profiles("prod"))).doesNotThrowAnyException();
    }

    @Test
    void RLE_084_turning_the_requirement_off_is_refused_outside_the_dev_profile() {
        assertThatThrownBy(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(OFF, profiles()))
                .as("no profile").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.approval.require-content-hash=false")
                .hasMessageContaining("PD-139");
        assertThatThrownBy(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(OFF,
                profiles("prod"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(OFF,
                profiles("dev", "prod"))).as("dev beside prod").isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(OFF,
                profiles("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> RuleEngineServicesConfiguration.requireDevProfileForUnboundApprovals(OFF,
                profiles("dev", "test"))).doesNotThrowAnyException();
        assertThat(RuleEngineServicesConfiguration.warnIfApprovalsAreNotBoundToContent(OFF)).isTrue();
    }

    @Test
    void RLE_084_the_property_binds_defaults_to_true_and_false_reaches_the_settings() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner runner =
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withUserConfiguration(PropertiesOnly.class);
        runner.run(context -> assertThat(context.getBean(RuleEngineProperties.class).toSettings()
                .requireContentHash()).isTrue());
        runner.withPropertyValues("horus.bpm.rules.approval.require-content-hash=false")
                .run(context -> assertThat(context.getBean(RuleEngineProperties.class).toSettings()
                        .requireContentHash()).isFalse());
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(RuleEngineProperties.class)
    static class PropertiesOnly {
    }
}
