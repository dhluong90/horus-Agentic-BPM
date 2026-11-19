package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two replicas of the engine on one PostgreSQL database, each with its own connections, cache and coherence
 * (RLE-010, TR-ASY-04, ADR-0009): the notification path, the epoch check alone, and a listener that loses its
 * connection. Replica B evaluates every 100 ms; times are measured from the commit of replica A's approval.
 */
@Testcontainers
class CacheCoherencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    static final DecisionInput SILVER = DecisionInput.of(Map.of("tier", "SILVER", "txCount", 1));
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final List<CacheCoherence> started = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopReplicas() {
        started.forEach(CacheCoherence::stop);
    }

    @Test
    void RLE_010_the_notification_reaches_the_other_replica_within_one_second() throws Exception {
        // Given two replicas holding card-fee v1; B listens, and its epoch check is a minute away
        String database = freshDatabase();
        Replica a = replica(database, "replica-a", Duration.ofMinutes(1), false);
        Replica b = replica(database, "replica-b", Duration.ofMinutes(1), true);
        awaitListening(b);
        publish(a, cardFee(50000), 1);
        assertThat(b.evaluateVersion()).isEqualTo(1);

        // When A approves v2
        publish(a, cardFee(60000), 2);
        long committed = System.nanoTime();

        // Then B serves v2 within 1 s, through the notification alone
        long elapsed = awaitVersion(b, 2, Duration.ofSeconds(5)) - committed;
        assertThat(Duration.ofNanos(elapsed)).isLessThan(Duration.ofSeconds(1));
        assertThat(b.engines().cache().evictions(io.github.dhluong90.bpm.rules.internal.service.CompiledCache.Cause.NOTIFY))
                .isEqualTo(1);
    }

    @Test
    void TR_ASY_04_with_notifications_off_the_epoch_check_alone_refreshes_within_the_interval() throws Exception {
        // Given two replicas with cache.listen-notify=false and a 5 s epoch check
        String database = freshDatabase();
        Replica a = replica(database, "replica-a", Duration.ofSeconds(5), false);
        Replica b = replica(database, "replica-b", Duration.ofSeconds(5), false);
        publish(a, cardFee(50000), 1);
        assertThat(b.evaluateVersion()).isEqualTo(1);

        // When A approves v2
        publish(a, cardFee(60000), 2);
        long committed = System.nanoTime();

        // Then B serves v2 within the interval (plus one 100 ms evaluation step), and nobody listens
        long elapsed = awaitVersion(b, 2, Duration.ofSeconds(10)) - committed;
        assertThat(Duration.ofNanos(elapsed)).isLessThanOrEqualTo(Duration.ofMillis(5_100));
        assertThat(listeners(database)).isZero();
        assertThat(b.coherence().listening()).isFalse();
    }

    @Test
    void RLE_010_a_lost_listener_connection_still_refreshes_within_the_interval_and_logs_the_reconnect_once()
            throws Exception {
        // Given B listens with a 5 s epoch check and holds v1, and its log is captured
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Logger log = Logger.getLogger(CacheNotificationListener.class.getName());
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        log.addHandler(capture);
        try {
            String database = freshDatabase();
            Replica a = replica(database, "replica-a", Duration.ofSeconds(5), false);
            Replica b = replica(database, "replica-b", Duration.ofSeconds(5), true);
            awaitListening(b);
            publish(a, cardFee(50000), 1);
            assertThat(b.evaluateVersion()).isEqualTo(1);

            // When B's listening session is terminated and A approves v2 at once
            assertThat(terminateListeners(database)).isEqualTo(1);
            publish(a, cardFee(60000), 2);
            long committed = System.nanoTime();

            // Then B still serves v2 within 5 s, and the loss and the reconnect are logged once each
            long elapsed = awaitVersion(b, 2, Duration.ofSeconds(10)) - committed;
            assertThat(Duration.ofNanos(elapsed)).isLessThanOrEqualTo(Duration.ofMillis(5_100));
            awaitListening(b);
            assertThat(logged).filteredOn(r -> r.getMessage().contains("lost its connection")).hasSize(1);
            assertThat(logged).filteredOn(r -> r.getMessage().contains("reconnected")).hasSize(1);
        } finally {
            log.removeHandler(capture);
        }
    }

    /** One replica: its own data source (application name {@code name}), engine, cache and coherence. */
    record Replica(RuleEngines engines, CacheCoherence coherence, RuleEnginePostgresIT.Caller caller) {

        int evaluateVersion() {
            caller.as("bank-a", "teller-01");
            return engines.ruleEngine().evaluate("card-fee", SILVER).version();
        }
    }

    private Replica replica(String database, String name, Duration checkInterval, boolean listen) {
        DataSource db = dataSourceFor(database, name);
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.systemUTC(),
                EngineSettings.defaults());
        engines.cursorKeys().load();
        CacheCoherence coherence = new CacheCoherence(db, engines.cache(), checkInterval, listen);
        coherence.start();
        started.add(coherence);
        return new Replica(engines, coherence, caller);
    }

    private static void publish(Replica replica, byte[] yaml, int version) {
        replica.caller().as("bank-a", "alice");
        if (version == 1) {
            replica.engines().versionService().importRuleSet(ImportRequest.yaml(yaml));
        } else {
            replica.engines().versionService().importVersion("card-fee", ImportRequest.yaml(yaml));
        }
        replica.engines().versionService().requestPublish("card-fee", version, WriteOptions.none());
        replica.caller().as("bank-a", "bob");
        replica.engines().versionService().approve("card-fee", version, WriteOptions.none());
    }

    /** Evaluates on the replica every 100 ms until it serves the version; returns System.nanoTime() of that call. */
    private static long awaitVersion(Replica replica, int version, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long before = System.nanoTime();
            if (replica.evaluateVersion() == version) {
                return before;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("replica still served an older version after " + timeout);
    }

    private static void awaitListening(Replica replica) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!replica.coherence().listening()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("listener did not start");
            }
            Thread.sleep(20);
        }
    }

    private static long listeners(String database) {
        return JdbcClient.create(dataSourceFor(database, "test")).sql(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = :db AND query LIKE 'LISTEN horus_rle_cache%'")
                .param("db", database).query(Long.class).single();
    }

    private static int terminateListeners(String database) {
        return JdbcClient.create(dataSourceFor(database, "test")).sql("""
                SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity
                WHERE datname = :db AND application_name = 'replica-b' AND query LIKE 'LISTEN horus_rle_cache%'
                """).param("db", database).query(Integer.class).single();
    }

    private static byte[] cardFee(long defaultFee) {
        try (InputStream in = CacheCoherencePostgresIT.class.getResourceAsStream("/rules/card-fee.yaml")) {
            String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return yaml.replace("\"50000 VND\"", "\"" + defaultFee + " VND\"").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String freshDatabase() {
        String name = "rle_cache_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName(), "test")).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSourceFor(name, "test");
        new RuleEngineSchemaMigrator(db).migrate();
        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureCurrentAndNextMonth(java.time.Instant.now());
        return name;
    }

    private static DataSource dataSourceFor(String database, String applicationName) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        ds.setApplicationName(applicationName);
        return ds;
    }
}
