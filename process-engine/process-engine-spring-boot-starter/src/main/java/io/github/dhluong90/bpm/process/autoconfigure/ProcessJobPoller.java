package io.github.dhluong90.bpm.process.autoconfigure;

import io.github.dhluong90.bpm.process.internal.service.JobExecutor;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs {@link JobExecutor#pollOnce()} on one daemon thread per replica: again at once while polls find due jobs,
 * otherwise after {@code jobs.poll-interval}. Starts after the context is ready and stops before the data source
 * closes. A poll that throws (database down) is logged and retried after the interval; claims of a stopped replica
 * are taken over by the others after their lease.
 */
final class ProcessJobPoller implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(ProcessJobPoller.class);

    private final JobExecutor executor;
    private final Duration interval;
    private volatile ScheduledExecutorService scheduler;

    ProcessJobPoller(JobExecutor executor, Duration interval) {
        this.executor = executor;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "horus-bpm-jobs");
            t.setDaemon(true);
            return t;
        });
        scheduler.schedule(this::poll, interval.toMillis(), TimeUnit.MILLISECONDS);
        LOG.info("Process Engine job poller started as replica " + executor.replicaId());
    }

    private void poll() {
        int fired = 0;
        try {
            fired = executor.pollOnce();
        } catch (RuntimeException e) {
            LOG.warn("Process Engine job poll failed: " + e.getClass().getName());
        }
        ScheduledExecutorService current = scheduler;
        if (current != null && !current.isShutdown()) {
            current.schedule(this::poll, fired > 0 ? 0 : interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
            try {
                current.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
