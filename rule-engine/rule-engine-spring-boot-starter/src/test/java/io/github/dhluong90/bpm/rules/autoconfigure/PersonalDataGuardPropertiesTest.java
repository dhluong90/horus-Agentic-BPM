package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.TenantModuleSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The guards against unmasked personal data bound from {@code horus.bpm.rules.decision-log.*} and
 * {@code masking.tenants.<tenant>.*} (PD-099): strict mode (RLE-050), the personal-data names (RLE-049) and the
 * tenant settings published in {@code /api/bpm/meta} (RLE-050, RLE-052).
 */
class PersonalDataGuardPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PolicyOnly.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PolicyOnly {
        @Bean
        PropertyMaskingPolicy policy(RuleEngineProperties properties, Environment environment) {
            return PropertyMaskingPolicy.from(properties,
                    environment.getProperty(PropertyMaskingPolicy.SHARED_KEY_PROPERTY));
        }
    }

    @Test
    void RLE_050_strict_mode_is_off_by_default_and_the_built_in_names_apply() {
        runner.run(context -> {
            PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
            assertThat(policy.requireExplicitMask("bank-a")).isFalse();
            assertThat(policy.personalDataNames("bank-a").match("nationalId")).contains("national id");
            assertThat(policy.configuredTenants()).isEmpty();
        });
    }

    @Test
    void RLE_050_strict_mode_is_set_per_host_and_overridden_per_tenant() {
        runner.withPropertyValues(
                        "horus.bpm.rules.decision-log.require-explicit-mask=true",
                        "horus.bpm.rules.masking.tenants.sandbox.require-explicit-mask=false")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.requireExplicitMask("bank-a")).isTrue();
                    assertThat(policy.requireExplicitMask("sandbox")).isFalse();
                    assertThat(policy.configuredTenants()).containsExactly("sandbox");
                });
    }

    @Test
    void RLE_049_the_host_extends_the_list_and_a_tenant_replaces_it_for_that_tenant_only() {
        runner.withPropertyValues(
                        "horus.bpm.rules.decision-log.additional-personal-data-names[0]=ma khach hang",
                        "horus.bpm.rules.masking.tenants.bank-b.personal-data-names[0]=customer ref",
                        "horus.bpm.rules.masking.tenants.bank-c.additional-personal-data-names[0]=cif")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    // bank-a: the built-in list plus the host's "ma khach hang"
                    assertThat(policy.personalDataNames("bank-a").match("nationalId")).contains("national id");
                    assertThat(policy.personalDataNames("bank-a").match("maKhachHang")).contains("ma khach hang");
                    // bank-b: its own list only
                    assertThat(policy.personalDataNames("bank-b").match("customerRef")).contains("customer ref");
                    assertThat(policy.personalDataNames("bank-b").match("nationalId")).isEmpty();
                    // bank-c: the host's list plus its own "cif"
                    assertThat(policy.personalDataNames("bank-c").match("cifNumber")).contains("cif");
                    assertThat(policy.personalDataNames("bank-c").match("maKhachHang")).contains("ma khach hang");
                    assertThat(policy.personalDataNames("bank-a").match("cifNumber")).isEmpty();
                });
    }

    @Test
    void RLE_049_the_host_replaces_the_built_in_list() {
        runner.withPropertyValues("horus.bpm.rules.decision-log.personal-data-names[0]=cccd")
                .run(context -> {
                    PropertyMaskingPolicy policy = context.getBean(PropertyMaskingPolicy.class);
                    assertThat(policy.personalDataNames("bank-a").names()).containsExactly("cccd");
                    assertThat(policy.personalDataNames("bank-a").match("email")).isEmpty();
                });
    }

    @Test
    void RLE_049_a_blank_name_stops_start_up_naming_the_setting() {
        runner.withPropertyValues("horus.bpm.rules.masking.tenants.bank-b.additional-personal-data-names[0]= ")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.rules.masking.tenants.bank-b.additional-personal-data-names"));
    }

    @Test
    void RLE_052_meta_publishes_the_masking_default_and_strict_mode_in_force_for_the_tenant() {
        runner.withPropertyValues(
                        "horus.bpm.rules.decision-log.mask-default=false",
                        "horus.bpm.rules.masking.tenants.bank-b.mask-default=true",
                        "horus.bpm.rules.masking.tenants.bank-b.require-explicit-mask=true")
                .run(context -> {
                    TenantModuleSettings settings =
                            RuleEngineModule.tenantSettings(context.getBean(PropertyMaskingPolicy.class));
                    assertThat(settings.module()).isEqualTo("rules");
                    assertThat(settings.settings("bank-a")).containsExactly(
                            java.util.Map.entry("decision-log.mask-default", false),
                            java.util.Map.entry("decision-log.require-explicit-mask", false));
                    assertThat(settings.settings("bank-b")).containsExactly(
                            java.util.Map.entry("decision-log.mask-default", true),
                            java.util.Map.entry("decision-log.require-explicit-mask", true));
                });
    }
}
