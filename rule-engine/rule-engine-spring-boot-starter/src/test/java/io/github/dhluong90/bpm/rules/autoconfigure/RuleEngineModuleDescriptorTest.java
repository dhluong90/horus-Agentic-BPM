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
            .withUserConfiguration(RuleEngineAutoConfigurationTest.HostWithEngineStandIns.class)
            // Outside the dev profile a host sets how long decisions are kept (PD-050).
            .withPropertyValues(RuleEngineAutoConfigurationTest.RETENTION);

    @Test
    void RLE_019_rules_entry_has_api_v1_the_build_version_and_the_seven_permissions() {
        runner.run(context -> {
            ModuleDescriptor rules = context.getBean(ModuleDescriptor.class);

            assertThat(rules.module()).isEqualTo("rules");
            assertThat(rules.apiVersion()).isEqualTo("v1");
            assertThat(rules.engineVersion()).isEqualTo(System.getProperty("rule-engine.version"))
                    .doesNotContain("${").matches("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$");
            assertThat(rules.features()).containsExactly("rules.history-readings", "rules.simulation-reports",
                    "rules.templates", "rules.condition-builder");
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
    void RLE_026_the_shared_last_version_setting_is_published_with_every_value() {
        // PD-101: one host property for both engines, shown in the rules settings
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=refuse")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-last-version", "refuse"));
        // RLE-038: four-eyes for the last version in effect is built and published as set
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=four-eyes")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-last-version", "four-eyes"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-last-version=sometimes")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.retire-last-version")
                        .hasMessageContaining("must be confirm (default)"));
    }

    @Test
    void RLE_038_the_retirement_mode_is_published_single_by_default_or_four_eyes() {
        // PD-080: the mode in force is always in /api/bpm/meta, after the draft-editing setting
        runner.run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                .containsExactly(Map.entry("authoring.cockpit-drafts", false),
                        Map.entry("lifecycle.approver-must-differ-from", "makers"),
                        Map.entry("lifecycle.retire-approval", "single"),
                        Map.entry("lifecycle.retire-last-version", "confirm"),
                        Map.entry("lifecycle.acknowledge-warnings", "UNMASKED_PERSONAL_DATA"),
                        Map.entry("approval.require-content-hash", true),
                        Map.entry("lifecycle.require-simulation-report", "")));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-approval=single")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-approval", "single"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-approval=four-eyes")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.retire-approval", "four-eyes"));
        runner.withPropertyValues("horus.bpm.lifecycle.retire-approval=dual")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.lifecycle.retire-approval"));
    }

    @Test
    void RLE_201_the_rule_sets_that_need_a_simulation_report_are_published_and_checked_at_start_up() {
        // PD-115: none by default; a host lists rule sets by name, shown comma-separated like the other lists
        runner.withPropertyValues("horus.bpm.rules.lifecycle.require-simulation-report=card-fee,loan-pricing")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsEntry("lifecycle.require-simulation-report", "card-fee,loan-pricing"));
        runner.withPropertyValues("horus.bpm.rules.lifecycle.require-simulation-report=Card Fee")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.rules.lifecycle.require-simulation-report")
                        .hasMessageContaining("kebab-case"));
    }

    @Test
    void RLE_201_the_features_the_build_announces_match_the_pattern_the_cockpit_accepts() {
        assertThat(RuleEngineModule.FEATURES).containsExactly("rules.history-readings", "rules.simulation-reports",
                "rules.templates").allMatch(feature -> feature.matches("^[a-z]+(\\.[a-z0-9-]+)+$"));
    }

    @Test
    void the_condition_builder_feature_is_announced_by_default_and_a_host_can_turn_it_off() {
        runner.run(context -> assertThat(context.getBean(ModuleDescriptor.class).features())
                .contains("rules.condition-builder"));
        runner.withPropertyValues("horus.bpm.rules.authoring.condition-builder=false")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).features())
                        .doesNotContain("rules.condition-builder").contains("rules.templates"));
    }

    @Test
    void RLE_197_the_template_service_offers_the_built_in_four_and_the_host_folder(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        // Given the bank's deposit-rate-by-term in its folder and the blank template hidden
        java.nio.file.Files.writeString(folder.resolve("deposit-rate-by-term.json"), """
                { "id": "deposit-rate-by-term",
                  "labels": { "en": "Deposit rate by term", "vi": "Lãi suất theo kỳ hạn" },
                  "descriptions": { "en": "Annual rate for each term.", "vi": "Lãi suất năm theo kỳ hạn." },
                  "document": { "schemaVersion": 1, "hitPolicy": "first",
                    "inputs": [ { "name": "termMonths", "type": "integer" } ],
                    "outputs": [ { "name": "annualRate", "type": "decimal" } ],
                    "rules": [ { "id": "R1", "when": {}, "then": { "annualRate": "4.2" } } ] } }
                """);
        var author = new io.github.dhluong90.bpm.rules.spi.CallerResolver() {
            @Override
            public String currentCallerId() {
                return "lan";
            }

            @Override
            public java.util.Set<String> currentPermissions() {
                return java.util.Set.of("rules:author");
            }
        };

        // When the host starts with the folder configured
        runner.withBean(io.github.dhluong90.bpm.rules.spi.CallerResolver.class, () -> author)
                .withPropertyValues("horus.bpm.rules.templates.location=" + folder,
                        "horus.bpm.rules.templates.hidden=blank")
                .run(context -> {
                    // Then Lan lists the three built-in ones left and the bank's own, after them
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(io.github.dhluong90.bpm.rules.api.RuleSetTemplateService.class).list())
                            .extracting(io.github.dhluong90.bpm.rules.api.RuleSetTemplate::id)
                            .containsExactly("fee-by-tier", "eligibility-with-reason", "limit-by-segment",
                                    "deposit-rate-by-term");
                });
    }

    @Test
    void RLE_198_a_template_file_that_is_not_valid_stops_the_start_and_names_the_file(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        java.nio.file.Files.writeString(folder.resolve("broken-template.json"), "{ \"id\": \"broken\" }");

        runner.withPropertyValues("horus.bpm.rules.templates.location=" + folder)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("Template file broken-template.json"));
        runner.withPropertyValues("horus.bpm.rules.templates.hidden=fee-by-teir")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.rules.templates.hidden").hasMessageContaining("fee-by-teir"));
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
