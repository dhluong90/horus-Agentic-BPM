package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.service.CompiledCache;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Keeps one replica's compiled cache coherent with the others (ADR-0009, RLE-010, TR-ASY-04): the epoch check every
 * {@code cache.epoch-check-interval} is the staleness bound everywhere, and, with {@code cache.listen-notify}, the
 * notification listener makes the common case sub-second. Behind a transaction-mode pooler the listener stays off
 * and no session connection is opened. Both run on daemon threads of their own; {@link #stop()} ends them.
 */
public final class CacheCoherence {

    private static final System.Logger LOG = System.getLogger(CacheCoherence.class.getName());

    /** Pause between reconnection attempts of the listener. */
    static final Duration RECONNECT_DELAY = Duration.ofSeconds(1);

    private final CompiledCache cache;
    private final Duration checkInterval;
    private final CacheNotificationListener listener;
    private ScheduledExecutorService scheduler;

    /**
     * Creates the coherence of one replica.
     *
     * @param dataSource the host's data source
     * @param cache the replica's compiled cache
     * @param checkInterval {@code cache.epoch-check-interval}
     * @param listenNotify {@code cache.listen-notify}: hold one session connection for {@code LISTEN}
     */
    public CacheCoherence(DataSource dataSource, CompiledCache cache, Duration checkInterval, boolean listenNotify) {
        this(dataSource, cache, checkInterval, listenNotify, RECONNECT_DELAY);
    }

    CacheCoherence(DataSource dataSource, CompiledCache cache, Duration checkInterval, boolean listenNotify,
            Duration reconnectDelay) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.checkInterval = Objects.requireNonNull(checkInterval, "checkInterval must not be null");
        if (checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be positive, was " + checkInterval);
        }
        this.listener = listenNotify ? new CacheNotificationListener(dataSource, cache, reconnectDelay) : null;
    }

    /** Starts the epoch check and, when configured, the listener; a second call does nothing. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "horus-rle-cache-epoch");
            thread.setDaemon(true);
            return thread;
        });
        long period = checkInterval.toMillis();
        // Fixed rate: a check starts every interval whatever the previous one took, so the bound is the interval.
        scheduler.scheduleAtFixedRate(this::checkQuietly, period, period, TimeUnit.MILLISECONDS);
        if (listener != null) {
            listener.start();
        }
    }

    /** Stops both; the listener's connection goes back to the pool. */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (listener != null) {
            listener.stop();
        }
    }

    /**
     * Tells whether the listener currently holds its session with {@code LISTEN} done.
     *
     * @return false when {@code cache.listen-notify=false} or while reconnecting
     */
    public boolean listening() {
        return listener != null && listener.listening();
    }

    private void checkQuietly() {
        try {
            cache.checkEpochs();
        } catch (RuntimeException e) {
            // The next run retries; requests are served from the cache meanwhile, stale for at most one more interval.
            LOG.log(System.Logger.Level.WARNING, "Rule Engine epoch check failed; retrying in " + checkInterval, e);
        }
    }
}
