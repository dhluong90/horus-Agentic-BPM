package io.github.dhluong90.bpm.rules.autoconfigure;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Runs the lifecycle sweep (DESIGN section 3.3.2, RLE-027) every {@code horus.bpm.rules.lifecycle.sweep-interval} on a
 * daemon thread of its own, on every replica. Each run is one ordinary transaction with no advisory lock, so replicas
 * may run at the same moment and each superseded version is still retired once. Started by
 * {@link RuleEngineSchemaInitializer} once the schema is current; a failed run is logged and the next one tries again.
 * A host may replace this bean, for example to run the sweep from its own scheduler.
 */
public final class RuleEngineLifecycleSweeper implements DisposableBean {

    private static final Log LOG = LogFactory.getLog(RuleEngineLifecycleSweeper.class);

    private final IntSupplier sweep;
    private final Duration interval;
    private ScheduledExecutorService scheduler;

    RuleEngineLifecycleSweeper(IntSupplier sweep, Duration interval) {
        this.sweep = Objects.requireNonNull(sweep, "sweep must not be null");
        this.interval = Objects.requireNonNull(interval, "interval must not be null");
    }

    /** Starts the sweep; the first run is one interval after start-up. Calling it again does nothing. */
    synchronized void start() {
        if (scheduler == null) {
            scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "horus-rle-lifecycle-sweep");
                thread.setDaemon(true);
                return thread;
            });
            long period = interval.toMillis();
            scheduler.scheduleWithFixedDelay(this::runQuietly, period, period, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Runs one sweep now and logs what it retired.
     *
     * @return the number of versions this replica retired
     */
    int runOnce() {
        int retired = sweep.getAsInt();
        if (retired > 0) {
            LOG.info("Rule Engine lifecycle sweep retired " + retired + " superseded version(s)");
        }
        return retired;
    }

    private void runQuietly() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            // Status only: selection follows the effective date, so no decision waits for the next run.
            LOG.warn("Rule Engine lifecycle sweep failed; it runs again in " + interval, e);
        }
    }

    /**
     * Waits until the sweep thread has ended after {@link #destroy()}; true at once when the sweep never started.
     *
     * @param timeout the longest wait
     * @return true when no run can start any more
     * @throws InterruptedException when interrupted while waiting
     */
    boolean awaitStopped(Duration timeout) throws InterruptedException {
        ScheduledExecutorService current;
        synchronized (this) {
            current = scheduler;
        }
        return current == null
                || current.isShutdown() && current.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
