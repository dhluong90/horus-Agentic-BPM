package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.service.JobSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** PRE-006: the job settings bind with their defaults, refuse unsafe values and give each replica its own id. */
class JobPropertiesTest {

    private static ProcessEngineProperties bind(java.util.Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }

    @Test
    void PRE_006_job_defaults_are_a_60_second_timer_lease_and_a_unique_replica_id() {
        ProcessEngineProperties properties = bind(java.util.Map.of());

        JobSettings first = properties.jobs().toSettings(ProcessEngineAutoConfiguration.defaultReplicaId());
        JobSettings second = properties.jobs().toSettings(ProcessEngineAutoConfiguration.defaultReplicaId());
        assertThat(properties.jobs().enabled()).isTrue();
        assertThat(first.timerLease()).isEqualTo(Duration.ofSeconds(60));
        assertThat(first.batchSize()).isEqualTo(32);
        assertThat(properties.jobs().pollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(first.replicaId()).isNotEqualTo(second.replicaId());
    }

    @Test
    void PRE_006_a_configured_replica_id_and_lease_are_used() {
        JobSettings settings = bind(java.util.Map.of("horus.bpm.processes.jobs.replica-id", "payments-pod-2",
                "horus.bpm.processes.jobs.timer-lease", "90s")).jobs().toSettings("unused");

        assertThat(settings.replicaId()).isEqualTo("payments-pod-2");
        assertThat(settings.timerLease()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void PRE_006_unsafe_job_settings_fail_start_up_naming_the_property() {
        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.jobs.timer-lease", "0s")))
                .hasStackTraceContaining("horus.bpm.processes.jobs.timer-lease");
        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.jobs.batch-size", "0")))
                .hasStackTraceContaining("horus.bpm.processes.jobs.batch-size");
        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.jobs.poll-interval", "1ms")))
                .hasStackTraceContaining("horus.bpm.processes.jobs.poll-interval");
    }
}
