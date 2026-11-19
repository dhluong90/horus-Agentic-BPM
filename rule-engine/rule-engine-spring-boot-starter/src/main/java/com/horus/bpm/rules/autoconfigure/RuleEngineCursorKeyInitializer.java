package com.horus.bpm.rules.autoconfigure;

import com.horus.bpm.rules.internal.service.CursorKeys;
import com.horus.bpm.rules.internal.service.EngineDefaults;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Loads the cursor key at start-up and keeps it fresh off the request path (RLE-039). {@link #start()} runs once,
 * from {@link RuleEngineSchemaInitializer} after the migrations: one short transaction inserts the key if absent
 * and reads it. A daemon thread then re-reads it every {@link EngineDefaults#CURSOR_KEY_REFRESH} in a read-only
 * transaction. No request reads or writes the key. A host may replace this bean.
 */
public final class RuleEngineCursorKeyInitializer implements DisposableBean {

    private static final Log LOG = LogFactory.getLog(RuleEngineCursorKeyInitializer.class);

    private final Runnable load;
    private final Runnable refresh;
    private final java.util.function.Consumer<String> notReady;
    private ScheduledExecutorService refresher;

    RuleEngineCursorKeyInitializer(CursorKeys keys) {
        this(Objects.requireNonNull(keys, "keys must not be null")::load, keys::refresh, keys::notReady);
    }

    RuleEngineCursorKeyInitializer(Runnable load, Runnable refresh) {
        this(load, refresh, state -> { });
    }

    RuleEngineCursorKeyInitializer(Runnable load, Runnable refresh, java.util.function.Consumer<String> notReady) {
        this.load = Objects.requireNonNull(load, "load must not be null");
        this.refresh = Objects.requireNonNull(refresh, "refresh must not be null");
        this.notReady = Objects.requireNonNull(notReady, "notReady must not be null");
    }

    /**
     * Records that the key was not loaded because the schema is not current, so list calls answer
     * {@code 503 SCHEMA_NOT_READY} naming the state.
     *
     * @param schemaState the schema state
     */
    void notReady(String schemaState) {
        notReady.accept(schemaState);
    }

    /**
     * Loads the key and starts the refresh.
     *
     * @throws IllegalStateException when the key cannot be created or read; start-up fails
     */
    synchronized void start() {
        load.run();
        if (refresher == null) {
            long period = EngineDefaults.CURSOR_KEY_REFRESH.toMillis();
            refresher = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "horus-rle-cursor-key");
                thread.setDaemon(true);
                return thread;
            });
            refresher.scheduleWithFixedDelay(this::refreshQuietly, period, period, TimeUnit.MILLISECONDS);
        }
    }

    private void refreshQuietly() {
        try {
            refresh.run();
        } catch (RuntimeException e) {
            // Keep the key in memory and try again at the next period; requests are unaffected.
            LOG.warn("Rule Engine could not re-read its cursor key; keeping the current one", e);
        }
    }

    @Override
    public synchronized void destroy() {
        if (refresher != null) {
            refresher.shutdownNow();
        }
    }
}
