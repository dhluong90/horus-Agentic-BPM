package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Limits that protect the host database may be lowered, never raised (ADR-0015, DESIGN 6.5, PD-103). */
class QueryLimitsTest {

    @Test
    void query_limits_may_be_lowered_but_never_raised() {
        assertThat(new ProcessEngineProperties.Query(50, Duration.ofDays(7)).maxPageSize()).isEqualTo(50);

        assertThatThrownBy(() -> new ProcessEngineProperties.Query(201, Duration.ofDays(31)))
                .hasMessageContaining("horus.bpm.processes.query.max-page-size");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(0, Duration.ofDays(31)))
                .hasMessageContaining("max-page-size");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ofDays(32)))
                .hasMessageContaining("horus.bpm.processes.query.max-range");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ZERO))
                .hasMessageContaining("max-range");
    }

    @Test
    void PRE_050_the_cancellation_reason_limit_may_be_lowered_but_never_raised_above_2000() {
        assertThat(new ProcessEngineProperties.Lifecycle(500).reasonMaxLength()).isEqualTo(500);

        assertThatThrownBy(() -> new ProcessEngineProperties.Lifecycle(2_001))
                .hasMessageContaining("horus.bpm.processes.lifecycle.reason-max-length");
        assertThatThrownBy(() -> new ProcessEngineProperties.Lifecycle(0)).hasMessageContaining("reason-max-length");
    }

    @Test
    void PRE_050_processes_cancel_is_its_own_permission_in_the_module_catalogue() {
        assertThat(ProcessEngineAutoConfiguration.PERMISSIONS).contains("processes:cancel", "processes:start");
    }
}
