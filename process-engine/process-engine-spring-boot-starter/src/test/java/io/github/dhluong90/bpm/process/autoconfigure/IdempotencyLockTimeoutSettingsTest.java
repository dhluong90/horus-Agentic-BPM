package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** PRE-381 (#172): {@code idempotency.lock-timeout} is 2 seconds by default and may be lowered, never raised. */
class IdempotencyLockTimeoutSettingsTest {

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    @Test
    void PRE_381_the_idempotency_lock_timeout_defaults_to_2_seconds_and_may_be_lowered_only() {
        assertThat(bind(Map.of()).idempotency().lockTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(bind(Map.of("horus.bpm.processes.idempotency.lock-timeout", "500ms")).idempotency().lockTimeout())
                .isEqualTo(Duration.ofMillis(500));
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.idempotency.lock-timeout", "5s"))).rootCause()
                .hasMessageContaining("idempotency.lock-timeout must be between 1 millisecond and 2 seconds");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.idempotency.lock-timeout", "0s"))).rootCause()
                .hasMessageContaining("idempotency.lock-timeout must be between 1 millisecond and 2 seconds");
    }
}
