package io.github.dhluong90.bpm.process.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.internal.store.GaugeStore;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The meters of PRE-084 on a {@code SimpleMeterRegistry}: names, counts, bounded tags, gauges that move only when the
 * gauge refresh runs, and the settings that bind them. No database: the gauge store is a stub.
 */
class ProcessEngineMetricsTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private GaugeStore.Snapshot snapshot = new GaugeStore.Snapshot(1, 1, Map.of());
    private int reads;

    private ProcessEngineMetrics metrics(boolean definitionKeyTag) {
        ProcessEngineMetrics metrics = new ProcessEngineMetrics(() -> {
            reads++;
            return snapshot;
        }, java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC), definitionKeyTag);
        metrics.bindTo(registry);
        return metrics;
    }

    @Test
    void PRE_084_counters_timers_and_distributions_carry_engine_values_only() {
        // Given the meters on a registry, and an engine that reports a start, a completion, incidents, steps, attempts
        // and a timer fired 4 seconds late
        ProcessEngineMetrics metrics = metrics(false);

        // When the engine reports them
        for (int i = 0; i < 5; i++) {
            metrics.instanceStarted("loan-disbursement");
        }
        for (int i = 0; i < 3; i++) {
            metrics.instanceCompleted("loan-disbursement");
        }
        metrics.instanceFailed("loan-disbursement");
        metrics.incidentRaised("ADAPTER_TIMEOUT");
        metrics.incidentRaised("UNHANDLED_BUSINESS_ERROR");
        metrics.incidentRaised("LIMIT_EXCEEDED");
        metrics.stepApplied("START", "OK", Duration.ofMillis(7));
        metrics.stepApplied("ASYNC_RESULT", "FAILED", Duration.ofMillis(3));
        for (String outcome : new String[] {"ACCEPTED", "REFUSED", "FAILED", "DISCARDED", "DUPLICATE"}) {
            metrics.asyncAttempt("ASYNC_AGENT", outcome);
        }
        metrics.timerLag(Duration.ofSeconds(4));

        // Then the counts are the reported ones
        assertThat(registry.get("horus.pre.instances.started").counter().count()).isEqualTo(5);
        assertThat(registry.get("horus.pre.instances.completed").counter().count()).isEqualTo(3);
        assertThat(registry.get("horus.pre.instances.failed").counter().count()).isEqualTo(1);
        assertThat(registry.get("horus.pre.incidents.raised").tag("errorCode", "ADAPTER_TIMEOUT").counter().count())
                .isEqualTo(1);
        // and a code outside the engine's set, a host's business code included, is counted as OTHER, never as itself
        assertThat(registry.get("horus.pre.incidents.raised").tag("errorCode", "OTHER").counter().count()).isEqualTo(1);
        assertThat(registry.find("horus.pre.incidents.raised").tag("errorCode", "LIMIT_EXCEEDED").counter()).isNull();
        assertThat(registry.get("horus.pre.step.duration").tag("stepKind", "START").tag("outcome", "OK").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("horus.pre.async.attempts").tag("kind", "ASYNC_AGENT").tag("outcome", "DUPLICATE")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("horus.pre.timer.lag").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(4.0);
    }

    @Test
    void PRE_084_no_tag_holds_a_tenant_a_business_key_an_instance_id_or_a_variable() {
        // Given the meters, and values that a careless caller might pass as tag values
        ProcessEngineMetrics metrics = metrics(false);

        // When they are reported
        metrics.instanceStarted("loan-disbursement");
        metrics.incidentRaised("bank-a");
        metrics.incidentRaised("LN-2026-0042");
        metrics.stepApplied("01JD0000000000000000000010", "079088001234", Duration.ofMillis(1));
        metrics.asyncAttempt("bank-a", "LN-2026-0042");

        // Then every tag value across the registry is one of the fixed sets, and the definition key is not a tag
        Set<String> tagValues = registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream())
                .map(t -> t.getKey() + "=" + t.getValue()).collect(Collectors.toSet());
        assertThat(tagValues).doesNotContain("errorCode=bank-a", "errorCode=LN-2026-0042")
                .noneMatch(v -> v.contains("bank-a") || v.contains("LN-2026") || v.contains("079088001234")
                        || v.contains("01JD0000") || v.contains("loan-disbursement"));
        assertThat(tagValues).contains("errorCode=OTHER", "stepKind=OTHER", "outcome=OTHER", "kind=OTHER");
        assertThat(registry.getMeters().stream().flatMap(m -> m.getId().getTags().stream()).map(t -> t.getKey())
                .collect(Collectors.toSet())).isSubsetOf("errorCode", "stepKind", "outcome", "kind");
    }

    @Test
    void PRE_084_definition_key_is_a_tag_only_when_the_host_asks_for_it() {
        // Given the meters with metrics.definition-key-tag on
        ProcessEngineMetrics metrics = metrics(true);

        // When an instance starts
        metrics.instanceStarted("loan-disbursement");

        // Then the instance counter carries the key
        assertThat(registry.get("horus.pre.instances.started").tag("definitionKey", "loan-disbursement").counter().count())
                .isEqualTo(1);
    }

    @Test
    void PRE_084_gauges_are_read_by_the_refresh_and_never_by_a_scrape() {
        // Given the gauges before any refresh, and a scrape of every meter
        ProcessEngineMetrics metrics = metrics(false);
        registry.getMeters().forEach(Meter::measure);
        registry.getMeters().forEach(Meter::measure);
        assertThat(reads).as("a scrape never queries").isZero();
        assertThat(registry.get("horus.pre.instances.active").gauge().value()).isZero();

        // When the refresh runs once with 1 running instance, 1 open incident and a TIMER job due 90 s ago
        snapshot = new GaugeStore.Snapshot(1, 1, Map.of("TIMER", NOW.minusSeconds(90)));
        metrics.refreshGauges();
        registry.getMeters().forEach(Meter::measure);

        // Then the gauges show the figures, and the scrapes did not query again
        assertThat(reads).isEqualTo(1);
        assertThat(registry.get("horus.pre.instances.active").gauge().value()).isEqualTo(1);
        assertThat(registry.get("horus.pre.incidents.open").gauge().value()).isEqualTo(1);
        assertThat(registry.get("horus.pre.jobs.due.oldest.age").tag("kind", "TIMER").gauge().value()).isEqualTo(90);
        assertThat(registry.get("horus.pre.jobs.due.oldest.age").tag("kind", "ASYNC_AGENT").gauge().value()).isZero();
    }

    @Test
    void PRE_084_metrics_and_rollup_settings_bind_with_their_defaults_and_refuse_bad_values() {
        ProcessEngineProperties defaults = bind(Map.of());
        assertThat(defaults.metrics().gaugeRefresh()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.metrics().definitionKeyTag()).isFalse();
        assertThat(defaults.stats().rollupInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.stats().rollupLag()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaults.stats().rollupMaxSlice()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.stats().rollupMaxRun()).isEqualTo(Duration.ofSeconds(60));

        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.metrics.gauge-refresh", "PT0S")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("metrics.gauge-refresh");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.stats.rollup-max-slice", "PT2H")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("stats.rollup-max-slice");
        assertThatThrownBy(() -> bind(Map.of("horus.bpm.processes.stats.rollup-lag", "PT0S")))
                .isInstanceOf(BindException.class).rootCause().hasMessageContaining("stats.rollup-lag");
    }

    private static ProcessEngineProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("horus.bpm.processes", ProcessEngineProperties.class);
    }
}
