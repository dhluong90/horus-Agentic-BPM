package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * TR-OPS-05: the Rule Engine behind PgBouncer in transaction mode, where each transaction may run on another server
 * connection. The schema migrates on a direct connection, as a host's migration job does; everything else goes
 * through the pooler with {@code prepareThreshold=0}: partition maintenance, import, request and approval with the
 * lock version, cold and cached evaluation, an idempotent replay, the epoch check, the retirement sweep and a refusal
 * for another tenant. Every statement the engine sends is captured; none keeps state in the session (no session
 * {@code SET}, no {@code LISTEN}, no session advisory lock, no named {@code PREPARE}, no temporary table). The CI job
 * {@code pooler} runs the tests tagged {@code pooler}; the two-replica cache run is {@code MultiReplicaCacheIT}.
 */
@Tag("pooler")
class PoolerTransactionModePostgresIT {

    static final Network NETWORK = Network.newNetwork();
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres())
            .withNetwork(NETWORK).withNetworkAliases("pg");
    static GenericContainer<?> pgbouncer;

    static final Instant NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final String DATABASE = "rle_pooler_it";

    /** Statements that keep state in the server session, which a transaction-mode pooler hands to other clients. */
    static final List<Pattern> SESSION_STATE = List.of(
            Pattern.compile("(?is)^\\s*SET\\s+(?!LOCAL\\b)(SESSION\\s+)?\\w+"),
            Pattern.compile("(?is)\\bset_config\\s*\\([^)]*,\\s*false\\s*\\)"),
            Pattern.compile("(?is)^\\s*(UN)?LISTEN\\b"),
            Pattern.compile("(?is)\\bpg_(try_)?advisory_lock(_shared)?\\s*\\("),
            Pattern.compile("(?is)^\\s*PREPARE\\b"),
            Pattern.compile("(?is)\\bCREATE\\s+(GLOBAL\\s+|LOCAL\\s+)?TEMP(ORARY)?\\b"));

    final List<String> statements = new CopyOnWriteArrayList<>();
    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    final MovableClock clock = new MovableClock(NOW);
    DataSource pooled;
    RuleEngineTransactions transactions;
    RuleEngines engines;

    @BeforeAll
    static void start() {
        POSTGRES.start();
        JdbcClient.create(direct(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + DATABASE).update();
        new RuleEngineSchemaMigrator(direct(DATABASE)).migrate();
        pgbouncer = new GenericContainer<>(DockerImageName.parse(TestImages.pgbouncer()))
                .withNetwork(NETWORK)
                .withEnv("DB_HOST", "pg")
                .withEnv("DB_PORT", "5432")
                .withEnv("DB_NAME", DATABASE)
                .withEnv("DB_USER", POSTGRES.getUsername())
                .withEnv("DB_PASSWORD", POSTGRES.getPassword())
                .withEnv("AUTH_TYPE", "scram-sha-256")
                .withEnv("POOL_MODE", "transaction")
                .withEnv("LISTEN_PORT", "5432")
                .withExposedPorts(5432)
                .waitingFor(Wait.forListeningPort());
        pgbouncer.start();
    }

    @AfterAll
    static void stop() {
        if (pgbouncer != null) {
            pgbouncer.stop();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://" + pgbouncer.getHost() + ":" + pgbouncer.getMappedPort(5432) + "/" + DATABASE
                + "?prepareThreshold=0");
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        pooled = new StatementCapturingDataSource(ds, statements);
        transactions = new RuleEngineTransactions(new DataSourceTransactionManager(pooled));
        engines = PostgresRuleEngines.create(pooled, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
    }

    @Test
    void TR_OPS_05_the_rule_set_lifecycle_runs_through_a_transaction_mode_pooler_without_session_state() {
        // Given the decision log partitions created through the pooler
        new DecisionLogPartitions(pooled, transactions).ensureCurrentAndNextMonth(NOW);

        // When alice imports card-fee and requests publication, and bob approves it at lock version 1
        caller.as("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        long lock = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "bob");
        RuleSetVersion v1 = engines.versionService().approve("card-fee", 1, new WriteOptions(null, lock, null));

        // Then v1 is published
        assertThat(v1.status()).isEqualTo(VersionStatus.PUBLISHED);

        // When a teller prices a Gold card (cold) and a Silver card with 5 transactions (cached), the second with a key
        caller.as("bank-a", "teller-01");
        DecisionResult gold = engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0)));
        DecisionRequest silver = DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)))
                .withIdempotencyKey("teller-01-2026-12-15-0001");
        DecisionResult first = engines.ruleEngine().evaluate(silver);
        DecisionResult replayed = engines.ruleEngine().evaluate(silver);

        // Then 0 VND and 10,000 VND, and the replay returns the logged decision without a second row
        assertThat(gold.outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
        assertThat(first.outputs()).containsEntry("fee", Money.ofMinor("VND", 10000));
        assertThat(replayed.decisionId()).isEqualTo(first.decisionId());
        assertThat(count("rle_decision")).isEqualTo(2);

        // When bob approves v2 (default fee 60,000 VND), effective one hour later, and the clock passes that hour
        caller.as("bank-a", "alice");
        Instant later = NOW.plus(Duration.ofHours(1));
        String v2 = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("\"50000 VND\"", "\"60000 VND\"")
                .replace("hitPolicy: first", "effectiveFrom: \"" + later + "\"\nhitPolicy: first");
        engines.versionService().importVersion("card-fee", ImportRequest.yaml(v2.getBytes(StandardCharsets.UTF_8)));
        long lock2 = engines.versionService().requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "bob");
        engines.versionService().approve("card-fee", 2, new WriteOptions(null, lock2, null));
        clock.set(later.plusSeconds(1));

        // Then the epoch check runs through the pooler, the sweep retires v1 once, and a Silver card with 1 transaction
        // pays the v2 default
        engines.cache().checkEpochs();
        assertThat(engines.lifecycleSweep().run()).isEqualTo(1);
        assertThat(engines.lifecycleSweep().run()).isZero();
        caller.as("bank-a", "teller-01");
        DecisionResult afterSweep = engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "SILVER", "txCount", 1)));
        assertThat(afterSweep.version()).isEqualTo(2);
        assertThat(afterSweep.outputs()).containsEntry("fee", Money.ofMinor("VND", 60000));

        // When carol of tenant-b asks for bank-a's card-fee
        long decisions = count("rle_decision");
        caller.as("tenant-b", "carol");
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0))))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then the request is refused and nothing is written
        assertThat(count("rle_decision")).isEqualTo(decisions);

        // And no statement the engine sent through the pooler keeps state in the session
        assertThat(statements).isNotEmpty();
        assertThat(statements).noneMatch(PoolerTransactionModePostgresIT::keepsSessionState);
        assertThat(statements).anyMatch(sql -> sql.contains("pg_advisory_xact_lock"))
                .anyMatch(sql -> sql.contains("pg_try_advisory_xact_lock"));
    }

    @Test
    void TR_OPS_05_two_replicas_maintaining_partitions_at_once_through_the_pooler_both_finish() throws Exception {
        // Given two replicas that create the partitions up to 90 days ahead at the same moment
        DecisionLogPartitions a = new DecisionLogPartitions(pooled, transactions);
        DecisionLogPartitions b = new DecisionLogPartitions(pooled, transactions);
        Instant spring = Instant.parse("2027-03-10T00:00:00Z");

        // When both run through the pooler
        List<String> created;
        try (ExecutorService pods = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<String>> first = pods.submit(() -> a.ensureAhead(spring, Duration.ofDays(90)));
            Future<List<String>> second = pods.submit(() -> b.ensureAhead(spring, Duration.ofDays(90)));
            created = new java.util.ArrayList<>(first.get());
            created.addAll(second.get());
        }

        // Then each month exists once, created by one of them, under the transaction-scoped lock only
        for (java.time.YearMonth month : DecisionLogPartitions.months(spring, Duration.ofDays(90))) {
            assertThat(a.exists(month)).isTrue();
        }
        assertThat(created).doesNotHaveDuplicates();
        assertThat(statements).noneMatch(PoolerTransactionModePostgresIT::keepsSessionState);
    }

    @Test
    void TR_OPS_05_four_eyes_retirement_history_replay_and_retention_drop_run_through_the_pooler() {
        // Given the decision log partitions, and an engine whose manual retirements need a second approver
        new DecisionLogPartitions(pooled, transactions).ensureCurrentAndNextMonth(NOW);
        RuleEngines fourEyes = PostgresRuleEngines.create(pooled, transactions, caller, caller, clock,
                EngineSettings.defaults().withLifecycle("system:supersession", "four-eyes"));
        fourEyes.cursorKeys().load();

        // When bank-c publishes card-fee v1, a teller prices a Silver card with 5 transactions, and a draft v2
        // (default fee 60,000 VND) is imported
        caller.as("bank-c", "alice");
        fourEyes.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        long lock = fourEyes.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.as("bank-c", "bob");
        long published = fourEyes.versionService().approve("card-fee", 1, new WriteOptions(null, lock, null))
                .lockVersion();
        caller.as("bank-c", "teller-01");
        fourEyes.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)));
        caller.as("bank-c", "alice");
        String v2 = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("\"50000 VND\"", "\"60000 VND\"");
        fourEyes.versionService().importVersion("card-fee", ImportRequest.yaml(v2.getBytes(StandardCharsets.UTF_8)));

        // And the draft is replayed over the logged decisions (#95)
        caller.as("bank-c", "pricing-approver", java.util.Set.of("rules:simulate", "rules:log:read"));
        long decisions = count("rle_decision");
        io.github.dhluong90.bpm.rules.api.HistorySimulationResult replay = fourEyes.ruleEngine().simulateHistory(
                "card-fee", 2, io.github.dhluong90.bpm.rules.api.HistorySimulation.between(
                        NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1))));

        // Then the one logged decision is replayed and nothing is written
        assertThat(replay.replayedCount()).isEqualTo(1);
        assertThat(count("rle_decision")).isEqualTo(decisions);

        // When bob requests v1's retirement and carol confirms it (#213)
        caller.as("bank-c", "bob");
        long requested = fourEyes.versionService()
                .retire("card-fee", 1, new WriteOptions(null, published, "Fee table withdrawn"), true).lockVersion();
        caller.as("bank-c", "carol");
        RuleSetVersion retired = fourEyes.versionService()
                .confirmRetire("card-fee", 1, new WriteOptions(null, requested, null), true);

        // Then v1 is retired
        assertThat(retired.status()).isEqualTo(VersionStatus.RETIRED);

        // When the months before February 2027 expire under a 12-month retention (#104)
        List<DecisionLogPartitions.Dropped> dropped = new DecisionLogPartitions(pooled, transactions)
                .dropExpired(Instant.parse("2028-02-03T00:00:00Z"), java.time.Period.ofMonths(12));

        // Then December 2026 is dropped and recorded, and no statement kept state in the session
        assertThat(dropped).extracting(DecisionLogPartitions.Dropped::partition)
                .contains(DecisionLogPartitions.partitionName(java.time.YearMonth.of(2026, 12)));
        assertThat(count("rle_decision_log_drop")).isPositive();
        assertThat(statements).anyMatch(sql -> sql.contains("rle_decision_log_drop"));
        assertThat(statements).noneMatch(PoolerTransactionModePostgresIT::keepsSessionState);
    }

    static boolean keepsSessionState(String sql) {
        return SESSION_STATE.stream().anyMatch(p -> p.matcher(sql).find());
    }

    @Test
    void TR_OPS_05_the_session_state_check_recognises_what_a_pooler_cannot_carry() {
        // Given statements that hold session state and statements scoped to their transaction
        // When they are checked
        // Then only the session ones are flagged
        assertThat(List.of("SET statement_timeout = '2s'", "SET SESSION lock_timeout = '5s'", "LISTEN horus_rle_cache",
                "SELECT pg_advisory_lock(42)", "SELECT pg_try_advisory_lock(42)", "PREPARE q AS SELECT 1",
                "CREATE TEMP TABLE t (x int)", "SELECT set_config('statement_timeout', '2s', false)"))
                .allMatch(PoolerTransactionModePostgresIT::keepsSessionState);
        assertThat(List.of("SET LOCAL lock_timeout = '5s'", "SELECT pg_advisory_xact_lock(42)",
                "SELECT pg_try_advisory_xact_lock(:lockKey)", "SELECT set_config('statement_timeout', :timeout, true)",
                "SELECT pg_notify('horus_rle_cache', :payload)"))
                .noneMatch(PoolerTransactionModePostgresIT::keepsSessionState);
    }

    private long count(String table) {
        return JdbcClient.create(direct(DATABASE)).sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static DataSource direct(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Records the SQL of every prepared and plain statement the engine sends. */
    static final class StatementCapturingDataSource extends DelegatingDataSource {
        private final List<String> statements;

        StatementCapturingDataSource(DataSource target, List<String> statements) {
            super(target);
            this.statements = statements;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("prepare") && args != null && args[0] instanceof String sql) {
                            statements.add(sql);
                        }
                        Object result = invoke(method, connection, args);
                        if (result instanceof Statement statement && method.getName().equals("createStatement")) {
                            return capturing(statement);
                        }
                        return result;
                    });
        }

        private Statement capturing(Statement statement) {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Statement.class},
                    (proxy, method, args) -> {
                        if ((method.getName().startsWith("execute") || method.getName().equals("addBatch"))
                                && args != null && args.length > 0 && args[0] instanceof String sql) {
                            statements.add(sql);
                        }
                        return invoke(method, statement, args);
                    });
        }

        private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
