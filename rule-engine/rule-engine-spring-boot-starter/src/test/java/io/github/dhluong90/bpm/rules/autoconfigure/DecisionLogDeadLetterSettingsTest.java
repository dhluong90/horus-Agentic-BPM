package io.github.dhluong90.bpm.rules.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog;
import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * RLE-235: the retry policy of the asynchronous decision log is configured and validated; the dead-letter count is a
 * metric; and the alert rule file watches that metric by its exported name.
 */
class DecisionLogDeadLetterSettingsTest {

    @Test
    void RLE_235_the_retry_policy_has_defaults_and_names_the_property_it_refuses() {
        RuleEngineProperties.DecisionLog.Async defaults = RuleEngineProperties.DecisionLog.Async.defaults();
        assertThat(defaults.maxAttempts()).isEqualTo(5);
        assertThat(defaults.retryBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.maxRetryBackoff()).isEqualTo(Duration.ofMinutes(5));

        assertThatThrownBy(() -> new RuleEngineProperties.DecisionLog.Async(100, Duration.ofSeconds(1), 100_000, 0,
                Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .hasMessage("horus.bpm.rules.decision-log.async.max-attempts must be 1 to 100, was 0");
        assertThatThrownBy(() -> new RuleEngineProperties.DecisionLog.Async(100, Duration.ofSeconds(1), 100_000, 5,
                Duration.ofMillis(10), Duration.ofMinutes(5)))
                .hasMessageContaining("horus.bpm.rules.decision-log.async.retry-backoff");
        assertThatThrownBy(() -> new RuleEngineProperties.DecisionLog.Async(100, Duration.ofSeconds(1), 100_000, 5,
                Duration.ofMinutes(10), Duration.ofMinutes(5)))
                .hasMessageContaining("horus.bpm.rules.decision-log.async.max-retry-backoff");
    }

    @Test
    void RLE_235_the_dead_letter_count_is_a_gauge_that_reads_the_log_when_the_registry_asks() {
        AsyncDecisionLog log = mock(AsyncDecisionLog.class);
        when(log.deadLetterDepth()).thenReturn(0L, 2L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RuleEngineMetrics(mock(DecisionLogPartitions.class), log).bindTo(registry);

        var gauge = registry.get("horus.rle.decision.log.queue.dead").gauge();
        assertThat(gauge.value()).isZero();
        assertThat(gauge.value()).isEqualTo(2.0);
        assertThat(registry.find("horus.rle.decision.log.dead.lettered").functionCounter()).isNotNull();
    }

    @Test
    void RLE_235_the_alert_rule_file_fires_when_the_dead_letter_gauge_is_above_zero() throws Exception {
        String rules = Files.readString(Path.of("../docs/alerts/decision-log-queue.rules.yml"));
        String exported = RuleEngineMetrics.QUEUE_DEAD.replace('.', '_');

        assertThat(rules).contains("alert: RuleEngineDecisionLogDeadLetter");
        assertThat(rules).contains("expr: max(" + exported + ") > 0");
        assertThat(exported).isEqualTo("horus_rle_decision_log_queue_dead");
    }
}
