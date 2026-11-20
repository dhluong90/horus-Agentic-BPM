package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The rules entry the starter contributes to {@code /api/bpm/meta} and {@code /api/bpm/me} (RLE-019), and
 * the role mapping property {@code horus.bpm.rules.security.role-permissions}.
 */
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
                .containsExactlyEntriesOf(Map.of("authoring.cockpit-drafts", false)));
        runner.withPropertyValues("horus.bpm.rules.authoring.cockpit-drafts=true")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsExactlyEntriesOf(Map.of("authoring.cockpit-drafts", true)));
        runner.withPropertyValues("horus.bpm.rules.authoring.cockpit-drafts=false")
                .run(context -> assertThat(context.getBean(ModuleDescriptor.class).settings())
                        .containsExactlyEntriesOf(Map.of("authoring.cockpit-drafts", false)));
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
}
