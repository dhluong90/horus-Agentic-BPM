package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Binds the decision log gauge {@code horus.rle.partition.default.rows} (Prometheus
 * {@code horus_rle_partition_default_rows}, DESIGN section 4.8) to the host's meter registry. The value is read
 * when the registry asks, so it reflects the default partition at that moment; it should be 0, and above 0 a row
 * fell outside every monthly partition. A failed read reports {@code NaN}. No tag carries a tenant or a rule set.
 */
final class RuleEngineMetrics implements MeterBinder {

    static final String DEFAULT_ROWS = "horus.rle.partition.default.rows";

    private static final Log LOG = LogFactory.getLog(RuleEngineMetrics.class);

    static final String QUEUE_DEPTH = "horus.rle.decision.log.queue.depth";
    static final String QUEUE_OLDEST_AGE = "horus.rle.decision.log.queue.oldest.age";
    static final String QUEUED = "horus.rle.decision.log.queued";
    static final String RELAYED = "horus.rle.decision.log.relayed";
    static final String REFUSED = "horus.rle.decision.log.refused";
    static final String RELAY_FAILED = "horus.rle.decision.log.relay.failed";
    /** Dead letters an operator still has to deal with; the alert fires above 0 (RLE-235). */
    static final String QUEUE_DEAD = "horus.rle.decision.log.queue.dead";
    static final String DEAD_LETTERED = "horus.rle.decision.log.dead.lettered";

    private final DecisionLogPartitions partitions;
    private final io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog asyncLog;

    RuleEngineMetrics(DecisionLogPartitions partitions) {
        this(partitions, null);
    }

    RuleEngineMetrics(DecisionLogPartitions partitions, io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog asyncLog) {
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.asyncLog = asyncLog;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(DEFAULT_ROWS, this, RuleEngineMetrics::defaultRows)
                .description("Rows of rle_decision outside every monthly partition; should be 0")
                // A gauge holds its state weakly by default: once nothing else references this binder, a garbage
                // collection turns the gauge into NaN. The registry keeps it, whoever created it.
                .strongReference(true)
                .register(registry);
        if (asyncLog != null) {
            // RLE-234: only in decision-log.mode async. Alert on depth near max-backlog and on a growing oldest age.
            Gauge.builder(QUEUE_DEPTH, asyncLog, io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::depth)
                    .description("Decisions queued for the decision log at the last count; evaluations are refused "
                            + "above decision-log.async.max-backlog").strongReference(true).register(registry);
            Gauge.builder(QUEUE_OLDEST_AGE, asyncLog, RuleEngineMetrics::oldestQueuedSeconds)
                    .baseUnit("seconds").description("Age of the oldest queued decision at the last relay run; "
                            + "0 when the queue was empty").strongReference(true).register(registry);
            io.micrometer.core.instrument.FunctionCounter.builder(QUEUED, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::appendedCount)
                    .description("Decisions this replica queued").register(registry);
            io.micrometer.core.instrument.FunctionCounter.builder(RELAYED, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::relayedCount)
                    .description("Decisions this replica moved into the decision log").register(registry);
            io.micrometer.core.instrument.FunctionCounter.builder(REFUSED, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::refusedCount)
                    .description("Evaluations refused with DECISION_LOG_BACKLOG").register(registry);
            io.micrometer.core.instrument.FunctionCounter.builder(RELAY_FAILED, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::relayFailureCount)
                    .description("Failed attempts and failed runs of the relay").register(registry);
            Gauge.builder(QUEUE_DEAD, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::deadLetterDepth)
                    .description("Decisions the relay gave up on and dead-lettered, not yet retried or resolved by an "
                            + "operator; they block nothing, and the alert fires above 0").strongReference(true)
                    .register(registry);
            io.micrometer.core.instrument.FunctionCounter.builder(DEAD_LETTERED, asyncLog,
                    io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog::deadLetteredCount)
                    .description("Decisions this replica dead-lettered").register(registry);
        }
    }

    private static double oldestQueuedSeconds(io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog log) {
        java.time.Instant oldest = log.oldestQueuedAt();
        return oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest, java.time.Instant.now()).toSeconds());
    }

    private double defaultRows() {
        try {
            return partitions.defaultRows();
        } catch (RuntimeException e) {
            LOG.debug("Rule Engine could not count the default partition", e);
            return Double.NaN;
        }
    }
}
