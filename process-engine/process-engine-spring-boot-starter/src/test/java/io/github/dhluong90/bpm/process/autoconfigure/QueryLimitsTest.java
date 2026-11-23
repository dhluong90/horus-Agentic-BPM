package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import io.github.dhluong90.bpm.process.internal.service.EngineSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Limits that protect the host database may be lowered, never raised (ADR-0015, DESIGN 6.5, PD-103). */
class QueryLimitsTest {

    @Test
    void query_limits_may_be_lowered_but_never_raised() {
        assertThat(new ProcessEngineProperties.Query(50, Duration.ofDays(7), 100, Duration.ofHours(1)).maxPageSize()).isEqualTo(50);

        assertThatThrownBy(() -> new ProcessEngineProperties.Query(201, Duration.ofDays(31), 100, Duration.ofHours(1)))
                .hasMessageContaining("horus.bpm.processes.query.max-page-size");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(0, Duration.ofDays(31), 100, Duration.ofHours(1)))
                .hasMessageContaining("max-page-size");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ofDays(32), 100, Duration.ofHours(1)))
                .hasMessageContaining("horus.bpm.processes.query.max-range");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ZERO, 100, Duration.ofHours(1)))
                .hasMessageContaining("max-range");
        assertThat(new ProcessEngineProperties.Query(200, Duration.ofDays(31), 1_000, Duration.ofHours(1)).maxRoles()).isEqualTo(1_000);
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ofDays(31), 0, Duration.ofHours(1)))
                .hasMessageContaining("horus.bpm.processes.query.max-roles");
        assertThatThrownBy(() -> new ProcessEngineProperties.Query(200, Duration.ofDays(31), 1_001, Duration.ofHours(1)))
                .hasMessageContaining("max-roles");
    }

    @Test
    void PRE_054_the_cancellation_reason_limit_may_be_lowered_but_never_raised_above_2000() {
        assertThat(new ProcessEngineProperties.Lifecycle(500).reasonMaxLength()).isEqualTo(500);

        assertThatThrownBy(() -> new ProcessEngineProperties.Lifecycle(2_001))
                .hasMessageContaining("horus.bpm.processes.lifecycle.reason-max-length");
        assertThatThrownBy(() -> new ProcessEngineProperties.Lifecycle(0)).hasMessageContaining("reason-max-length");
    }

    @Test
    void PRE_054_processes_cancel_is_its_own_permission_in_the_module_catalogue() {
        assertThat(ProcessEngineAutoConfiguration.PERMISSIONS).contains("processes:cancel", "processes:start");
    }

    @Test
    void PRE_054_a_host_setting_above_2000_refuses_start_up_through_the_property_binding() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(BindProperties.class);

        runner.withPropertyValues("horus.bpm.processes.lifecycle.reason-max-length=2001")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("horus.bpm.processes.lifecycle.reason-max-length must be 1 to 2000"));
        runner.withPropertyValues("horus.bpm.processes.lifecycle.reason-max-length=1500")
                .run(context -> assertThat(context.getBean(ProcessEngineProperties.class).lifecycle().reasonMaxLength())
                        .isEqualTo(1500));
        runner.run(context -> assertThat(context.getBean(ProcessEngineProperties.class).toSettings(
                EngineSettings.ApproverRule.MAKERS, EngineSettings.RetireLastVersionRule.CONFIRM).reasonMaxLength())
                .isEqualTo(2000));
    }

    @Test
    void PRE_054_the_engine_settings_refuse_a_reason_limit_above_2000_on_every_path() {
        assertThatThrownBy(() -> new EngineSettings(false, 500, 1_048_576, Duration.ofHours(24), Duration.ofMillis(200),
                200, Duration.ofDays(31), 2_001)).hasMessageContaining("lifecycle.reason-max-length must be 1 to 2000");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProcessEngineProperties.class)
    static class BindProperties {
    }
}
