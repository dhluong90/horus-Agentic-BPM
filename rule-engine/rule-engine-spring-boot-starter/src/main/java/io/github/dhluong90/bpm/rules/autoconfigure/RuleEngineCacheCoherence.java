package io.github.dhluong90.bpm.rules.autoconfigure;

import io.github.dhluong90.bpm.rules.postgres.CacheCoherence;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;

/**
 * Starts this replica's cache coherence when the context is ready and stops it on shutdown (ADR-0009): the epoch
 * check every {@code cache.epoch-check-interval} and, when {@code cache.listen-notify} is true, the listener on
 * {@code horus_rle_cache}. A host may replace this bean, for example to run the check on its own scheduler.
 */
public final class RuleEngineCacheCoherence implements SmartLifecycle, RuleEngineBackgroundJob {

    private final CacheCoherence coherence;
    private volatile boolean running;

    RuleEngineCacheCoherence(CacheCoherence coherence) {
        this.coherence = Objects.requireNonNull(coherence, "coherence must not be null");
    }

    @Override
    public void start() {
        coherence.start();
        running = true;
    }

    @Override
    public void stop() {
        coherence.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Tells whether the listener holds its session with {@code LISTEN} done.
     *
     * @return false when {@code cache.listen-notify=false} or while reconnecting
     */
    public boolean listening() {
        return coherence.listening();
    }

    @Override
    public String jobName() {
        return "cacheEpochCheck";
    }

    @Override
    public java.time.Instant lastSuccess() {
        return coherence.lastCheck();
    }

    @Override
    public boolean stopped() {
        return coherence.stopped();
    }
}
