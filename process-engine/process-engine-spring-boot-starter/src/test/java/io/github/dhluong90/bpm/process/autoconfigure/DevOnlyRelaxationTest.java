package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** PRE-022: auto-approval of imported definitions is accepted only on the dev profile on its own. */
class DevOnlyRelaxationTest {

    private static final String PROPERTY = "horus.bpm.processes.definitions.auto-approve";

    @Test
    void PRE_022_dev_only_relaxations_refused_outside_dev() {
        assertThatThrownBy(() -> guard()).as("no profile").isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY);
        assertThatThrownBy(() -> guard("prod")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard("prod", "dev")).as("dev beside another profile").isInstanceOf(IllegalStateException.class);
        MockEnvironment defaultDev = new MockEnvironment();
        defaultDev.setDefaultProfiles("dev");
        assertThatThrownBy(() -> ProcessEngineAutoConfiguration.requireDevProfileOnly(defaultDev, PROPERTY))
                .as("spring.profiles.default=dev with no active profile").isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> guard("dev")).doesNotThrowAnyException();
        assertThatCode(() -> guard("dev", "test")).doesNotThrowAnyException();
    }

    @Test
    void PRE_022_dev_only_relaxations_refused_outside_dev_for_each_start_up() {
        // (a) prod with auto-approve, (b) prod with maker-checker off, (d) dev,prod with auto-approve: refused
        assertThatThrownBy(() -> startUp(new String[] {"prod"}, "horus.bpm.processes.definitions.auto-approve", "true"))
                .as("(a)").hasMessageContaining("horus.bpm.processes.definitions.auto-approve")
                .hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {"prod"}, "horus.bpm.processes.maker-checker.enabled", "false"))
                .as("(b)").hasMessageContaining("horus.bpm.processes.maker-checker.enabled")
                .hasMessageContaining("dev profile");
        assertThatThrownBy(() -> startUp(new String[] {"dev", "prod"}, "horus.bpm.processes.definitions.auto-approve",
                "true")).as("(d)").hasMessageContaining("dev profile");
        // (e) the removed key, whatever its value and on any profile, names its replacement
        for (String value : new String[] {"false", "true"}) {
            assertThatThrownBy(() -> startUp(new String[] {"dev"}, "horus.bpm.processes.definitions-auto-approve", value))
                    .as("(e) " + value).hasMessageContaining("horus.bpm.processes.definitions-auto-approve")
                    .hasMessageContaining("horus.bpm.processes.definitions.auto-approve");
        }
        // (c) dev alone with auto-approve, and dev with maker-checker off: accepted
        assertThatCode(() -> startUp(new String[] {"dev"}, "horus.bpm.processes.definitions.auto-approve", "true"))
                .as("(c)").doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"dev"}, "horus.bpm.processes.maker-checker.enabled", "false"))
                .doesNotThrowAnyException();
        assertThatCode(() -> startUp(new String[] {"prod"}, "horus.bpm.processes.maker-checker.enabled", "true"))
                .doesNotThrowAnyException();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void PRE_019_start_up_logs_one_warn_for_the_requester_relaxation_and_none_for_makers(
            org.springframework.boot.test.system.CapturedOutput output) {
        String relaxation = "horus.bpm.lifecycle.approver-must-differ-from=requester";

        // Given the default (makers), when the starter checks its settings at start-up
        startUp(new String[] {"prod"}, "horus.bpm.lifecycle.approver-must-differ-from", "makers");
        String afterMakers = output.getAll();

        // Then no WARN names the relaxation
        assertThat(afterMakers).doesNotContain(relaxation);

        // Given requester, when the starter checks its settings at start-up
        startUp(new String[] {"prod"}, "horus.bpm.lifecycle.approver-must-differ-from", "requester");
        String added = output.getAll().substring(afterMakers.length());

        // Then exactly one WARN line names the relaxed setting
        java.util.List<String> lines = added.lines().filter(l -> l.contains(relaxation)).toList();
        assertThat(lines).singleElement().satisfies(l -> assertThat(l).contains("WARN"));
    }

    @Test
    void PRE_019_shared_approver_setting_is_published_in_meta_for_the_process_module() {
        MockEnvironment defaults = new MockEnvironment();
        ProcessEngineProperties properties = org.springframework.boot.context.properties.bind.Binder.get(defaults)
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
        assertThat(new ProcessEngineAutoConfiguration().processEngineModuleDescriptor(properties, defaults).settings())
                .containsEntry("lifecycle.approver-must-differ-from", "makers");

        MockEnvironment relaxed = new MockEnvironment()
                .withProperty("horus.bpm.lifecycle.approver-must-differ-from", "requester");
        assertThat(new ProcessEngineAutoConfiguration().processEngineModuleDescriptor(properties, relaxed).settings())
                .containsEntry("lifecycle.approver-must-differ-from", "requester");

        MockEnvironment wrong = new MockEnvironment()
                .withProperty("horus.bpm.lifecycle.approver-must-differ-from", "nobody");
        assertThatThrownBy(() -> new ProcessEngineAutoConfiguration().processEngineModuleDescriptor(properties, wrong))
                .hasMessageContaining("horus.bpm.lifecycle.approver-must-differ-from");
    }

    private static void startUp(String[] profiles, String property, String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        environment.setProperty(property, value);
        ProcessEngineProperties properties = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
        ProcessEngineAutoConfiguration.requireDevOnlyRelaxations(environment, properties);
    }

    private static void guard(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        ProcessEngineAutoConfiguration.requireDevProfileOnly(environment, PROPERTY);
    }
}
