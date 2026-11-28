package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.store.GaugeStore;
import io.github.dhluong90.bpm.process.spi.ProcessMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The engine's meters on the host's {@code MeterRegistry} (PRE-084, DESIGN section 6.4): the {@link ProcessMetrics}
 * the engine calls after commit, and the gauges that {@link #refreshGauges()} fills from one bounded query set.
 *
 * <p>Every tag value is checked against a fixed set before it is used, and anything else is recorded as {@code OTHER},
 * so the number of series is bounded by this class and never by traffic: step kinds, outcomes, job kinds and engine
 * error codes only. No tag holds a tenant, a business key, an instance id, a user or a variable, and a host's
 * business code is counted as {@code UNHANDLED_BUSINESS_ERROR}. {@code definitionKey} is a tag of the instance
 * counters only when the host sets {@code metrics.definition-key-tag}.
 */
final class ProcessEngineMetrics implements MeterBinder, ProcessMetrics {

    static final String INSTANCES_STARTED = "horus.pre.instances.started";
    static final String INSTANCES_COMPLETED = "horus.pre.instances.completed";
    static final String INSTANCES_FAILED = "horus.pre.instances.failed";
    static final String INSTANCES_ACTIVE = "horus.pre.instances.active";
    static final String INCIDENTS_RAISED = "horus.pre.incidents.raised";
    static final String INCIDENTS_OPEN = "horus.pre.incidents.open";
    static final String TIMER_LAG = "horus.pre.timer.lag";
    static final String STEP_DURATION = "horus.pre.step.duration";
    static final String ASYNC_ATTEMPTS = "horus.pre.async.attempts";
    static final String JOBS_OLDEST_AGE = "horus.pre.jobs.due.oldest.age";
    static final String MESSAGES_REFUSED = "horus.pre.messages.refused";

    /** Tag value for anything outside a fixed set. */
    static final String OTHER = "OTHER";

    static final Set<String> STEP_KINDS = Set.of("START", "COMPLETE_TASK", "JOB_DUE", "ASYNC_RESULT", "CANCEL", OTHER);
    static final Set<String> STEP_OUTCOMES = Set.of("OK", "FAILED");
    static final Set<String> ATTEMPT_OUTCOMES = Set.of("ACCEPTED", "REFUSED", "FAILED", "DISCARDED", "DUPLICATE");
    static final Set<String> JOB_KINDS = Set.copyOf(io.github.dhluong90.bpm.process.postgres.JdbcGaugeStore.JOB_KINDS);
    static final Set<String> MESSAGE_REASONS = Set.of("NOT_CORRELATED", "AMBIGUOUS");
    static final Set<String> ASYNC_KINDS = Set.of("ASYNC_SERVICE", "ASYNC_AGENT");
    /** Engine error codes of incidents; a code outside the set, a host's own included, is {@code OTHER}. */
    static final Set<String> ERROR_CODES = Set.of("ADAPTER_ERROR", "ADAPTER_NOT_REGISTERED", "ADAPTER_NULL_RESULT",
            "ADAPTER_TIMEOUT", "AGENT_LABELS_INVALID", "AGENT_MEMORY_INVALID", "AGENT_MEMORY_MASKED_FIELD",
            "AGENT_MEMORY_MISSING", "AGENT_MEMORY_TOO_LARGE", "AGENT_NOT_REGISTERED", "AGENT_SCHEMA_INVALID",
            "AGENT_TIMEOUT", "GUARD_BUDGET_EXCEEDED", "GUARD_NULL_VERDICT", "GUARD_SPEC_INVALID",
            "GUARDRAIL_CONTRACT_INVALID", "GUARDRAIL_EVALUATION_ERROR", "GUARDRAIL_VIOLATION", "MAX_ATTEMPTS_REACHED",
            "RUNNER_NOT_REGISTERED", "SCHEMA_VALIDATOR_NULL", "STEP_FAILED", "STEP_LIMIT", "STEP_ROUTED_TO_REVIEW",
            "STEP_RUN_FAILED", "UNHANDLED_BUSINESS_ERROR");

    private final GaugeStore gauges;
    private final Clock clock;
    private final boolean definitionKeyTag;
    private final AtomicLong activeInstances = new AtomicLong();
    private final AtomicLong openIncidents = new AtomicLong();
    private final Map<String, AtomicLong> oldestDueAgeSeconds = new ConcurrentHashMap<>();
    private final AtomicReference<MeterRegistry> registry = new AtomicReference<>();

    ProcessEngineMetrics(GaugeStore gauges, Clock clock, boolean definitionKeyTag) {
        this.gauges = Objects.requireNonNull(gauges);
        this.clock = Objects.requireNonNull(clock);
        this.definitionKeyTag = definitionKeyTag;
    }

    @Override
    public void bindTo(MeterRegistry meters) {
        registry.set(meters);
        Gauge.builder(INSTANCES_ACTIVE, activeInstances, AtomicLong::get)
                .description("Instances in status RUNNING, all tenants together; refreshed every metrics.gauge-refresh")
                .register(meters);
        Gauge.builder(INCIDENTS_OPEN, openIncidents, AtomicLong::get)
                .description("Open incidents, all tenants together; refreshed every metrics.gauge-refresh")
                .register(meters);
        for (String kind : JOB_KINDS) {
            ageOf(kind, meters);
        }
    }

    private AtomicLong ageOf(String kind, MeterRegistry meters) {
        return oldestDueAgeSeconds.computeIfAbsent(kind, k -> {
            AtomicLong age = new AtomicLong();
            Gauge.builder(JOBS_OLDEST_AGE, age, AtomicLong::get).baseUnit("seconds")
                    .description("Age of the oldest due job of the kind, 0 when none is due")
                    .tag("kind", k).register(meters);
            return age;
        });
    }

    /** Reads the gauge figures with the bounded queries of the gauge store. Called once per refresh interval. */
    void refreshGauges() {
        GaugeStore.Snapshot snapshot = gauges.read();
        activeInstances.set(snapshot.activeInstances());
        openIncidents.set(snapshot.openIncidents());
        long now = clock.instant().getEpochSecond();
        for (String kind : JOB_KINDS) {
            var due = snapshot.oldestDue().get(kind);
            AtomicLong age = oldestDueAgeSeconds.get(kind);
            if (age != null) {
                age.set(due == null ? 0 : Math.max(0, now - due.getEpochSecond()));
            }
        }
    }

    @Override
    public void instanceStarted(String definitionKey) {
        instanceCounter(INSTANCES_STARTED, "Instance starts that committed", definitionKey);
    }

    @Override
    public void instanceCompleted(String definitionKey) {
        instanceCounter(INSTANCES_COMPLETED, "Instances that completed", definitionKey);
    }

    @Override
    public void instanceFailed(String definitionKey) {
        instanceCounter(INSTANCES_FAILED, "Instances that ended FAILED", definitionKey);
    }

    private void instanceCounter(String name, String description, String definitionKey) {
        MeterRegistry meters = registry.get();
        if (meters == null) {
            return;
        }
        Tags tags = definitionKeyTag && definitionKey != null ? Tags.of("definitionKey", definitionKey) : Tags.empty();
        Counter.builder(name).description(description).tags(tags).register(meters).increment();
    }

    @Override
    public void incidentRaised(String errorCode) {
        MeterRegistry meters = registry.get();
        if (meters != null) {
            Counter.builder(INCIDENTS_RAISED).description("Incidents opened, by engine error code")
                    .tag("errorCode", pick(errorCode, ERROR_CODES)).register(meters).increment();
        }
    }

    @Override
    public void stepApplied(String stepKind, String outcome, Duration elapsed) {
        MeterRegistry meters = registry.get();
        if (meters != null) {
            Timer.builder(STEP_DURATION).description("Time the execution kernel spent on one engine command")
                    .tag("stepKind", pick(stepKind, STEP_KINDS)).tag("outcome", pick(outcome, STEP_OUTCOMES))
                    .register(meters).record(elapsed);
        }
    }

    @Override
    public void asyncAttempt(String kind, String outcome) {
        MeterRegistry meters = registry.get();
        if (meters != null) {
            Counter.builder(ASYNC_ATTEMPTS).description("Async attempt results handled, by job kind and outcome")
                    .tag("kind", pick(kind, ASYNC_KINDS)).tag("outcome", pick(outcome, ATTEMPT_OUTCOMES))
                    .register(meters).increment();
        }
    }

    @Override
    public void messageRefused(String reason) {
        MeterRegistry meters = registry.get();
        if (meters != null) {
            Counter.builder(MESSAGES_REFUSED).description("Message correlations refused, by reason")
                    .tag("reason", pick(reason, MESSAGE_REASONS)).register(meters).increment();
        }
    }

    @Override
    public void timerLag(Duration lag) {
        MeterRegistry meters = registry.get();
        if (meters != null) {
            Timer.builder(TIMER_LAG).description("How long after its due time a timer fired")
                    .register(meters).record(lag);
        }
    }

    private static String pick(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : OTHER;
    }
}
