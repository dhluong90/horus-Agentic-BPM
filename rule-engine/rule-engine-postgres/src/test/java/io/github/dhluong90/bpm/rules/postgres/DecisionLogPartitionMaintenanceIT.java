package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Partition maintenance of the decision log while the host runs (RLE-017, TR-OPS-04, TR-OPS-05, TR-OBS-01): months
 * ahead of the clock without a restart, stragglers moved out of the default partition, two replicas at once, and no
 * update or delete by date. One fresh database per test; every month comes from a fixed instant.
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
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                  masked_inputs, matched_rule_ids, eval_clock, duration_micros, log_mode)
                VALUES ('bank-a', CAST(:at AS timestamptz), :id, 'card-fee', 1, 'FIRST', 'OK', '{}', '[]',
                  CAST(:at AS timestamptz), 12, 'TRANSACTIONAL')
                """).param("id", id).param("at", createdAt).update();
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
