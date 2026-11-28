package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.AsyncDecisionLog;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Moves queued decisions into the decision log every {@code decision-log.async.poll-interval}, on a daemon thread of
 * its own, on every replica, when the host runs {@code decision-log.mode: async} (RLE-230). Replicas may run together:
 * a batch locks its rows with {@code SKIP LOCKED}. It starts when the application context is ready, after the schema
 * is current, drains whatever is queued, and on shutdown moves what it can before it stops, so a clean stop leaves an
 * empty queue; a decision a stopped host leaves behind stays queued, durable, for the next run on any replica.
 * A failed run is logged and counted, and the next run tries again; a run that fails for ten polls in a row is
 * logged at ERROR. A host may replace this bean, for example to run the relay from its own scheduler.
 */
public final class RuleEngineDecisionLogRelay implements SmartLifecycle, RuleEngineBackgroundJob {

    private static final Log LOG = LogFactory.getLog(RuleEngineDecisionLogRelay.class);
    private static final int ESCALATE_AFTER_FAILURES = 10;

    private final AsyncDecisionLog log;
    private final Duration interval;
    private ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile int consecutiveFailures;

    RuleEngineDecisionLogRelay(AsyncDecisionLog log, Duration interval) {
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "horus-rle-decision-log-relay");
            thread.setDaemon(true);
            return thread;
        });
        long period = interval.toMillis();
        scheduler.scheduleWithFixedDelay(this::runQuietly, 0, period, TimeUnit.MILLISECONDS);
        running = true;
    }

    /**
     * Runs one relay now and logs what it moved.
     *
     * @return the number of decisions moved
     */
    int runOnce() {
        int moved = log.relayOnce();
        if (moved > 0 && LOG.isDebugEnabled()) {
            LOG.debug("Rule Engine moved " + moved + " queued decision(s) into the decision log");
        }
        consecutiveFailures = 0;
        return moved;
    }

    private void runQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            int failures = ++consecutiveFailures;
            if (failures >= ESCALATE_AFTER_FAILURES && failures % ESCALATE_AFTER_FAILURES == 0) {
                LOG.error("Rule Engine decision log relay failed " + failures + " times in a row; decisions stay "
                        + "queued and evaluations are refused above decision-log.async.max-backlog", e);
            } else {
                LOG.warn("Rule Engine decision log relay failed; it runs again in " + interval, e);
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler.shutdownNow();
            scheduler = null;
            try {
                log.relayOnce();
            } catch (RuntimeException e) {
                LOG.warn("Rule Engine could not drain the decision log queue at shutdown; the queued decisions stay "
                        + "durable for the next run", e);
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String jobName() {
        return "decisionLogRelay";
    }

    @Override
    public Instant lastSuccess() {
        return log.lastRelay();
    }

    @Override
    public boolean stopped() {
        return false;
    }
}
