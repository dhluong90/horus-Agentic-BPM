package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.spi.FormRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The default task forms (PRE-216): JSON documents under the configured classpath folder and nothing else. A
 * reference is a plain relative name, so a definition can never name a file outside the folder.
 */
class ClasspathFormRegistryTest {

    private final ClasspathFormRegistry registry = new ClasspathFormRegistry("forms/",
            ClasspathFormRegistryTest.class.getClassLoader());

    @Test
    void PRE_216_a_schema_path_is_read_as_the_schema_without_hints() {
        FormRegistry.FormDefinition form = registry.find("bank-a", "approve.schema.json").orElseThrow();

        assertThat(form.schema()).containsKeys("type", "required", "properties", "if", "then");
        assertThat(form.hints()).isEmpty();
    }

    @Test
    void PRE_216_a_form_key_is_read_from_its_envelope_with_the_hints() {
        FormRegistry.FormDefinition form = registry.find("bank-a", "credit-review").orElseThrow();

        assertThat(form.schema()).containsKey("properties");
        assertThat(form.hints()).containsEntry("order", List.of("decision", "reason"));
    }

    @Test
    void PRE_216_a_name_outside_the_folder_or_not_plain_is_not_registered() {
        assertThat(registry.find("bank-a", "../other/outside.schema.json")).isEmpty();
        assertThat(registry.find("bank-a", "/other/outside.schema.json")).isEmpty();
        assertThat(registry.find("bank-a", "other/outside.schema.json")).isEmpty();
        assertThat(registry.find("bank-a", "forms/approve.schema.json")).isEmpty();
        assertThat(registry.find("bank-a", "approve schema.json")).isEmpty();
        assertThat(registry.find("bank-a", "no-such-form")).isEmpty();
        assertThat(registry.find("bank-a", null)).isEmpty();
    }

    @Test
    void PRE_216_the_settings_accept_relative_origins_and_a_relative_folder_only() {
        ProcessEngineProperties.Tasks tasks = new ProcessEngineProperties.Tasks("forms/",
                List.of("https://loans.example.com", "http://localhost:8080"));
        assertThat(tasks.formLinkOrigins()).hasSize(2);

        assertThatThrownBy(() -> new ProcessEngineProperties.Tasks("forms/", List.of("https://loans.example.com/path")))
                .hasMessageContaining("horus.bpm.processes.tasks.form-link-origins");
        assertThatThrownBy(() -> new ProcessEngineProperties.Tasks("/etc/", List.of()))
                .hasMessageContaining("horus.bpm.processes.tasks.form-resource-root");
        assertThatThrownBy(() -> new ProcessEngineProperties.Tasks("forms", List.of()))
                .hasMessageContaining("form-resource-root");
    }
}
