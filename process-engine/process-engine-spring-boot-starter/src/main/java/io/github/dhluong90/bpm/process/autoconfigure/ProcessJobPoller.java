package io.github.dhluong90.bpm.process.autoconfigure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs one job poll at a time on one daemon thread per replica: again at once while polls fire jobs, otherwise after
 * {@code jobs.poll-interval}. Starts after the context is ready and stops before the data source closes.
 *
 * <p>The loop never ends silently. Any {@link Throwable} from one poll, an {@link Error} included, is logged once for
 * that poll, with the replica and the exception class and no payload, and the next poll runs after the interval. A
 * {@link VirtualMachineError} ({@code OutOfMemoryError}, {@code StackOverflowError}) is logged, marks the job health
 * {@code DOWN} and stops the loop, so the orchestrator restarts the host instead of it limping on. Claims of a stopped
 * replica are taken over by the others after their lease. {@link #health()} reports the state and the time of the last
 * successful poll.
 */
final class ProcessJobPoller implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(ProcessJobPoller.class);

    /**
     * Health of the poller.
     *
     * @param up false after a JVM-fatal error stopped the loop
     * @param details replica id, last successful poll, and the fatal error class when down
     */
    record Health(boolean up, Map<String, Object> details) {
    }

    private final IntSupplier poll;
    private final String replicaId;
    private final Duration interval;
    private final Clock clock;
    private volatile ScheduledExecutorService scheduler;
    private volatile Instant lastSuccess;
    private volatile String fatal;

    ProcessJobPoller(IntSupplier poll, String replicaId, Duration interval, Clock clock) {
        this.poll = poll;
        this.replicaId = replicaId;
        this.interval = interval;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null || fatal != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "horus-bpm-jobs");
            t.setDaemon(true);
            return t;
        });
        scheduler.schedule(this::iteration, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Process Engine job poller started as replica " + replicaId);
    }

    /** One loop iteration: one poll, then the next iteration is scheduled unless a JVM-fatal error occurred. */
    void iteration() {
        int fired = 0;
        try {
            fired = poll.getAsInt();
            lastSuccess = clock.instant();
        } catch (VirtualMachineError e) {
            fatal = e.getClass().getName();
            LOG.error("Process Engine job poller of replica " + replicaId + " stopped on " + fatal
                    + "; job health is DOWN so the host is restarted");
            shutdown();
            return;
        } catch (Throwable e) {
            // One line per failed poll: the class only, never a message that could carry job data.
            LOG.warn("Process Engine job poll of replica " + replicaId + " failed with " + e.getClass().getName()
                    + "; the next poll runs after " + interval);
            fired = 0;
        }
        ScheduledExecutorService current = scheduler;
        if (current != null && !current.isShutdown()) {
            current.schedule(this::iteration, fired > 0 ? 0 : interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** @return UP with the last successful poll, or DOWN after a JVM-fatal error */
    Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("replicaId", replicaId);
        details.put("lastSuccessfulPoll", lastSuccess == null ? "none yet" : lastSuccess.toString());
        if (fatal != null) {
            details.put("stoppedBy", fatal);
        }
        return new Health(fatal == null, details);
    }

    @Override
    public synchronized void stop() {
        shutdown();
    }

    private synchronized void shutdown() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
            if (!Thread.currentThread().getName().equals("horus-bpm-jobs")) {
                try {
                    current.awaitTermination(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
