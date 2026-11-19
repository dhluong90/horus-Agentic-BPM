package com.horus.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.horus.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * waits for the next run, and the default-partition gauge.
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
        assertThat(output).contains("could not create decision log partitions; the next run");
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
}
