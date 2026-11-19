package io.github.dhluong90.bpm.process.autoconfigure;

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

    private static void guard(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        ProcessEngineAutoConfiguration.requireDevProfileOnly(environment, PROPERTY);
    }
}
