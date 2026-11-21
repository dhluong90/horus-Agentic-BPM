package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Keeps the monthly partitions of the decision log ahead of the host clock while the host runs (RLE-017,
 * TR-OPS-04). {@link #runOnce()} creates every missing month up to {@code now + decision-log.partitions-ahead};
 * {@link RuleEngineSchemaInitializer} calls it at start-up and then {@link #start()}s a daemon thread that repeats
 * it daily. Replicas serialise on the advisory lock of {@link DecisionLogPartitions}; a run that waits longer than
 * the 5 s lock timeout logs a warning and the next run retries, so no run fails the host (TR-OPS-05).
 *
 * <p>When the host set {@code decision-log.retention}, the same run then drops the monthly partitions that lie wholly
 * before the clock minus the retention ({@link DecisionLogPartitions#dropExpired}), records each drop in
 * {@code rle_decision_log_drop} and logs it at WARN with its counts. Without a retention nothing is dropped. A host may
 * replace this bean.
 */
public final class RuleEnginePartitionMaintenance implements DisposableBean, RuleEngineBackgroundJob {

    private static final Log LOG = LogFactory.getLog(RuleEnginePartitionMaintenance.class);

    private final DecisionLogPartitions partitions;
    private final Clock clock;
    private final Duration ahead;
    private final Duration interval;
    private final Period retention;
    private ScheduledExecutorService scheduler;
    private volatile Instant lastSuccess;
    private volatile boolean stopped;

    RuleEnginePartitionMaintenance(DecisionLogPartitions partitions, Clock clock, Duration ahead) {
        this(partitions, clock, ahead, EngineDefaults.PARTITION_MAINTENANCE_INTERVAL);
    }

    RuleEnginePartitionMaintenance(DecisionLogPartitions partitions, Clock clock, Duration ahead, Duration interval) {
        this(partitions, clock, ahead, interval, null);
    }

    /**
     * Creates the job.
     *
     * @param retention {@code decision-log.retention}, or null when the host set none: then nothing is dropped
     */
    RuleEnginePartitionMaintenance(DecisionLogPartitions partitions, Clock clock, Duration ahead, Duration interval,
            Period retention) {
        this.partitions = Objects.requireNonNull(partitions, "partitions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ahead = Objects.requireNonNull(ahead, "ahead must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
        this.retention = retention;
    }

    /**
     * Creates the missing months now, on the calling thread, then drops the expired ones when a retention is set.
     *
     * @return the partitions created
     * @throws RuntimeException when the database refuses; start-up reports it, the scheduled run logs it
     */
    List<String> runOnce() {
        List<String> created = partitions.ensureAhead(clock.instant(), ahead);
        if (!created.isEmpty()) {
            LOG.info("Rule Engine created decision log partitions " + created);
        }
        if (retention != null) {
            for (DecisionLogPartitions.Dropped dropped : partitions.dropExpired(clock.instant(), retention)) {
                LOG.warn("Rule Engine dropped decision log partition " + dropped.partition() + " [" + dropped.from()
                        + ", " + dropped.to() + ") under horus.bpm.rules.decision-log.retention=" + retention + ": "
                        + dropped.total() + " decisions " + dropped.decisionsByTenant()
                        + ", recorded in rle_decision_log_drop");
            }
        }
        lastSuccess = clock.instant();
        return created;
    }

    /** Starts the daily run; a second call does nothing. */
    synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "horus-rle-partitions");
            thread.setDaemon(true);
            return thread;
        });
        long period = interval.toMillis();
        scheduler.scheduleWithFixedDelay(this::runQuietly, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * One scheduled iteration. The boundary catches every {@code Throwable}, so an {@code Error} from a library
     * (for example {@code NoClassDefFoundError}) never ends the loop silently: it is logged with the months the run
     * was creating and the next run retries. A {@link VirtualMachineError} is logged, the loop stops and the health
     * contributor reports {@code DOWN}, so the orchestrator restarts the host rather than it running on degraded.
     */
    void runQuietly() {
        if (stopped) {
            return;
        }
        try {
            runOnce();
        } catch (VirtualMachineError fatal) {
            stopped = true;
            LOG.fatal("Rule Engine stopped decision log partition maintenance after a JVM error; health is DOWN "
                    + "until the host restarts", fatal);
            ScheduledExecutorService running = scheduler;
            if (running != null) {
                running.shutdown();
            }
        } catch (Throwable e) {
            LOG.warn("Rule Engine could not create decision log partitions up to " + ahead + " ahead of "
                    + clock.instant() + (retention == null ? "" : " or drop those older than " + retention)
                    + "; the next run in " + interval + " retries", e);
        }
    }

    @Override
    public String jobName() {
        return "partitionMaintenance";
    }

    @Override
    public Instant lastSuccess() {
        return lastSuccess;
    }

    @Override
    public boolean stopped() {
        return stopped;
    }

    @Override
    public synchronized void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
