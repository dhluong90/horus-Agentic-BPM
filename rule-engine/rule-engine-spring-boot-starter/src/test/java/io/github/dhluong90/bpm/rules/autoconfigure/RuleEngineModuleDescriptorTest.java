package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The rules entry the starter contributes to {@code /api/bpm/meta} and {@code /api/bpm/me} (RLE-019), and
 * the role mapping property {@code horus.bpm.rules.security.role-permissions}.
 */
@ExtendWith(OutputCaptureExtension.class)
class RuleEngineModuleDescriptorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineAutoConfiguration.class))
            .withUserConfiguration(RuleEngineAutoConfigurationTest.HostWithEngineStandIns.class);

    @Test
    void RLE_019_rules_entry_has_api_v1_the_build_version_and_the_seven_permissions() {
        runner.run(context -> {
            ModuleDescriptor rules = context.getBean(ModuleDescriptor.class);

            assertThat(rules.module()).isEqualTo("rules");
            assertThat(rules.apiVersion()).isEqualTo("v1");
            assertThat(rules.engineVersion()).isEqualTo(System.getProperty("rule-engine.version"))
                    .doesNotContain("${").matches("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$");
            assertThat(rules.features()).isEmpty();
            assertThat(rules.permissions()).containsExactly(
                    "rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read",
                    "rules:masked:search", "rules:pin");
            assertThat(rules.rolePermissions()).isEmpty();
        });
    }

    @Test
    void RLE_019_every_permission_fits_the_published_me_pattern_so_older_cockpits_accept_it() throws Exception {
        // The pattern of cockpit/api/schema/bpm-me.v1.schema.json, unchanged since 0.1.0: a cockpit of that release
        // rejects the whole /api/bpm/me response when one permission does not match.
        String schema = java.nio.file.Files.readString(
                java.nio.file.Path.of("../../cockpit/api/schema/bpm-me.v1.schema.json"));
        assertThat(schema).contains("\"^(rules|processes)(:[a-z]+){1,2}$\"");
        assertThat(RuleEngineModule.PERMISSIONS).contains("rules:masked:search")
                .allMatch(permission -> permission.matches("^(rules|processes)(:[a-z]+){1,2}$"));
    }

    @Test
    void PD_094_cockpit_drafts_setting_is_published_false_by_default_and_true_when_the_host_sets_it() {
        runner.run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                .containsEntry("authoring.cockpit-drafts", false)
                .containsEntry("lifecycle.acknowledge-warnings", "UNMASKED_PERSONAL_DATA"));
        runner.withPropertyValues("horus.bpm.rules.authoring.cockpit-drafts=true")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("authoring.cockpit-drafts", true));
        runner.withPropertyValues("horus.bpm.rules.authoring.cockpit-drafts=false")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("authoring.cockpit-drafts", false)
                .containsEntry("lifecycle.acknowledge-warnings", "UNMASKED_PERSONAL_DATA"));
    }

    @Test
    void RLE_026_the_shared_last_version_setting_is_published_and_four_eyes_is_refused_until_it_ships() {
        // PD-101: one host property for both engines, shown in the rules settings
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=refuse")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-last-version", "refuse"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=four-eyes")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.retire-last-version")
                        .hasMessageContaining("four-eyes is not built in this release"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=sometimes")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.retire-last-version")
                        .hasMessageContaining("must be confirm (default)"));
    }

    @Test
    void RLE_026_the_retirement_mode_is_published_and_four_eyes_is_refused_until_it_ships() {
        // PD-080: the mode in force is always in /api/bpm/meta, after the draft-editing setting
        runner.run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                .containsExactly(Map.entry("authoring.cockpit-drafts", false),
                        Map.entry("lifecycle.approver-must-differ-from", "makers"),
                        Map.entry("lifecycle.retire-approval", "single"),
                        Map.entry("lifecycle.retire-last-version", "confirm"),
                        Map.entry("lifecycle.acknowledge-warnings", "UNMASKED_PERSONAL_DATA")));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-approval=single")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-approval", "single"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-approval=four-eyes")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.retire-approval")
                        .hasMessageContaining("four-eyes arrives in M3"));
    }

    @Test
    void RLE_019_role_mapping_property_keeps_host_role_names_as_written() {
        runner.withPropertyValues(
                        "horus.bpm.rules.security.role-permissions.ROLE_PRICING_APPROVER=rules:approve",
                        "horus.bpm.rules.security.role-permissions.ROLE_PRICING_ANALYST[0]=rules:read",
                        "horus.bpm.rules.security.role-permissions.ROLE_PRICING_ANALYST[1]=rules:author")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).rolePermissions())
                        .containsExactlyInAnyOrderEntriesOf(Map.of(
                                "ROLE_PRICING_APPROVER", List.of("rules:approve"),
                                "ROLE_PRICING_ANALYST", List.of("rules:read", "rules:author"))));
    }

    @Test
    void RLE_019_role_mapping_to_an_unknown_permission_stops_the_start_up() {
        runner.withPropertyValues("horus.bpm.rules.security.role-permissions.ROLE_PRICING_APPROVER=rules:aprove")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("Role mapping ROLE_PRICING_APPROVER grants unknown permission 'rules:aprove'"));
    }

    @Test
    void no_rules_entry_when_the_engine_is_disabled() {
        runner.withPropertyValues("horus.bpm.rules.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ModuleDescriptor.class));
    }

    @Test
    void RLE_009_meta_reports_makers_by_default_and_whatever_the_host_sets(CapturedOutput output) {
        runner.run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                .containsEntry("lifecycle.approver-must-differ-from", "makers"));
        assertThat(output).doesNotContain("the Rule Engine keeps makers");
        runner.withPropertyValues("horus.bpm.lifecycle.approver-must-differ-from=requester")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.approver-must-differ-from", "makers"));
        assertThat(output.toString().split("the Rule Engine keeps makers", -1)).hasSize(2);
    }

    @Test
    void RLE_009_an_unknown_approver_setting_stops_start_up_naming_the_property() {
        runner.withPropertyValues("horus.bpm.lifecycle.approver-must-differ-from=nobody")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.approver-must-differ-from")
                        .hasMessageContaining("makers").hasMessageContaining("requester"));
    }

    @Configuration(proxyBeanMethods = false)
    static class HostDescriptor {
        @Bean
        ModuleDescriptor ruleEngineModuleDescriptor() {
            return RuleEngineModule.descriptor(Map.of(), false, "single", "confirm");
        }
    }

    @Test
    void RLE_009_a_host_that_replaces_the_descriptor_still_gets_the_failure_and_the_warning(CapturedOutput output) {
        runner.withUserConfiguration(HostDescriptor.class)
                .withPropertyValues("horus.bpm.lifecycle.approver-must-differ-from=nobody")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.approver-must-differ-from"));
        runner.withUserConfiguration(HostDescriptor.class)
                .withPropertyValues("horus.bpm.lifecycle.approver-must-differ-from=requester")
                .run(context -> assertThat(context).hasNotFailed());
        assertThat(output.toString().split("the Rule Engine keeps makers", -1)).hasSize(2);
    }
}
