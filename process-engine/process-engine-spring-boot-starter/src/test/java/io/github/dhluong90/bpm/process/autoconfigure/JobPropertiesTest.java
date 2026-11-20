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

    @Test
    void PRE_017_async_defaults_are_a_30_second_margin_a_30_second_timeout_and_16_adapter_slots() {
        io.github.dhluong90.bpm.process.internal.service.AsyncSettings async = bind(java.util.Map.of()).toAsyncSettings();
        assertThat(async.leaseMargin()).isEqualTo(Duration.ofSeconds(30));
        assertThat(async.defaultTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(async.executorThreads()).isEqualTo(16);
        assertThat(async.lease(Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(32));
        assertThat(async.lease(null)).isEqualTo(Duration.ofSeconds(60));

        io.github.dhluong90.bpm.process.internal.service.AsyncSettings tuned = bind(java.util.Map.of(
                "horus.bpm.processes.jobs.lease-margin", "45s", "horus.bpm.processes.adapters.default-timeout", "PT5M",
                "horus.bpm.processes.adapters.executor-threads", "64")).toAsyncSettings();
        assertThat(tuned.lease(null)).isEqualTo(Duration.ofSeconds(345));
        assertThat(tuned.executorThreads()).isEqualTo(64);

        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.jobs.lease-margin", "0s")))
                .hasStackTraceContaining("horus.bpm.processes.jobs.lease-margin");
        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.adapters.executor-threads", "0")))
                .hasStackTraceContaining("horus.bpm.processes.adapters.executor-threads");
    }

    @Test
    void PRE_057_runners_default_to_the_engine_wide_keys_and_take_per_kind_values() {
        io.github.dhluong90.bpm.process.internal.service.JobSettings jobs =
                io.github.dhluong90.bpm.process.internal.service.JobSettings.defaults("pod-1");
        io.github.dhluong90.bpm.process.internal.service.AsyncSettings none = bind(java.util.Map.of()).toAsyncSettings();
        assertThat(none.runner("ASYNC_SERVICE", jobs)).isEqualTo(none.runner("ASYNC_AGENT", jobs))
                .isEqualTo(new io.github.dhluong90.bpm.process.internal.service.RunnerSettings(16, Duration.ofSeconds(30),
                        3, Duration.ofSeconds(10), Duration.ofMillis(500)));

        io.github.dhluong90.bpm.process.internal.service.AsyncSettings tuned = bind(java.util.Map.of(
                "horus.bpm.processes.adapters.executor-threads", "24",
                "horus.bpm.processes.runners.async-agent.slots", "4",
                "horus.bpm.processes.runners.async-agent.default-timeout", "PT2M",
                "horus.bpm.processes.runners.async-agent.retry-backoff", "30s",
                "horus.bpm.processes.runners.async-agent.guard-budget", "200ms",
                "horus.bpm.processes.runners.async-service.max-attempts", "5")).toAsyncSettings();
        assertThat(tuned.runner("ASYNC_AGENT", jobs)).isEqualTo(
                new io.github.dhluong90.bpm.process.internal.service.RunnerSettings(4, Duration.ofMinutes(2), 3,
                        Duration.ofSeconds(30), Duration.ofMillis(200)));
        assertThat(tuned.runner("ASYNC_SERVICE", jobs)).isEqualTo(
                new io.github.dhluong90.bpm.process.internal.service.RunnerSettings(24, Duration.ofSeconds(30), 5,
                        Duration.ofSeconds(10), Duration.ofMillis(500)));

        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.runners.async-agent.slots", "0")))
                .hasStackTraceContaining("horus.bpm.processes.runners.<kind>.slots");
        assertThatThrownBy(() -> bind(java.util.Map.of("horus.bpm.processes.runners.async-agent.guard-budget", "30s")))
                .hasStackTraceContaining("guard-budget");
    }
}
