package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.CannotAcquireLockException;

/**
 * The running host's side of RLE-017: the period ahead from configuration, the repeated run, a lock timeout that
 * waits for the next run, the default-partition gauge, and retention: whole months, at least the floor, required
 * outside {@code dev}, and never a drop without a setting (PD-050).
 */
@ExtendWith(OutputCaptureExtension.class)
class RuleEnginePartitionMaintenanceTest {

    static final Instant JAN_26_2027 = Instant.parse("2027-01-26T00:00:00Z");
    final Clock clock = Clock.fixed(JAN_26_2027, ZoneOffset.UTC);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RuleEngineProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner properties = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void RLE_017_partitions_ahead_defaults_to_seven_days_and_is_bounded() {
        properties.run(context -> assertThat(context.getBean(RuleEngineProperties.class).decisionLog()
                .partitionsAheadOrDefault()).isEqualTo(Duration.ofDays(7)));
        properties.withPropertyValues("horus.bpm.rules.decision-log.partitions-ahead=P40D")
                .run(context -> assertThat(context.getBean(RuleEngineProperties.class).decisionLog()
                        .partitionsAheadOrDefault()).isEqualTo(Duration.ofDays(40)));
        properties.withPropertyValues("horus.bpm.rules.decision-log.partitions-ahead=P367D")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.rules.decision-log.partitions-ahead"));
        properties.withPropertyValues("horus.bpm.rules.decision-log.partitions-ahead=-P1D")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void RLE_017_retention_has_no_default_is_whole_months_and_at_least_the_floor() {
        properties.run(context -> assertThat(context.getBean(RuleEngineProperties.class).decisionLog().retentionPeriod())
                .isNull());
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=P12M")
                .run(context -> assertThat(context.getBean(RuleEngineProperties.class).decisionLog().retentionPeriod())
                        .isEqualTo(Period.ofMonths(12)));
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=P10Y")
                .run(context -> assertThat(context.getBean(RuleEngineProperties.class).decisionLog().retentionPeriod()
                        .toTotalMonths()).isEqualTo(120));
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=keep-forever")
                .run(context -> {
                    RuleEngineProperties.DecisionLog log = context.getBean(RuleEngineProperties.class).decisionLog();
                    assertThat(log.retentionPeriod()).isNull();
                    assertThat(log.keepForeverChosen()).isTrue();
                });
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=forever")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("keep-forever"));
        // Below the floor of 12 months, in days, or a floor lowered below 12 months: start-up stops naming the property
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=P11M")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.rules.decision-log.retention must be at least"));
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention=P400D")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("whole months"));
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention-floor=P6M")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("horus.bpm.rules.decision-log.retention-floor must be at least P12M"));
        // A host that raises the floor to its own policy refuses a shorter retention set by mistake
        properties.withPropertyValues("horus.bpm.rules.decision-log.retention-floor=P84M",
                        "horus.bpm.rules.decision-log.retention=P60M")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("retention-floor (P84M)"));
    }

    @Test
    void RLE_017_unset_retention_warns_every_start_up_and_keep_forever_is_explicit(CapturedOutput output) {
        RuleEngineProperties.DecisionLog unset = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, null, null);
        assertThat(DecisionLogRetention.resolve(unset)).isNull();
        assertThat(DecisionLogRetention.resolve(unset)).isNull();
        assertThat(output.getAll().split("WARN", -1)).hasSize(3);
        assertThat(output).contains("horus.bpm.rules.decision-log.retention", "every decision is kept", "KB");

        RuleEngineProperties.DecisionLog explicit = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, "keep-forever", null);
        assertThat(DecisionLogRetention.resolve(explicit)).isNull();
        assertThat(output.getAll().split("WARN", -1)).hasSize(3);

        RuleEngineProperties.DecisionLog months = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, "P120M", null);
        assertThat(DecisionLogRetention.resolve(months)).isEqualTo(Period.ofMonths(120));
    }

    @Test
    void RLE_100_from_1_0_an_unset_retention_refuses_start_up_outside_dev_and_until_then_it_warns(
            CapturedOutput output) {
        RuleEngineProperties.DecisionLog unset = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, null, null);
        org.springframework.mock.env.MockEnvironment prod = new org.springframework.mock.env.MockEnvironment();
        prod.setActiveProfiles("prod");

        // Given engine 1.0.0, an unset retention outside dev stops start-up and names the property and the choices
        assertThatThrownBy(() -> DecisionLogRetention.resolve(unset, prod, "1.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("horus.bpm.rules.decision-log.retention is required from Rule Engine 1.0")
                .hasMessageContaining("keep-forever").hasMessageContaining("P12M");
        // and so does a host with no profile at all, and a host on dev together with another profile
        assertThatThrownBy(() -> DecisionLogRetention.resolve(unset, new org.springframework.mock.env.MockEnvironment(),
                "1.2.0")).isInstanceOf(IllegalStateException.class);
        org.springframework.mock.env.MockEnvironment mixed = new org.springframework.mock.env.MockEnvironment();
        mixed.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> DecisionLogRetention.resolve(unset, mixed, "2.0.0"))
                .isInstanceOf(IllegalStateException.class);

        // but the dev profile needs no setting: it warns and keeps everything, as before 1.0
        org.springframework.mock.env.MockEnvironment dev = new org.springframework.mock.env.MockEnvironment();
        dev.setActiveProfiles("dev", "test");
        assertThat(DecisionLogRetention.resolve(unset, dev, "1.0.0")).isNull();
        assertThat(output).contains("retention is unset");

        // and before 1.0 nothing refuses start-up on any profile
        assertThat(DecisionLogRetention.resolve(unset, prod, "0.3.0-SNAPSHOT")).isNull();
        assertThat(DecisionLogRetention.resolve(unset, prod, "0.9.9")).isNull();
        // but an unknown version fails closed (#450): it never falls back to the 0.x behaviour
        assertThatThrownBy(() -> DecisionLogRetention.resolve(unset, prod, null))
                .isInstanceOf(IllegalStateException.class);

        // and from 1.0 a bank that chose either answer starts normally
        RuleEngineProperties.DecisionLog forever = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, "keep-forever", null);
        RuleEngineProperties.DecisionLog months = new RuleEngineProperties.DecisionLog(
                null, null, null, null, null, "P12M", null);
        assertThat(DecisionLogRetention.resolve(forever, prod, "1.0.0")).isNull();
        assertThat(DecisionLogRetention.resolve(months, prod, "1.0.0")).isEqualTo(Period.ofMonths(12));
    }

    @Test
    void RLE_100_the_required_from_version_is_read_as_a_major_number() {
        assertThat(DecisionLogRetention.required("1.0.0")).isTrue();
        assertThat(DecisionLogRetention.required("1.0.0-RC1")).isTrue();
        assertThat(DecisionLogRetention.required("12.3.4")).isTrue();
        assertThat(DecisionLogRetention.required("0.3.0-SNAPSHOT")).isFalse();
        assertThat(DecisionLogRetention.required("0.99.0")).isFalse();
        // a version that cannot be read fails closed (#450)
        assertThat(DecisionLogRetention.required("unknown")).isTrue();
        assertThat(DecisionLogRetention.required("")).isTrue();
        assertThat(DecisionLogRetention.required("${project.version}")).isTrue();
        assertThat(DecisionLogRetention.required(null)).isTrue();
    }

    @Test
    void RLE_017_without_a_retention_the_job_never_drops_a_partition() {
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7))).thenReturn(List.of());

        new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7), Duration.ofDays(1), null).runOnce();

        verify(partitions, never()).dropExpired(any(), any());
    }

    @Test
    void RLE_017_with_a_retention_each_run_drops_expired_months_and_logs_each_drop(CapturedOutput output) {
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7))).thenReturn(List.of());
        when(partitions.dropExpired(JAN_26_2027, Period.ofMonths(12))).thenReturn(List.of(
                new DecisionLogPartitions.Dropped("rle_decision_2025_12", Instant.parse("2025-12-01T00:00:00Z"),
                        Instant.parse("2026-01-01T00:00:00Z"), Map.of("bank-a", 3L), 3L)));

        new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7), Duration.ofDays(1),
                Period.ofMonths(12)).runOnce();

        verify(partitions).dropExpired(JAN_26_2027, Period.ofMonths(12));
        assertThat(output).contains("dropped decision log partition rle_decision_2025_12",
                "decision-log.retention=P12M", "3 decisions {bank-a=3}", "recorded in rle_decision_log_drop");
    }

    @Test
    void RLE_029_query_range_and_statement_timeout_are_configured_and_bounded() {
        properties.run(context -> assertThat(context.getBean(RuleEngineProperties.class).toQuerySettings())
                .satisfies(q -> {
                    assertThat(q.maxRange()).isEqualTo(Duration.ofDays(31));
                    assertThat(q.statementTimeout()).isEqualTo(Duration.ofSeconds(2));
                }));
        properties.withPropertyValues("horus.bpm.rules.query.max-range=P7D",
                        "horus.bpm.rules.query.statement-timeout=PT1S")
                .run(context -> assertThat(context.getBean(RuleEngineProperties.class).toQuerySettings())
                        .satisfies(q -> {
                            assertThat(q.maxRange()).isEqualTo(Duration.ofDays(7));
                            assertThat(q.statementTimeout()).isEqualTo(Duration.ofSeconds(1));
                        }));
        properties.withPropertyValues("horus.bpm.rules.query.max-range=P32D")
                .run(context -> assertThatThrownBy(() -> context.getBean(RuleEngineProperties.class).toQuerySettings())
                        .hasMessageContaining("horus.bpm.rules.query.max-range"));
    }

    @Test
    void RLE_017_each_run_creates_months_up_to_the_period_ahead_of_the_clock() {
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7))).thenReturn(List.of("rle_decision_2027_02"));

        assertThat(new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7)).runOnce())
                .containsExactly("rle_decision_2027_02");
    }

    @Test
    void TR_OPS_05_a_lock_timeout_waits_for_the_next_run_without_failing(CapturedOutput output) {
        // Given a run that times out on the advisory lock, then one that succeeds
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7)))
                .thenThrow(new CannotAcquireLockException("canceling statement due to lock timeout"))
                .thenReturn(List.of());
        RuleEnginePartitionMaintenance maintenance =
                new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7), Duration.ofMillis(50));

        // When the scheduled runs happen
        maintenance.start();
        try {
            verify(partitions, timeout(5_000).atLeast(2)).ensureAhead(JAN_26_2027, Duration.ofDays(7));
        } finally {
            maintenance.destroy();
        }

        // Then the failure was logged with the retry, and the schedule kept running
        assertThat(output).contains("could not create decision log partitions").contains("the next run");
    }

    @Test
    void RLE_017_default_rows_gauge_reads_the_default_partition() {
        // Given one straggler, then none after the job moved it
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.defaultRows()).thenReturn(1L, 0L).thenThrow(new IllegalStateException("database down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // When
        new RuleEngineMetrics(partitions).bindTo(registry);

        // Then
        var gauge = registry.get("horus.rle.partition.default.rows").gauge();
        assertThat(gauge.value()).isEqualTo(1.0);
        assertThat(gauge.value()).isZero();
        assertThat(gauge.value()).isNaN();
    }

    @Test
    void RLE_017_default_rows_gauge_survives_a_garbage_collection_after_binding() {
        // Given a gauge whose binder nothing but the registry references
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.defaultRows()).thenReturn(1L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new RuleEngineMetrics(partitions).bindTo(registry);

        // When a garbage collection has run: a weakly reachable sentinel is cleared only by one
        java.lang.ref.WeakReference<Object> sentinel = new java.lang.ref.WeakReference<>(new Object());
        for (int i = 0; i < 50 && sentinel.get() != null; i++) {
            System.gc();
        }
        assertThat(sentinel.get()).as("a garbage collection ran").isNull();

        // Then the gauge still reads the default partition instead of NaN
        assertThat(registry.get("horus.rle.partition.default.rows").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void TR_OPS_05_an_error_in_one_run_does_not_end_the_loop_and_the_next_run_happens(CapturedOutput output) {
        // Given a run that throws an Error from a library, then one that succeeds
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7)))
                .thenThrow(new NoClassDefFoundError("org/example/MissingDriverClass"))
                .thenReturn(List.of());
        RuleEnginePartitionMaintenance maintenance =
                new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7), Duration.ofMillis(50));

        // When the scheduled runs happen
        maintenance.start();
        try {
            verify(partitions, timeout(5_000).atLeast(2)).ensureAhead(JAN_26_2027, Duration.ofDays(7));
        } finally {
            maintenance.destroy();
        }

        // Then the Error was logged, the loop kept running, and the last success is recorded
        assertThat(output).contains("could not create decision log partitions").contains("NoClassDefFoundError");
        assertThat(maintenance.stopped()).isFalse();
        assertThat(maintenance.lastSuccess()).isEqualTo(JAN_26_2027);
    }

    @Test
    void TR_OPS_05_a_virtual_machine_error_stops_the_loop_and_health_reports_down() throws Exception {
        // Given a run that throws OutOfMemoryError
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7))).thenThrow(new OutOfMemoryError("test"));
        RuleEnginePartitionMaintenance maintenance =
                new RuleEnginePartitionMaintenance(partitions, clock, Duration.ofDays(7), Duration.ofMillis(20));
        io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator migrator =
                mock(io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new io.github.dhluong90.bpm.rules.postgres.SchemaStatus("5", 0));
        RuleEngineHealthCheck health = new RuleEngineHealthCheck(migrator, partitions, clock, System::nanoTime,
                List.of(maintenance));
        assertThat(health.check().up()).isTrue();
        assertThat(health.check().details()).containsEntry("partitionMaintenanceLastSuccess", "never");

        // When the scheduled run fails
        maintenance.start();
        try {
            verify(partitions, timeout(5_000)).ensureAhead(JAN_26_2027, Duration.ofDays(7));
            Thread.sleep(300);   // several periods: a stopped loop must not run again
        } finally {
            maintenance.destroy();
        }

        // Then the loop ran once and stopped, and health is DOWN naming the job
        verify(partitions, org.mockito.Mockito.times(1)).ensureAhead(JAN_26_2027, Duration.ofDays(7));
        assertThat(maintenance.stopped()).isTrue();
        assertThat(health.check().up()).isFalse();
        assertThat(health.check().details()).containsEntry("partitionMaintenance", "STOPPED");
    }

    @Test
    void RLE_017_health_shows_the_time_of_the_last_successful_run() {
        DecisionLogPartitions partitions = mock(DecisionLogPartitions.class);
        when(partitions.ensureAhead(JAN_26_2027, Duration.ofDays(7))).thenReturn(List.of());
        RuleEnginePartitionMaintenance maintenance = new RuleEnginePartitionMaintenance(partitions, clock,
                Duration.ofDays(7));
        io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator migrator =
                mock(io.github.dhluong90.bpm.rules.postgres.RuleEngineSchemaMigrator.class);
        when(migrator.status()).thenReturn(new io.github.dhluong90.bpm.rules.postgres.SchemaStatus("5", 0));

        maintenance.runOnce();

        assertThat(new RuleEngineHealthCheck(migrator, partitions, clock, System::nanoTime, List.of(maintenance))
                .check().details()).containsEntry("partitionMaintenanceLastSuccess", "2027-01-26T00:00:00Z");
    }
}
