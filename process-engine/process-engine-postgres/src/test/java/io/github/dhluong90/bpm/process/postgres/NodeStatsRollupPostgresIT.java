package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.NodeWindowStats;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.NodeStatsRollup;
import io.github.dhluong90.bpm.process.internal.service.NodeStatsSettings;
import io.github.dhluong90.bpm.process.internal.service.NodeWindowStatsService;
import io.github.dhluong90.bpm.process.internal.store.NodeStatsStore;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The hourly node statistics rollup on PostgreSQL (#110, PRE-029): bounded lag, one writer per slice across replicas,
 * a slice that rolls back counted once, nothing written by a step transaction, and a tenant-scoped, permission-checked
 * read of the result. Time is the injected clock; nothing sleeps.
 */
class NodeStatsRollupPostgresIT {

    static final Instant T_1000 = Instant.parse("2026-11-20T10:00:00Z");
    static final Instant T_1050 = Instant.parse("2026-11-20T10:50:00Z");
    static final String DEFINITION_A = "01JDEFA00000000000000000A1";
    static final String DEFINITION_B = "01JDEFB00000000000000000B1";

    private final TestClock clock = TestClock.at(T_1050);
    private final AtomicReference<String> tenant = new AtomicReference<>("bank-a");
    private final AtomicReference<Set<String>> permissions = new AtomicReference<>(Set.of("processes:read"));
    private final JdbcClient jdbc = TestEngine.jdbc();
    private final DataSource ds = TestEngine.dataSource();
    private final ProcessEngineTransactions tx = new ProcessEngineTransactions(new DataSourceTransactionManager(ds));
    private final JdbcNodeStatsStore store = new JdbcNodeStatsStore(ds);
    /** Initial watermark = 10:50 - 5 min lag - 45 min = 10:00. */
    private final NodeStatsSettings settings = new NodeStatsSettings(true, Duration.ofMinutes(5), Duration.ofMinutes(5),
            Duration.ofMinutes(1), Duration.ofSeconds(60), Duration.ofMinutes(45));
    private ExecutorService pool;

    @BeforeEach
    void clean() {
        TestEngine.reset();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    private NodeStatsRollup replica(String id, NodeStatsStore over) {
        return new NodeStatsRollup(clock, tx, over, settings, id);
    }

    /** 1,200 completions of {@code approve} between 10:00 and 10:40, each entered earlier by 2 minutes to 3 hours. */
    private long[] seedApprovals(String tenantId, String definitionId) {
        long[] durationsMs = new long[1200];
        for (int i = 0; i < 1200; i++) {
            long durationMs = 120_000L + Math.round(i * (10_800_000.0 - 120_000.0) / 1199.0);
            durationsMs[i] = durationMs;
            Instant completed = T_1000.plusMillis(i * 2_400_000L / 1200);
            Instant entered = completed.minusMillis(durationMs);
            String instance = String.format("INST%022d", i);
            String token = String.format("TOK%023d", i);
            history(tenantId, definitionId, instance, token, "NODE_ENTERED", entered, String.format("HE%024d", i));
            history(tenantId, definitionId, instance, token, "NODE_COMPLETED", completed, String.format("HC%024d", i));
        }
        return durationsMs;
    }

    private void history(String tenantId, String definitionId, String instance, String token, String type, Instant at,
            String id) {
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                        + "token_id, details) VALUES (?, ?, ?, ?, ?, ?, 'approve', ?, '{}'::jsonb)")
                .params(tenantId, java.sql.Timestamp.from(at), id, instance, definitionId, type, token).update();
    }

    private static long trueQuantile(long[] sorted, double q) {
        return sorted[(int) Math.ceil(q * sorted.length) - 1];
    }

    private long completed(String tenantId, String definitionId) {
        return jdbc.sql("SELECT coalesce(sum(completed_count), 0) FROM pre_node_stats_hourly WHERE tenant_id = ? "
                + "AND definition_id = ? AND node_id = 'approve' AND bucket_start = ?")
                .params(tenantId, definitionId, java.sql.Timestamp.from(T_1000)).query(Long.class).single();
    }

    private Instant watermark() {
        return store.watermark(NodeStatsStore.JOB_NAME).orElseThrow();
    }

    @Test
    void PRE_029_rollup_bounded_lag_and_no_step_tx_writes() throws Exception {
        // Given two replicas, a test clock, and 1,200 completions of approve between 10:00 and 10:40 with durations
        // from 2 minutes to 3 hours, in bank-a
        long[] durations = seedApprovals("bank-a", DEFINITION_A);
        NodeStatsRollup first = replica("pod-1", store);
        NodeStatsRollup second = replica("pod-2", store);

        // When the clock reaches 10:50 and both replicas run the rollup at the same moment
        CountDownLatch go = new CountDownLatch(1);
        Future<NodeStatsRollup.RunResult> a = pool.submit(() -> {
            go.await();
            return first.run();
        });
        Future<NodeStatsRollup.RunResult> b = pool.submit(() -> {
            go.await();
            return second.run();
        });
        go.countDown();
        NodeStatsRollup.RunResult resultA = a.get();
        NodeStatsRollup.RunResult resultB = b.get();
        // and one of them, or both in turn, continue until the watermark reaches 10:45
        NodeStatsRollup.RunResult rest = first.run();

        // Then each slice was taken exactly once: the 10:00 bucket holds every completion, none twice
        assertThat(completed("bank-a", DEFINITION_A)).isEqualTo(1200);
        assertThat(resultA.slices() + resultB.slices() + rest.slices()).isEqualTo(45);
        assertThat(watermark()).isEqualTo(T_1050.minus(Duration.ofMinutes(5)));
        assertThat(rest.caughtUp()).isTrue();

        // And the p95 read from the window mode is within 19 percent of the true p95, the median too
        NodeWindowStatsService reads = reads();
        NodeWindowStats stats = reads.nodeWindowStats("loan-approval", 1, T_1000, T_1000.plus(Duration.ofHours(1)));
        NodeWindowStats.NodeWindow approve = stats.nodes().get("approve");
        long[] sorted = durations.clone();
        java.util.Arrays.sort(sorted);
        assertThat(approve.completed()).isEqualTo(1200);
        assertThat((double) approve.p95Ms()).isBetween(trueQuantile(sorted, 0.95) * 0.81, trueQuantile(sorted, 0.95) * 1.19);
        assertThat((double) approve.p50Ms()).isBetween(trueQuantile(sorted, 0.50) * 0.81, trueQuantile(sorted, 0.50) * 1.19);
        assertThat(stats.asOf()).isEqualTo(watermark());
        assertThat(stats.lag()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void PRE_029_a_step_transition_writes_no_statistics_row() {
        // Given an engine whose data source records every statement
        SqlRecorder recorder = new SqlRecorder();
        TestClock stepClock = TestClock.at(T_1000);
        TestEngine engine = TestEngine.replica("pod-1", stepClock).dataSource(recorder.wrap(ds)).build();
        try {
            engine.services.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                    TestEngine.resource("/bpmn/loan-checks.bpmn"), null));
            engine.identity.permissions = Set.of("processes:deploy", "processes:start", "processes:read");
            recorder.clear();

            // When one transition is captured at the SQL level
            engine.services.start(StartRequest.of("loan-checks").withBusinessKey("LN-2027-0001"));

            // Then the captured transition has statements, and none touches the statistics tables
            assertThat(recorder.statements()).isNotEmpty().anyMatch(sql -> sql.contains("pre_history"))
                    .noneMatch(sql -> sql.contains("pre_node_stats_hourly") || sql.contains("pre_engine_watermark"));
        } finally {
            engine.close();
        }
    }

    @Test
    void PRE_029_a_slice_whose_transaction_rolls_back_is_counted_once_on_the_next_run() {
        // Given history for 10:00 to 10:40 and a store that fails once after adding a slice, before its commit
        seedApprovals("bank-a", DEFINITION_A);
        AtomicBoolean failNext = new AtomicBoolean(true);
        NodeStatsStore flaky = new DelegatingStore(store) {
            @Override
            public void advance(String job, Instant to, String replicaId, Instant now) {
                if (failNext.compareAndSet(true, false)) {
                    throw new IllegalStateException("the slice transaction fails after its additions");
                }
                super.advance(job, to, replicaId, now);
            }
        };
        NodeStatsRollup rollup = replica("pod-1", flaky);

        // When the first run fails and the next run completes
        assertThatThrownBy(rollup::run).isInstanceOf(IllegalStateException.class);
        assertThat(completed("bank-a", DEFINITION_A)).as("the failed slice added nothing").isZero();
        assertThat(watermark()).as("and did not move the watermark").isEqualTo(T_1000);
        rollup.run();

        // Then every completion is counted once, not twice
        assertThat(completed("bank-a", DEFINITION_A)).isEqualTo(1200);
    }

    @Test
    void PRE_029_a_run_with_nothing_new_changes_nothing_and_a_run_stops_at_its_time_limit() {
        // Given a first run that reaches 10:45
        seedApprovals("bank-a", DEFINITION_A);
        NodeStatsRollup rollup = replica("pod-1", store);
        rollup.run();
        long rows = jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single();

        // When it runs again with the clock unmoved
        NodeStatsRollup.RunResult again = rollup.run();

        // Then it does nothing: no slice, the same rows and the same counts
        assertThat(again.slices()).isZero();
        assertThat(again.caughtUp()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single()).isEqualTo(rows);
        assertThat(completed("bank-a", DEFINITION_A)).isEqualTo(1200);

        // And a run of a minimum-length budget takes one slice and leaves the rest for the next run
        TestEngine.reset();
        seedApprovals("bank-a", DEFINITION_A);
        TestClock moving = TestClock.at(T_1050);
        NodeStatsStore advancing = new DelegatingStore(store) {
            @Override
            public Collection<NodeHour> aggregate(Instant from, Instant to) {
                moving.advance(Duration.ofSeconds(61));
                return super.aggregate(from, to);
            }
        };
        NodeStatsSettings fullRun = new NodeStatsSettings(true, Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ofMinutes(1), Duration.ofSeconds(60), Duration.ofMinutes(45));
        NodeStatsRollup bounded = new NodeStatsRollup(moving, tx, advancing, fullRun, "pod-1");
        NodeStatsRollup.RunResult limited = bounded.run();
        assertThat(limited.slices()).as("the run stopped at rollup-max-run, not at the end of the history").isLessThan(45)
                .isPositive();
        assertThat(limited.caughtUp()).isFalse();
        assertThat(watermark()).isBefore(T_1050.minus(Duration.ofMinutes(5)));
    }

    private void staleHour(String tenantId, String definitionId, Instant hour) {
        jdbc.sql("INSERT INTO pre_node_stats_hourly (tenant_id, definition_id, bucket_start, node_id, completed_count, "
                + "duration_hist, updated_at) VALUES (?, ?, ?, 'approve', 3, array_fill(0, ARRAY[64]), ?)")
                .params(tenantId, definitionId, java.sql.Timestamp.from(hour), java.sql.Timestamp.from(hour)).update();
    }

    private long hourRows() {
        return jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single();
    }

    @Test
    void PRE_200_hourly_rows_older_than_the_retention_are_deleted_by_the_rollup_run() {
        // Given hourly statistics kept for 90 days, with one hour from 15 July, one from 22 August (the first day
        // kept) and today's, in two tenants
        Instant july = Instant.parse("2026-07-15T10:00:00Z");
        Instant august = Instant.parse("2026-08-22T10:00:00Z");
        staleHour("bank-a", DEFINITION_A, july);
        staleHour("bank-b", DEFINITION_B, july);
        staleHour("bank-a", DEFINITION_A, august);
        seedApprovals("bank-a", DEFINITION_A);
        NodeStatsSettings ninetyDays = new NodeStatsSettings(true, Duration.ofMinutes(5), Duration.ofMinutes(5),
                Duration.ofMinutes(1), Duration.ofSeconds(60), Duration.ofMinutes(45), Duration.ofMinutes(5),
                java.time.Period.ofDays(90));

        // When the rollup runs on 20 November, after the 90 days of the July hours have passed
        NodeStatsRollup.RunResult result = new NodeStatsRollup(clock, tx, store, ninetyDays, "pod-1").run();

        // Then the two July hours (older than 90 days, whole days counted back from today) are gone, in both tenants,
        // and the August hour and today's statistics stay
        assertThat(result.purged()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly WHERE bucket_start = ?")
                .param(java.sql.Timestamp.from(july)).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly WHERE bucket_start = ?")
                .param(java.sql.Timestamp.from(august)).query(Long.class).single()).isOne();
        assertThat(completed("bank-a", DEFINITION_A)).isEqualTo(1200);

        // And a run with nothing older deletes nothing
        assertThat(new NodeStatsRollup(clock, tx, store, ninetyDays, "pod-1").run().purged()).isZero();
    }

    @Test
    void PRE_200_a_purge_takes_bounded_batches_and_the_next_run_continues() {
        // Given more expired hours than one run deletes
        int expired = NodeStatsRollup.PURGE_BATCH * NodeStatsRollup.PURGE_MAX_BATCHES + 1;
        jdbc.sql("INSERT INTO pre_node_stats_hourly (tenant_id, definition_id, bucket_start, node_id, duration_hist, "
                + "updated_at) SELECT 'bank-a', ?, timestamptz '2025-01-01 00:00:00+00' + (n * interval '1 minute'), "
                + "'approve', array_fill(0, ARRAY[64]), now() FROM generate_series(1, ?) AS n")
                .params(DEFINITION_A, expired).update();

        // When the rollup runs twice
        NodeStatsRollup rollup = replica("pod-1", store);
        long first = rollup.run().purged();
        long second = rollup.run().purged();

        // Then the first run stops at its batch limit and the second finishes the job
        assertThat(first).isEqualTo((long) NodeStatsRollup.PURGE_BATCH * NodeStatsRollup.PURGE_MAX_BATCHES);
        assertThat(second).isOne();
        assertThat(hourRows()).isZero();
    }

    @Test
    void PRE_200_no_slice_is_rolled_up_within_the_write_lag_window() {
        // Given a write-lag window of 10 minutes, and completions at 10:30 and 10:45 while the clock reads 10:50
        NodeStatsSettings tenMinutes = new NodeStatsSettings(true, Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(1), Duration.ofSeconds(60), Duration.ofMinutes(60), Duration.ofMinutes(10),
                java.time.Period.ofMonths(13));
        history("bank-a", DEFINITION_A, "INST0000000000000000000001", "TOK00000000000000000000001", "NODE_COMPLETED",
                T_1000.plus(Duration.ofMinutes(30)), "HC000000000000000000000001");
        history("bank-a", DEFINITION_A, "INST0000000000000000000002", "TOK00000000000000000000002", "NODE_COMPLETED",
                T_1000.plus(Duration.ofMinutes(45)), "HC000000000000000000000002");

        // When the rollup runs
        NodeStatsRollup.RunResult result = new NodeStatsRollup(clock, tx, store, tenMinutes, "pod-1").run();

        // Then only history before 10:40 is counted, and the watermark stops there
        assertThat(result.caughtUp()).isTrue();
        assertThat(watermark()).isEqualTo(T_1000.plus(Duration.ofMinutes(40)));
        assertThat(completed("bank-a", DEFINITION_A)).isOne();

        // And once the clock passes 10:55 the later completion is counted too
        clock.advance(Duration.ofMinutes(6));
        new NodeStatsRollup(clock, tx, store, tenMinutes, "pod-1").run();
        assertThat(completed("bank-a", DEFINITION_A)).isEqualTo(2);
    }

    @Test
    void PRE_029_the_window_read_is_tenant_scoped_and_permission_checked_and_writes_nothing() {
        // Given bank-a's loan-approval statistics and bank-b's definition with the same key and version
        seedApprovals("bank-a", DEFINITION_A);
        definition("bank-a", DEFINITION_A, "loan-approval");
        definition("bank-b", DEFINITION_B, "loan-approval");
        replica("pod-1", store).run();
        long statsRows = jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single();
        NodeWindowStatsService reads = reads();

        // When bank-b asks for the window of the same key and version
        tenant.set("bank-b");
        NodeWindowStats other = reads.nodeWindowStats("loan-approval", 1, T_1000, T_1000.plus(Duration.ofHours(1)));

        // Then it sees its own empty statistics, never bank-a's 1,200 completions
        assertThat(other.nodes()).isEmpty();

        // And bank-b cannot read bank-a's version by asking for a key only bank-a has
        definition("bank-a", "01JDEFA00000000000000000A2", "loan-secret");
        assertThatThrownBy(() -> reads.nodeWindowStats("loan-secret", 1, null, null))
                .isInstanceOf(DefinitionNotFoundException.class);

        // And a caller without processes:read is refused, a range above query.max-range is QUERY_INVALID
        permissions.set(Set.of("processes:start"));
        assertThatThrownBy(() -> reads.nodeWindowStats("loan-approval", 1, null, null))
                .isInstanceOf(PermissionDeniedException.class);
        permissions.set(Set.of("processes:read"));
        assertThatThrownBy(() -> reads.nodeWindowStats("loan-approval", 1, T_1000.minus(Duration.ofDays(40)), T_1000))
                .isInstanceOf(QueryInvalidException.class).hasMessageContaining("query.max-range");
        assertThatThrownBy(() -> reads.nodeWindowStats("loan-approval", 1, T_1000, T_1000))
                .isInstanceOf(QueryInvalidException.class);

        // And nothing was written by any refused or empty read
        assertThat(jdbc.sql("SELECT count(*) FROM pre_node_stats_hourly").query(Long.class).single()).isEqualTo(statsRows);
    }

    private void definition(String tenantId, String id, String key) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                + "created_by, created_at, approved_by, approved_at) VALUES (?, ?, ?, 1, 'ACTIVE', 'Loan approval', "
                + "'{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 4, 'IMPORT', now(), 'lan', "
                + "now(), 'minh', now()) ON CONFLICT DO NOTHING").params(tenantId, id, key).update();
    }

    private NodeWindowStatsService reads() {
        definition("bank-a", DEFINITION_A, "loan-approval");
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "supervisor";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions.get();
            }
        };
        return new NodeWindowStatsService(tenant::get, callers, clock, tx, new JdbcProcessStore(ds), store,
                Duration.ofDays(31));
    }

    /** Forwards every call, so a test overrides the one it wants to disturb. */
    private static class DelegatingStore implements NodeStatsStore {
        private final NodeStatsStore delegate;

        DelegatingStore(NodeStatsStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void ensureWatermark(String job, Instant initial, String replicaId, Instant now) {
            delegate.ensureWatermark(job, initial, replicaId, now);
        }

        @Override
        public int deleteBefore(Instant cutoff, int limit) {
            return delegate.deleteBefore(cutoff, limit);
        }

        @Override
        public Optional<Instant> lockWatermark(String job) {
            return delegate.lockWatermark(job);
        }

        @Override
        public Collection<NodeHour> aggregate(Instant from, Instant to) {
            return delegate.aggregate(from, to);
        }

        @Override
        public void add(Collection<NodeHour> hours, Instant now) {
            delegate.add(hours, now);
        }

        @Override
        public void advance(String job, Instant to, String replicaId, Instant now) {
            delegate.advance(job, to, replicaId, now);
        }

        @Override
        public Optional<Instant> watermark(String job) {
            return delegate.watermark(job);
        }

        @Override
        public List<NodeHour> window(String tenantId, String definitionId, Instant from, Instant to) {
            return delegate.window(tenantId, definitionId, from, to);
        }
    }
}
