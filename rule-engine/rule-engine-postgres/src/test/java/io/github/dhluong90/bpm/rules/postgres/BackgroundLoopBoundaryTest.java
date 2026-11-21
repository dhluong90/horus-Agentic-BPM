package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The loop boundary of the cache coherence threads (ADR-0009, merge condition of the security review): an
 * {@code Error} thrown by one iteration never ends the loop, and a {@code VirtualMachineError} stops it and reports
 * {@code stopped}. No database is needed.
 */
class BackgroundLoopBoundaryTest {

    @Test
    void RLE_010_an_error_in_one_epoch_check_does_not_end_the_schedule() throws Exception {
        // Given a check that throws an Error from a library once, then succeeds
        AtomicInteger runs = new AtomicInteger();
        CacheCoherence coherence = new CacheCoherence(() -> {
            if (runs.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("org/example/MissingClass");
            }
        }, Duration.ofMillis(20), null);

        // When the schedule runs
        coherence.start();
        try {
            await(() -> runs.get() >= 3);
        } finally {
            coherence.stop();
        }

        // Then the next checks ran and the last success is recorded
        assertThat(coherence.stopped()).isFalse();
        assertThat(coherence.lastCheck()).isNotNull();
    }

    @Test
    void RLE_010_a_virtual_machine_error_stops_the_epoch_check() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CacheCoherence coherence = new CacheCoherence(() -> {
            runs.incrementAndGet();
            throw new OutOfMemoryError("test");
        }, Duration.ofMillis(20), null);

        coherence.start();
        try {
            await(coherence::stopped);
            Thread.sleep(200);   // several periods: a stopped schedule must not run again
        } finally {
            coherence.stop();
        }

        assertThat(runs).hasValue(1);
        assertThat(coherence.stopped()).isTrue();
        assertThat(coherence.lastCheck()).isNull();
    }

    @Test
    void RLE_010_an_error_while_listening_does_not_end_the_listener_loop() throws Exception {
        // Given a data source whose first connection attempt throws an Error, then a plain SQL failure
        AtomicInteger attempts = new AtomicInteger();
        CacheNotificationListener listener = new CacheNotificationListener(dataSource(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new NoClassDefFoundError("org/postgresql/PGConnection");
            }
            throw new SQLException("connection refused");
        }), engines().cache(), Duration.ofMillis(20));

        listener.start();
        try {
            await(() -> attempts.get() >= 3);
        } finally {
            listener.stop();
        }

        assertThat(listener.stopped()).isFalse();
    }

    @Test
    void RLE_010_a_virtual_machine_error_stops_the_listener_and_the_coherence_reports_it() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CacheNotificationListener listener = new CacheNotificationListener(dataSource(() -> {
            attempts.incrementAndGet();
            throw new StackOverflowError("test");
        }), engines().cache(), Duration.ofMillis(20));
        CacheCoherence coherence = new CacheCoherence(() -> { }, Duration.ofMinutes(1), listener);

        coherence.start();
        try {
            await(coherence::stopped);
            Thread.sleep(200);
        } finally {
            coherence.stop();
        }

        assertThat(attempts).hasValue(1);
        assertThat(listener.stopped()).isTrue();
        assertThat(coherence.stopped()).isTrue();
    }

    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private static AbstractDataSource dataSource(ConnectionSource source) {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return source.get();
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return source.get();
            }
        };
    }

    private static RuleEngines engines() {
        AbstractDataSource none = dataSource(() -> {
            throw new SQLException("not used");
        });
        RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
        return PostgresRuleEngines.create(none, new RuleEngineTransactions(new DataSourceTransactionManager(none)),
                caller, caller, Clock.systemUTC(), EngineSettings.defaults().withRequireContentHash(false));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(10);
        }
    }
}
