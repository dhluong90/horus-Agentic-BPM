package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.CompiledCache;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import tools.jackson.databind.JsonNode;

/**
 * Listens on {@code horus_rle_cache} and evicts the compiled entry a publication names (ADR-0009). It holds one
 * session connection from the host's pool for its lifetime, so it is off behind a transaction-mode pooler
 * ({@code cache.listen-notify=false}), where the epoch check alone keeps the bound.
 *
 * <p>When the connection is lost, the listener logs one warning, retries every {@code reconnectDelay} without
 * logging again, and logs one line when it listens again. Notifications sent while it was away are not replayed by
 * PostgreSQL; the epoch check catches them within {@code cache.epoch-check-interval}, and the listener runs one
 * check itself on every reconnect. A malformed payload is ignored. Runs on one daemon thread.
 */
public final class CacheNotificationListener {

    private static final System.Logger LOG = System.getLogger(CacheNotificationListener.class.getName());
    private static final int POLL_MILLIS = 500;

    private final DataSource dataSource;
    private final CompiledCache cache;
    private final Duration reconnectDelay;
    private volatile boolean running;
    private volatile boolean listening;
    private volatile Connection connection;
    private volatile boolean stopped;
    private Thread thread;

    /**
     * Creates the listener; {@link #start()} opens the connection.
     *
     * @param dataSource the host's data source; must hand out direct PostgreSQL sessions
     * @param cache the compiled cache of this replica
     * @param reconnectDelay the pause between reconnection attempts
     */
    public CacheNotificationListener(DataSource dataSource, CompiledCache cache, Duration reconnectDelay) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
        this.reconnectDelay = Objects.requireNonNull(reconnectDelay, "reconnectDelay must not be null");
    }

    /** Starts listening on a daemon thread; a second call does nothing. */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "horus-rle-cache-listener");
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops listening and returns the connection to the pool. */
    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            closeQuietly(connection);
            try {
                thread.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    /**
     * Tells whether the listener holds a connection with {@code LISTEN} done.
     *
     * @return true while listening
     */
    public boolean listening() {
        return listening;
    }

    /**
     * Tells whether the listener stopped after a {@link VirtualMachineError}.
     *
     * @return true once stopped for good
     */
    public boolean stopped() {
        return stopped;
    }

    private void run() {
        boolean lost = false;
        while (running) {
            try (Connection c = dataSource.getConnection()) {
                connection = c;
                c.setAutoCommit(true);
                try (Statement statement = c.createStatement()) {
                    statement.execute("LISTEN " + JdbcRuleStore.CACHE_CHANNEL);
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                listening = true;
                if (lost) {
                    LOG.log(System.Logger.Level.INFO, "Rule Engine cache listener reconnected and listens on "
                            + JdbcRuleStore.CACHE_CHANNEL + " again");
                    lost = false;
                    checkQuietly();
                }
                while (running) {
                    PGNotification[] notifications = pg.getNotifications(POLL_MILLIS);
                    if (notifications != null) {
                        for (PGNotification notification : notifications) {
                            handle(notification.getParameter());
                        }
                    }
                }
            } catch (VirtualMachineError fatal) {
                // JVM-fatal: stop for good and let health report DOWN, so the orchestrator restarts the host.
                listening = false;
                stopped = true;
                running = false;
                LOG.log(System.Logger.Level.ERROR, "Rule Engine cache listener stopped after a JVM error", fatal);
                return;
            } catch (Throwable e) {
                // Any other failure, an Error from a library included, never ends the loop: log once, reconnect.
                listening = false;
                if (!running) {
                    return;
                }
                if (!lost) {
                    LOG.log(System.Logger.Level.WARNING, "Rule Engine cache listener lost its connection; retrying every "
                            + reconnectDelay + ", and the epoch check keeps replicas within cache.epoch-check-interval "
                            + "meanwhile: " + e);
                    lost = true;
                }
                pause();
            } finally {
                listening = false;
                connection = null;
            }
        }
    }

    private void handle(String payload) {
        try {
            JsonNode node = EngineValues.read(payload);
            JsonNode tenant = node.get("tenantId");
            JsonNode ruleSet = node.get("ruleSet");
            JsonNode epoch = node.get("epoch");
            if (tenant != null && tenant.isString() && ruleSet != null && ruleSet.isString() && epoch != null
                    && epoch.isIntegralNumber()) {
                cache.evict(tenant.stringValue(), ruleSet.stringValue(), epoch.longValue());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rule Engine ignored a malformed cache notification", e);
        }
    }

    private void checkQuietly() {
        try {
            cache.checkEpochs();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "Rule Engine epoch check after reconnect failed; the schedule retries", e);
        }
    }

    private void pause() {
        try {
            Thread.sleep(reconnectDelay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // closing on stop; nothing to report
            }
        }
    }
}
