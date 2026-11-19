package io.github.dhluong90.bpm.host.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.host.spi.ModuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** An engine entry that does not match the published schema never reaches a client. */
class ModuleDescriptorTest {

    private static final List<String> PERMISSIONS = TestHosts.RULES_PERMISSIONS;

    @Test
    void a_valid_entry_is_copied_and_immutable() {
        List<String> features = new ArrayList<>(List.of("rules.simulation"));
        ModuleDescriptor descriptor = new ModuleDescriptor("rules", "0.1.0-SNAPSHOT", "v1", features,
                Map.of("authoring.cockpit-drafts", false), PERMISSIONS, Map.of("ROLE_RISK_MANAGER", List.of("rules:approve")));
        features.add("rules.grid-editor");

        assertThat(descriptor.features()).containsExactly("rules.simulation");
        assertThatThrownBy(() -> descriptor.permissions().add("rules:x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_permission_segment_may_join_words_with_hyphens() {
        ModuleDescriptor descriptor = new ModuleDescriptor("rules", "0.1.0", "v1", List.of(), Map.of(),
                List.of("rules:log:read", "rules:log:search-masked"),
                Map.of("ROLE_PRIVACY_OPS", List.of("rules:log:search-masked")));

        assertThat(descriptor.permissions()).contains("rules:log:search-masked");
    }

    @Test
    void a_role_mapping_to_an_unknown_permission_is_refused() {
        assertThatThrownBy(() -> TestHosts.rules(Map.of("ROLE_PRICING_APPROVER", List.of("rules:aprove"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Role mapping ROLE_PRICING_APPROVER grants unknown permission 'rules:aprove'");
    }

    @Test
    void values_outside_the_schema_are_refused() {
        assertRefused("tasks", "0.1.0", "v1", List.of(), PERMISSIONS, "Unknown engine module 'tasks'");
        assertRefused("rules", "0.1", "v1", List.of(), PERMISSIONS, "Invalid engineVersion '0.1'");
        assertRefused("rules", "0.1.0", "1", List.of(), PERMISSIONS, "Invalid apiVersion '1'");
        assertRefused("rules", "0.1.0", "v1", List.of("Simulation"), PERMISSIONS, "Invalid feature 'Simulation'");
        assertRefused("rules", "0.1.0", "v1", List.of("processes.diagram"), PERMISSIONS, "must start with 'rules.'");
        assertRefused("rules", "0.1.0", "v1", List.of("rules.a", "rules.a"), PERMISSIONS, "Duplicate entry in features");
        assertRefused("rules", "0.1.0", "v1", List.of(), List.of("processes:read"), "must start with 'rules:'");
        assertRefused("rules", "0.1.0", "v1", List.of(), List.of("rules:Read"), "Invalid permission 'rules:Read'");
        assertRefused("rules", "0.1.0", "v1", List.of(), List.of("rules:log:-masked"), "Invalid permission");
        assertRefused("rules", "0.1.0", "v1", List.of(), List.of("rules:log:search-"), "Invalid permission");
        assertThatThrownBy(() -> new ModuleDescriptor("rules", "0.1.0", "v1", List.of(), Map.of("ratio", 0.5),
                PERMISSIONS, Map.of())).hasMessageContaining("Setting 'ratio' must be a boolean, an integer or a string");
    }

    private static void assertRefused(String module, String engineVersion, String apiVersion, List<String> features,
            List<String> permissions, String message) {
        assertThatThrownBy(() -> new ModuleDescriptor(module, engineVersion, apiVersion, features, Map.of(), permissions,
                Map.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }
}
