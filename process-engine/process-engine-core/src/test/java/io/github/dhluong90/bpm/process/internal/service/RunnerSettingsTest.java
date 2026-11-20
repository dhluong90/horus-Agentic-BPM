package io.github.dhluong90.bpm.process.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Per-kind runner settings and their fall-backs (#363). */
class RunnerSettingsTest {

    private static final JobSettings JOBS = JobSettings.defaults("pod-1");

    @Test
    void PRE_057_a_host_that_sets_nothing_runs_every_kind_with_the_engine_wide_values() {
        // Agent steps retry a schema violation once; a remote service that breaks its schema is not retried.
        assertThat(AsyncSettings.DEFAULTS.runner(JobStore.ASYNC_SERVICE, JOBS)).isEqualTo(new RunnerSettings(16,
                Duration.ofSeconds(30), 3, Duration.ofSeconds(10), Duration.ofMillis(500), 0));
        assertThat(AsyncSettings.DEFAULTS.runner(JobStore.ASYNC_AGENT, JOBS)).isEqualTo(new RunnerSettings(16,
                Duration.ofSeconds(30), 3, Duration.ofSeconds(10), Duration.ofMillis(500), 1));
        assertThat(AsyncSettings.DEFAULTS.lease(JobStore.ASYNC_SERVICE, null, JOBS))
                .isEqualTo(AsyncSettings.DEFAULTS.lease(null));
    }

    @Test
    void PRE_057_each_kind_keeps_what_the_host_set_and_falls_back_for_the_rest() {
        AsyncSettings async = new AsyncSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 16, Map.of(),
                Map.of(JobStore.ASYNC_AGENT, new RunnerSettings.Overrides(4, Duration.ofMinutes(2), 2, null,
                        Duration.ofMillis(200), 0)));

        assertThat(async.runner(JobStore.ASYNC_AGENT, JOBS)).isEqualTo(new RunnerSettings(4, Duration.ofMinutes(2), 2,
                Duration.ofSeconds(10), Duration.ofMillis(200), 0));
        assertThat(async.runner(JobStore.ASYNC_SERVICE, JOBS)).isEqualTo(AsyncSettings.DEFAULTS.runner(
                JobStore.ASYNC_SERVICE, JOBS));
        assertThat(async.lease(JobStore.ASYNC_AGENT, null, JOBS)).isEqualTo(Duration.ofSeconds(150));
        assertThat(async.lease(JobStore.ASYNC_AGENT, Duration.ofSeconds(5), JOBS)).isEqualTo(Duration.ofSeconds(35));
    }

    @Test
    void PRE_057_settings_for_a_kind_that_is_not_async_or_out_of_range_are_refused() {
        assertThatThrownBy(() -> new AsyncSettings(Duration.ofSeconds(30), Duration.ofSeconds(30), 16, Map.of(),
                Map.of(JobStore.TIMER, RunnerSettings.Overrides.NONE)))
                .hasMessageContaining("no async job kind");
        assertThatThrownBy(() -> new RunnerSettings(0, Duration.ofSeconds(1), 1, Duration.ofSeconds(1),
                Duration.ofMillis(1), 0)).hasMessageContaining("slots");
        assertThatThrownBy(() -> new RunnerSettings(1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1),
                Duration.ofSeconds(11), 0)).hasMessageContaining("guard-budget");
        assertThatThrownBy(() -> new RunnerSettings(1, Duration.ofSeconds(1), 1, Duration.ofSeconds(1),
                Duration.ofMillis(1), 6)).hasMessageContaining("schema-retries");
    }
}
