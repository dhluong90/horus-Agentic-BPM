package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** PRE-205 (#92): {@code calls.max-depth} is 5 by default and may be lowered, never raised. */
class CallsSettingsTest {

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    @Test
    void PRE_205_the_call_depth_defaults_to_5_and_may_be_lowered_only() {
        assertThat(bind(Map.of()).calls().maxDepth()).isEqualTo(5);
        assertThat(bind(Map.of("horus.bpm.processes.calls.max-depth", "3")).calls().maxDepth()).isEqualTo(3);
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.calls.max-depth", "6"))).rootCause()
                .hasMessageContaining("calls.max-depth must be 1 to 5");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.calls.max-depth", "0"))).rootCause()
                .hasMessageContaining("calls.max-depth must be 1 to 5");
    }
}
