package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.EngineMeters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Binds the evaluation and retirement meters of DESIGN section 4.8 (TR-OBS-02) to the host's meter registry:
 * <ul>
 *   <li>timer {@code horus.rle.evaluations} (Prometheus {@code horus_rle_evaluations_seconds_count} and
 *       {@code _sum}), tag {@code outcome} ({@code matched}, {@code no_match}, {@code failed}, {@code refused});</li>
 *   <li>counter {@code horus.rle.evaluation.errors} (Prometheus {@code horus_rle_evaluation_errors_total}), tag
 *       {@code code}, the engine error code of a failed or refused call, for example {@code RULE_SET_NOT_FOUND};</li>
 *   <li>counter {@code horus.rle.lifecycle.retired} (Prometheus {@code horus_rle_lifecycle_retired_total}), tag
 *       {@code reason} ({@code SUPERSEDED}, {@code MANUAL}), counted after commit.</li>
 * </ul>
 * No tag carries a tenant, a rule set, a caller or an input value. Percentile histograms are the host's choice, for
 * example {@code management.metrics.distribution.percentiles-histogram.horus.rle.evaluations=true}.
 */
final class RuleEngineEvaluationMetrics implements MeterBinder {

    static final String EVALUATIONS = "horus.rle.evaluations";
    static final String ERRORS = "horus.rle.evaluation.errors";
    static final String RETIRED = "horus.rle.lifecycle.retired";

    private final EngineMeters meters;

    RuleEngineEvaluationMetrics(EngineMeters meters) {
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Map<EngineMeters.Outcome, Timer> timers = new EnumMap<>(EngineMeters.Outcome.class);
        for (EngineMeters.Outcome outcome : EngineMeters.Outcome.values()) {
            timers.put(outcome, Timer.builder(EVALUATIONS)
                    .description("Rule Engine evaluation calls and their duration, including the decision log write")
                    .tag("outcome", outcome.tag())
                    .register(registry));
        }
        Map<EngineMeters.RetireReason, Counter> retired = new EnumMap<>(EngineMeters.RetireReason.class);
        for (EngineMeters.RetireReason reason : EngineMeters.RetireReason.values()) {
            retired.put(reason, Counter.builder(RETIRED)
                    .description("Rule set versions retired, by reason")
                    .tag("reason", reason.name())
                    .register(registry));
        }
        meters.bind(new EngineMeters.Recorder() {
            @Override
            public void evaluation(EngineMeters.Outcome outcome, String code, long nanos) {
                timers.get(outcome).record(nanos, TimeUnit.NANOSECONDS);
                if (code != null) {
                    // The registry keeps one counter per code; the codes are the engine's fixed set (EngineMeters).
                    Counter.builder(ERRORS)
                            .description("Rule Engine evaluation calls that failed or were refused, by error code")
                            .tag("code", code)
                            .register(registry)
                            .increment();
                }
            }

            @Override
            public void retired(EngineMeters.RetireReason reason, int count) {
                retired.get(reason).increment(count);
            }
        });
    }
}
