package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.EventOutboxLag;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Binds the event outbox lag gauges to the host's meter registry: {@code horus.rle.outbox.pending}
 * (Prometheus {@code horus_rle_outbox_pending}, rows not yet {@code SENT}, capped at 100000) and
 * {@code horus.rle.outbox.oldest.age} (Prometheus {@code horus_rle_outbox_oldest_age_seconds}, 0 when nothing waits).
 * The engine writes the outbox and the host relays it, so the age is the lag of the host's relay. A failed read
 * reports {@code NaN}. No tag carries a tenant, an event or a payload.
 */
final class RuleEngineOutboxMetrics implements MeterBinder {

    static final String PENDING = "horus.rle.outbox.pending";
    static final String OLDEST_AGE = "horus.rle.outbox.oldest.age";

    private static final Log LOG = LogFactory.getLog(RuleEngineOutboxMetrics.class);

    private final EventOutboxLag lag;
    private final Clock clock;

    RuleEngineOutboxMetrics(EventOutboxLag lag, Clock clock) {
        this.lag = Objects.requireNonNull(lag, "lag must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(PENDING, this, RuleEngineOutboxMetrics::pending)
                .description("Event outbox rows not yet SENT by the host's relay, capped at 100000")
                .strongReference(true).register(registry);
        Gauge.builder(OLDEST_AGE, this, RuleEngineOutboxMetrics::oldestAge)
                .description("Age of the oldest event outbox row not yet SENT; 0 when the relay has caught up")
                .baseUnit("seconds").strongReference(true).register(registry);
    }

    private double pending() {
        try {
            return lag.pending();
        } catch (RuntimeException e) {
            LOG.debug("Rule Engine could not count the event outbox", e);
            return Double.NaN;
        }
    }

    private double oldestAge() {
        try {
            OptionalLong age = lag.oldestAgeSeconds(clock.instant());
            return age.isPresent() ? age.getAsLong() : 0;
        } catch (RuntimeException e) {
            LOG.debug("Rule Engine could not read the age of the event outbox", e);
            return Double.NaN;
        }
    }
}
