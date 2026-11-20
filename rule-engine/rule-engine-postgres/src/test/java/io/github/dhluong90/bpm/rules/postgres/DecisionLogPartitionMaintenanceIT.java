package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Partition maintenance of the decision log while the host runs (RLE-017, TR-OPS-04, TR-OPS-05, TR-OBS-01): months
 * ahead of the clock without a restart, stragglers moved out of the default partition, two replicas at once, no
 * update or delete by date, and retention by dropping whole months with a record of each drop (PD-050). One fresh
 * database per test; every month comes from a fixed instant.
 */
@Testcontainers
class DecisionLogPartitionMaintenanceIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void RLE_017_creates_monthly_partitions_ahead_of_the_clock_without_a_restart() {
        // Given a clean database and the clock at 26 January 2027
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);

        // When the migration and the partition job have run
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);

        // Then
        assertThat(partitionBounds(db).keySet())
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02", "rle_decision_default");
        Map<String, String> before = partitionBounds(db);

        // When the clock moves to 25 February 2027 and the job runs again in the same process
        List<String> created = partitions.ensureAhead(Instant.parse("2027-02-25T00:00:00Z"), SEVEN_DAYS);

        // Then March exists and no existing partition changed (same table, same bounds)
        assertThat(created).containsExactly("rle_decision_2027_03");
        Map<String, String> after = partitionBounds(db);
        assertThat(after).containsAllEntriesOf(before).containsKey("rle_decision_2027_03").hasSize(4);
        assertThat(after.get("rle_decision_2027_03"))
                .isEqualTo("FOR VALUES FROM ('2027-03-01 00:00:00+00') TO ('2027-04-01 00:00:00+00')");
    }

    @Test
    void RLE_017_months_cover_the_current_the_next_and_every_month_up_to_the_period_ahead() {
        assertThat(DecisionLogPartitions.months(Instant.parse("2027-01-10T00:00:00Z"), SEVEN_DAYS))
                .extracting(DecisionLogPartitions::partitionName)
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(DecisionLogPartitions.months(Instant.parse("2027-01-26T00:00:00Z"), Duration.ofDays(40)))
                .extracting(DecisionLogPartitions::partitionName)
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02", "rle_decision_2027_03");
        assertThat(DecisionLogPartitions.months(Instant.parse("2027-12-31T23:59:59Z"), Duration.ZERO))
                .extracting(DecisionLogPartitions::partitionName)
                .containsExactly("rle_decision_2027_12", "rle_decision_2028_01");
    }

    @Test
    void RLE_017_moves_a_straggler_out_of_the_default_partition_and_the_count_drops_to_zero() {
        // Given January and February exist, and a decision of 15 March 2027 landed in the default partition
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);
        JdbcClient jdbc = JdbcClient.create(db);
        insertDecision(jdbc, "01KDQ0000000000000000M1503", "2027-03-15T00:00:00Z");
        assertThat(partitionOf(jdbc, "01KDQ0000000000000000M1503")).isEqualTo("rle_decision_default");
        assertThat(partitions.defaultRows()).isEqualTo(1);

        // When the job creates March
        List<String> created = partitions.ensureAhead(Instant.parse("2027-02-25T00:00:00Z"), SEVEN_DAYS);

        // Then the row is in March, the default partition is empty and nothing was lost
        assertThat(created).containsExactly("rle_decision_2027_03");
        assertThat(partitionOf(jdbc, "01KDQ0000000000000000M1503")).isEqualTo("rle_decision_2027_03");
        assertThat(partitions.defaultRows()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void TR_OPS_05_two_replicas_running_the_job_at_once_create_each_month_once() throws Exception {
        // Given two replicas, each with its own connections, on a clean database
        DataSource db = migratedDatabase();
        DecisionLogPartitions replicaA = partitions(db);
        DecisionLogPartitions replicaB = partitions(db);
        Instant now = Instant.parse("2027-01-26T00:00:00Z");
        Duration ahead = Duration.ofDays(100);
        CyclicBarrier start = new CyclicBarrier(2);

        // When both run the job at the same moment
        List<String> created;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<List<String>> a = pool.submit(() -> {
                start.await();
                return replicaA.ensureAhead(now, ahead);
            });
            Future<List<String>> b = pool.submit(() -> {
                start.await();
                return replicaB.ensureAhead(now, ahead);
            });
            created = new ArrayList<>(a.get());
            created.addAll(b.get());
        }

        // Then neither run failed and each month was created exactly once
        assertThat(created).containsExactlyInAnyOrder("rle_decision_2027_01", "rle_decision_2027_02",
                "rle_decision_2027_03", "rle_decision_2027_04", "rle_decision_2027_05");
        assertThat(partitionBounds(db)).hasSize(6);
    }

    @Test
    void TR_OBS_01_partition_job_issues_no_update_or_delete_on_rle_decision() {
        // Given the SQL of every statement is captured, and a straggler waits in the default partition
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource plain = migratedDatabase();
        DataSource db = new RuleEnginePostgresIT.SqlCapturingDataSource(plain, statements);
        DecisionLogPartitions partitions = partitions(db);
        insertDecision(JdbcClient.create(plain), "01KDQ0000000000000000M1504", "2027-03-15T00:00:00Z");

        // When the job runs any number of times, across a month boundary
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);
        partitions.ensureAhead(Instant.parse("2027-01-27T00:00:00Z"), SEVEN_DAYS);
        partitions.ensureAhead(Instant.parse("2027-02-25T00:00:00Z"), SEVEN_DAYS);
        partitions.defaultRows();

        // Then no UPDATE or DELETE names rle_decision; the only delete is the bounded move out of the default
        Pattern onDecisionLog = Pattern.compile("(?is).*\\b(UPDATE|DELETE\\s+FROM)\\s+rle_decision\\b.*");
        assertThat(statements).isNotEmpty().noneMatch(sql -> onDecisionLog.matcher(sql).matches());
        // One bounded move per created month (January, February, March), each limited to that month's range.
        assertThat(statements).filteredOn(sql -> sql.toUpperCase().contains("DELETE"))
                .hasSize(3)
                .allSatisfy(sql -> assertThat(sql)
                        .contains("DELETE FROM rle_decision_default WHERE created_at >= ? AND created_at < ?")
                        .doesNotContainIgnoringCase("UPDATE"));
        assertThat(statements).noneMatch(sql -> sql.toUpperCase().contains("DROP "));
    }

    @Test
    void RLE_017_decision_log_monthly_partitions_and_retention_by_drop() {
        // Given partitions 2027_01 to 2028_02, five decisions of two banks in January 2027 and one in February 2027
        List<String> statements = new CopyOnWriteArrayList<>();
        DataSource plain = migratedDatabase();
        DecisionLogPartitions partitions = partitions(new RuleEnginePostgresIT.SqlCapturingDataSource(plain, statements));
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), Duration.ofDays(366));
        partitions.ensureAhead(Instant.parse("2028-02-03T00:00:00Z"), SEVEN_DAYS);
        JdbcClient jdbc = JdbcClient.create(plain);
        insertDecision(jdbc, "bank-a", "01KDQ0000000000000000J0001", "2027-01-01T00:00:00Z");
        insertDecision(jdbc, "bank-a", "01KDQ0000000000000000J0002", "2027-01-15T09:30:00Z");
        insertDecision(jdbc, "bank-a", "01KDQ0000000000000000J0003", "2027-01-31T23:59:59Z");
        insertDecision(jdbc, "bank-b", "01KDQ0000000000000000J0004", "2027-01-10T00:00:00Z");
        insertDecision(jdbc, "bank-b", "01KDQ0000000000000000J0005", "2027-01-20T00:00:00Z");
        insertDecision(jdbc, "bank-a", "01KDQ0000000000000000F0001", "2027-02-01T00:00:00Z");
        statements.clear();

        // When retention P12M runs with the clock at 3 February 2028
        List<DecisionLogPartitions.Dropped> dropped =
                partitions.dropExpired(Instant.parse("2028-02-03T00:00:00Z"), Period.ofMonths(12));

        // Then only January 2027 is dropped: its upper bound 2027-02-01 is before the cutoff 2027-02-03
        assertThat(dropped).extracting(DecisionLogPartitions.Dropped::partition).containsExactly("rle_decision_2027_01");
        assertThat(dropped.getFirst().decisionsByTenant()).containsExactly(Map.entry("bank-a", 3L),
                Map.entry("bank-b", 2L));
        assertThat(dropped.getFirst().total()).isEqualTo(5L);
        assertThat(partitionBounds(plain)).doesNotContainKey("rle_decision_2027_01")
                .containsKeys("rle_decision_2027_02", "rle_decision_2028_02", "rle_decision_default");
        assertThat(jdbc.sql("SELECT id FROM rle_decision").query(String.class).list())
                .containsExactly("01KDQ0000000000000000F0001");
        // No row was deleted: the only change to the decision log is the DROP of the whole month
        assertThat(statements).noneMatch(sql -> sql.toUpperCase().contains("DELETE"))
                .noneMatch(sql -> sql.toUpperCase().contains("UPDATE"))
                .filteredOn(sql -> sql.toUpperCase().contains("DROP "))
                .containsExactly("DROP TABLE rle_decision_2027_01");
        // And the drop is recorded per tenant and in total, with the retention and the cutoff in force
        assertThat(jdbc.sql("""
                SELECT tenant_id || ' ' || partition_name || ' ' || decision_count || ' ' || retention || ' '
                  || to_char(cutoff AT TIME ZONE 'UTC', 'YYYY-MM-DD') || ' '
                  || to_char(range_to AT TIME ZONE 'UTC', 'YYYY-MM-DD')
                FROM rle_decision_log_drop ORDER BY tenant_id
                """).query(String.class).list()).containsExactly(
                "* rle_decision_2027_01 5 P12M 2027-02-03 2027-02-01",
                "bank-a rle_decision_2027_01 3 P12M 2027-02-03 2027-02-01",
                "bank-b rle_decision_2027_01 2 P12M 2027-02-03 2027-02-01");

        // When it runs again the same day, Then nothing more is dropped or recorded
        assertThat(partitions.dropExpired(Instant.parse("2028-02-03T00:00:00Z"), Period.ofMonths(12))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_log_drop").query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void RLE_017_retention_never_drops_the_default_partition_and_records_an_empty_month() {
        // Given January 2027 holds no decision and a straggler of 2026 waits in the default partition
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);
        JdbcClient jdbc = JdbcClient.create(db);
        insertDecision(jdbc, "bank-a", "01KDQ0000000000000000D2026", "2026-06-01T00:00:00Z");

        // When retention P12M runs on 1 March 2028, after 2027_02's bound 2027-03-01 equals the cutoff
        List<DecisionLogPartitions.Dropped> dropped =
                partitions.dropExpired(Instant.parse("2028-03-01T00:00:00Z"), Period.ofYears(1));

        // Then both whole months are dropped, the empty one recorded with 0, and the default partition stays
        assertThat(dropped).extracting(DecisionLogPartitions.Dropped::partition)
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(jdbc.sql("SELECT partition_name || ' ' || decision_count FROM rle_decision_log_drop "
                + "WHERE tenant_id = '*' ORDER BY partition_name").query(String.class).list())
                .containsExactly("rle_decision_2027_01 0", "rle_decision_2027_02 0");
        assertThat(partitionBounds(db)).containsOnlyKeys("rle_decision_default");
        assertThat(partitions.defaultRows()).isEqualTo(1L);
    }

    @Test
    void RLE_017_cutoff_is_whole_utc_months_and_a_month_expires_when_its_upper_bound_reaches_it() {
        assertThat(DecisionLogPartitions.cutoff(Instant.parse("2028-02-03T00:00:00Z"), Period.ofMonths(12)))
                .isEqualTo(Instant.parse("2027-02-03T00:00:00Z"));
        assertThat(DecisionLogPartitions.cutoff(Instant.parse("2028-02-29T12:00:00Z"), Period.ofYears(10)))
                .isEqualTo(Instant.parse("2018-02-28T12:00:00Z"));
        List<YearMonth> present = List.of(YearMonth.of(2027, 1), YearMonth.of(2027, 2), YearMonth.of(2027, 3));
        assertThat(DecisionLogPartitions.expired(present, Instant.parse("2027-02-01T00:00:00Z")))
                .containsExactly(YearMonth.of(2027, 1));
        assertThat(DecisionLogPartitions.expired(present, Instant.parse("2027-01-31T23:59:59Z"))).isEmpty();
        assertThatThrownBy(() -> DecisionLogPartitions.cutoff(Instant.EPOCH, Period.ofDays(400)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole months");
        assertThatThrownBy(() -> DecisionLogPartitions.cutoff(Instant.EPOCH, Period.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void RLE_017_a_drop_that_waits_past_the_lock_timeout_drops_and_records_nothing() throws Exception {
        // Given January 2027 has expired and a long read holds the decision log open in another transaction
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);
        JdbcClient jdbc = JdbcClient.create(db);
        try (java.sql.Connection reader = db.getConnection()) {
            reader.setAutoCommit(false);
            try (var statement = reader.createStatement()) {
                statement.execute("SELECT count(*) FROM rle_decision");
            }

            // When retention runs, Then the DROP gives up after lock_timeout (2 s)
            assertThatThrownBy(() -> partitions.dropExpired(Instant.parse("2028-03-01T00:00:00Z"), Period.ofMonths(12)))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("lock timeout");
            reader.rollback();
        }

        // And nothing was dropped or recorded, so the next run retries
        assertThat(partitionBounds(db)).containsKeys("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_log_drop").query(Long.class).single()).isZero();
    }

    @Test
    void RLE_017_the_drop_record_is_append_only_and_the_V19_rollback_step_runs() {
        // Given one recorded drop
        DataSource db = migratedDatabase();
        DecisionLogPartitions partitions = partitions(db);
        partitions.ensureAhead(Instant.parse("2027-01-26T00:00:00Z"), SEVEN_DAYS);
        partitions.dropExpired(Instant.parse("2028-02-03T00:00:00Z"), Period.ofMonths(12));
        JdbcClient jdbc = JdbcClient.create(db);

        // When the runtime role tries to rewrite or remove it, Then the database refuses (SQLSTATE 55000)
        for (String sql : List.of("UPDATE rle_decision_log_drop SET decision_count = 0",
                "DELETE FROM rle_decision_log_drop", "TRUNCATE rle_decision_log_drop")) {
            assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("rle_decision_log_drop is append-only");
        }
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_log_drop").query(Long.class).single()).isEqualTo(1L);

        // When the documented rollback step of V19 runs, Then the table and its function are gone
        jdbc.sql("DROP TRIGGER rle_decision_log_drop_no_truncate ON rle_decision_log_drop").update();
        jdbc.sql("DROP TRIGGER rle_decision_log_drop_no_change ON rle_decision_log_drop").update();
        jdbc.sql("DROP TABLE rle_decision_log_drop").update();
        jdbc.sql("DROP FUNCTION rle_decision_log_drop_append_only()").update();
        assertThat(jdbc.sql("SELECT to_regclass('rle_decision_log_drop') IS NULL").query(Boolean.class).single())
                .isTrue();
    }

    private static DecisionLogPartitions partitions(DataSource db) {
        return new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)));
    }

    /** Partition name to its bound, for every partition of rle_decision. */
    private static Map<String, String> partitionBounds(DataSource db) {
        Map<String, String> bounds = new java.util.TreeMap<>();
        // Bounds print in the session time zone: one connection, set to UTC first.
        var single = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                org.springframework.jdbc.datasource.DataSourceUtils.getConnection(db), true);
        JdbcClient jdbc = JdbcClient.create(single);
        jdbc.sql("SET TIME ZONE 'UTC'").update();
        jdbc.sql("""
                SELECT c.relname, pg_get_expr(c.relpartbound, c.oid) AS bound
                FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                WHERE i.inhparent = 'rle_decision'::regclass
                """).query((rs, n) -> bounds.put(rs.getString(1), rs.getString(2))).list();
        single.destroy();
        return bounds;
    }

    private static void insertDecision(JdbcClient jdbc, String id, String createdAt) {
        insertDecision(jdbc, "bank-a", id, createdAt);
    }

    private static void insertDecision(JdbcClient jdbc, String tenant, String id, String createdAt) {
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                  masked_inputs, matched_rule_ids, eval_clock, duration_micros, log_mode)
                VALUES (:tenant, CAST(:at AS timestamptz), :id, 'card-fee', 1, 'FIRST', 'OK', '{}', '[]',
                  CAST(:at AS timestamptz), 12, 'TRANSACTIONAL')
                """).param("tenant", tenant).param("id", id).param("at", createdAt).update();
    }

    private static String partitionOf(JdbcClient jdbc, String id) {
        return jdbc.sql("SELECT tableoid::regclass::text FROM rle_decision WHERE id = :id").param("id", id)
                .query(String.class).single();
    }

    private static DataSource migratedDatabase() {
        String name = "rle_part_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSourceFor(name);
        new RuleEngineSchemaMigrator(db).migrate();
        return db;
    }

    private static DataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
