package io.github.dhluong90.bpm.process.autoconfigure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the engine's periodic background work on one daemon thread per replica: the hourly node statistics rollup
 * and the gauge refresh. Tasks of this class never run inside a step transaction and never in a request thread, and
 * a failing task is logged once per minute with its name and the exception class only, never a message that could
 * carry data, and runs again at its next interval. Two replicas may both run the rollup: the rollup itself lets one
 * writer take each slice.
 */
final class ProcessMaintenanceScheduler implements SmartLifecycle {

    private static final Log LOG = LogFactory.getLog(ProcessMaintenanceScheduler.class);
    private static final Duration LOG_EVERY = Duration.ofMinutes(1);

    /** One periodic task. */
    record Task(String name, Duration interval, Runnable body) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private final Clock clock;
    private volatile ScheduledExecutorService executor;
    private volatile Instant lastLogged = Instant.MIN;

    ProcessMaintenanceScheduler(Clock clock) {
        this.clock = clock;
    }

    ProcessMaintenanceScheduler add(String name, Duration interval, Runnable body) {
        return add(new Task(name, interval, body));
    }

    ProcessMaintenanceScheduler add(Task task) {
        tasks.add(task);
        return this;
    }

    boolean isEmpty() {
        return tasks.isEmpty();
    }

    /** Runs one task once, as the scheduler does; used by tests and by the start-up pass. */
    void runOnce(Task task) {
        try {
            task.body().run();
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError error) {
                throw error;
            }
            Instant now = clock.instant();
            if (Duration.between(lastLogged, now).compareTo(LOG_EVERY) >= 0) {
                lastLogged = now;
                LOG.warn("Process Engine task " + task.name() + " failed with " + e.getClass().getName()
                        + "; it runs again after " + task.interval());
            }
        }
    }

    @Override
    public synchronized void start() {
        if (executor != null || tasks.isEmpty()) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "horus-bpm-maintenance");
            t.setDaemon(true);
            return t;
        });
        for (Task task : tasks) {
            executor.scheduleWithFixedDelay(() -> runOnce(task), 1_000, task.interval().toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService current = executor;
        executor = null;
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
        return executor != null;
    }
}
