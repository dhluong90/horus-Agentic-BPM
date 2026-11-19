package com.horus.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.IdempotencyInFlightException;
import com.horus.bpm.rules.api.IdempotencyKeyReuseException;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.SelfApprovalForbiddenException;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.service.EngineSettings;
import com.horus.bpm.rules.internal.service.RuleEngines;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The engine services on the PostgreSQL adapters, in a host-managed transaction, one fresh database
 * per test. The clock is fixed at 15 December 2026, 09:00 in Ho Chi Minh City.
 */
@Testcontainers
class RuleEnginePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2026-12-15T02:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final List<String> statements = new CopyOnWriteArrayList<>();
    final Caller caller = new Caller();
    DataSource db;
    TransactionTemplate host;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        db = new SqlCapturingDataSource(freshDatabase(), statements);
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(transactionManager);
        new DecisionLogPartitions(db, transactions).ensureCurrentAndNextMonth(NOW);
        host = new TransactionTemplate(transactionManager);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults());
    }

    @Test
    void RLE_013_transactional_decision_log_rolls_back_with_host() {
        // Given
        publishCardFee();
        caller.as("bank-a", "teller-01");
        DecisionInput gold = DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5));

        // When the host transaction rolls back after the evaluation
        assertThatThrownBy(() -> host.executeWithoutResult(status -> {
            engines.ruleEngine().evaluate("card-fee", gold);
            throw new IllegalStateException("host business rule failed");
        })).isInstanceOf(IllegalStateException.class);

        // Then
        assertThat(decisionRows()).isZero();

        // When the host transaction commits
        statements.clear();
        DecisionResult result = host.execute(status -> engines.ruleEngine().evaluate("card-fee", gold));

        // Then
        assertThat(decisionRows()).isEqualTo(1);
        assertThat(statements.stream().filter(sql -> sql.contains("rle_decision ") || sql.contains("rle_decision\n")))
                .filteredOn(sql -> sql.strip().toUpperCase().startsWith("INSERT"))
                .hasSize(1);
        assertThat(statements).noneMatch(sql -> sql.strip().toUpperCase().matches("(?s)^(UPDATE|DELETE).*RLE_DECISION.*"));
        assertThat(JdbcClient.create(db).sql("""
                SELECT outcome || ' ' || matched_rule_ids::text || ' ' || outputs::text || ' ' || caller_id
                FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id
                """).param("id", result.decisionId()).query(String.class).single())
                .isEqualTo("OK [\"R1\"] {\"fee\": {\"amount\": 0, \"currency\": \"VND\"}} teller-01");
    }

    @Test
    void RLE_001_first_hit_policy_returns_top_matching_rule() {
        // Given
        publishCardFee();
        caller.as("bank-a", "teller-01");

        // When
        DecisionResult result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));

        // Then
        assertThat(result.matchedRuleIds()).containsExactly("R1");
        assertThat(result.outputs()).containsEntry("fee", Money.ofMinor("VND", 0));
    }

    @Test
    void RLE_014_idempotency_key_replays_identical_and_rejects_mismatch() throws Exception {
        // Given
        byte[] b1 = cardFee();
        byte[] b2 = new String(b1, StandardCharsets.UTF_8).replace("50000 VND", "60000 VND").getBytes(StandardCharsets.UTF_8);
        RuleSetVersion first = engines.versionService().importRuleSet(ImportRequest.yaml(b1).withIdempotencyKey("k-101"));

        // When
        RuleSetVersion replay = engines.versionService().importRuleSet(ImportRequest.yaml(b1).withIdempotencyKey("k-101"));

        // Then
        assertThat(replay).isEqualTo(first);
        assertThat(count("rle_version")).isEqualTo(1);
        assertThatThrownBy(() -> engines.versionService().importRuleSet(ImportRequest.yaml(b2).withIdempotencyKey("k-101")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        assertThat(count("rle_version")).isEqualTo(1);
        assertThat(count("rle_decision_outbox")).isEqualTo(1);

        // And a second request with a key whose first request is still running is in flight
        CountDownLatch firstHoldsKey = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<RuleSetVersion> running = pool.submit(() -> host.execute(status -> {
                RuleSetVersion v = engines.versionService().importVersion("card-fee",
                        ImportRequest.yaml(b1).withIdempotencyKey("k-102"));
                firstHoldsKey.countDown();
                await(release);
                return v;
            }));
            assertThat(firstHoldsKey.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> engines.versionService().importVersion("card-fee",
                    ImportRequest.yaml(b1).withIdempotencyKey("k-102")))
                    .isInstanceOf(IdempotencyInFlightException.class);
            release.countDown();
            RuleSetVersion second = running.get(10, TimeUnit.SECONDS);
            assertThat(engines.versionService().importVersion("card-fee", ImportRequest.yaml(b1).withIdempotencyKey("k-102")))
                    .isEqualTo(second);
            assertThat(count("rle_version")).isEqualTo(2);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void RLE_002_unique_hit_policy_returns_one_match_no_match_on_zero_and_fails_on_several() {
        // Given
        publish("/rules/loan-pricing.yaml", "loan-pricing");
        caller.as("bank-a", "teller-01");

        // When
        DecisionResult overlap = engines.ruleEngine().evaluate("loan-pricing",
                DecisionInput.of(Map.of("score", 750, "income", Money.ofMinor("VND", 25_000_000))));
        DecisionResult none = engines.ruleEngine().evaluate("loan-pricing",
                DecisionInput.of(Map.of("score", 500, "income", Money.ofMinor("VND", 5_000_000))));

        // Then
        assertThat(overlap.outputs()).isEmpty();
        assertThat(none.outputs()).containsOnlyKeys("rate").containsEntry("rate", null);
        assertThat(JdbcClient.create(db).sql("""
                SELECT outcome || ' ' || error_code || ' ' || matched_rule_ids::text || ' ' || COALESCE(outputs::text, 'no-outputs')
                FROM rle_decision WHERE tenant_id = 'bank-a' AND rule_set_name = 'loan-pricing' AND error_code IS NOT NULL
                """).query(String.class).list())
                .containsExactlyInAnyOrder("UNIQUE_HIT_VIOLATION UNIQUE_HIT_VIOLATION [\"R1\", \"R2\"] no-outputs");
        // Zero matches is a no-match, logged as OK with [] exactly as under first (PD-088)
        assertThat(JdbcClient.create(db).sql("""
                SELECT outcome || ' ' || matched_rule_ids::text FROM rle_decision
                WHERE tenant_id = 'bank-a' AND rule_set_name = 'loan-pricing' AND error_code IS NULL
                """).query(String.class).single()).isEqualTo("OK []");
    }

    @Test
    void RLE_003_collect_hit_policy_keeps_null_entries_and_aggregates_non_null_values() {
        publish("/rules/risk-flags.yaml", "risk-flags");
        caller.as("bank-a", "teller-01");

        DecisionResult result = engines.ruleEngine().evaluate("risk-flags",
                DecisionInput.of(Map.of("txPerHour", 12, "country", "VN", "newDevice", true)));

        assertThat(result.matchedRuleIds()).containsExactly("R1", "R3");
        assertThat(JdbcClient.create(db).sql("SELECT outputs::text FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id")
                .param("id", result.decisionId()).query(String.class).single())
                .isEqualTo("{\"flags\": [\"HIGH_VELOCITY\", \"NEW_DEVICE\"], \"penalty\": 15}");
    }

    @Test
    void RLE_009_maker_checker_blocks_self_approval() {
        // Given
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());

        // When / Then
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(engines.versionService().find("card-fee", 1, false).orElseThrow().status())
                .isEqualTo(VersionStatus.PENDING_APPROVAL);
        caller.as("bank-a", "bob");
        assertThat(engines.versionService().approve("card-fee", 1, WriteOptions.none()).status())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_009_the_database_refuses_a_self_approval_written_around_the_service() {
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());

        assertThatThrownBy(() -> JdbcClient.create(db)
                .sql("UPDATE rle_version SET status = 'PUBLISHED', approved_by = 'alice' WHERE tenant_id = 'bank-a'")
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void RLE_009_the_database_refuses_approval_by_the_author_written_around_the_service() {
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee()));
        caller.as("bank-a", "bob");
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());

        assertThat(JdbcClient.create(db).sql("SELECT created_by || ' ' || requested_by FROM rle_version WHERE tenant_id = 'bank-a'")
                .query(String.class).single()).isEqualTo("alice bob");
        assertThatThrownBy(() -> JdbcClient.create(db)
                .sql("UPDATE rle_version SET status = 'PUBLISHED', approved_by = 'alice' WHERE tenant_id = 'bank-a'")
                .update()).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("rle_version_four_eyes");
        assertThat(JdbcClient.create(db)
                .sql("UPDATE rle_version SET status = 'PUBLISHED', approved_by = 'carol' WHERE tenant_id = 'bank-a'")
                .update()).isEqualTo(1);
    }

    @Test
    void TR_SEC_05_undeclared_inputs_never_reach_rle_decision() {
        publishCardFee();
        caller.as("bank-a", "teller-01");

        RuleEngines lenient = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                caller, caller, Clock.fixed(NOW, ZoneOffset.UTC), EngineSettings.defaults().withRejectUndeclaredInputs(false));
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5, "nationalId", "079090001234"))))
                .isInstanceOf(com.horus.bpm.rules.api.InputInvalidException.class);

        lenient.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5, "nationalId", "079090001234")));

        assertThat(JdbcClient.create(db).sql("SELECT masked_inputs::text FROM rle_decision WHERE tenant_id = 'bank-a'")
                .query(String.class).single()).isEqualTo("{\"tier\": \"GOLD\", \"txCount\": 5}");
    }

    @Test
    void RLE_020_import_keeps_source_document_and_canonical_hash() {
        byte[] file = cardFee();

        engines.versionService().importRuleSet(ImportRequest.yaml(file));

        assertThat(JdbcClient.create(db).sql("SELECT source_format FROM rle_version WHERE tenant_id = 'bank-a'")
                .query(String.class).single()).isEqualTo("YAML");
        assertThat(JdbcClient.create(db).sql("SELECT source_document FROM rle_version WHERE tenant_id = 'bank-a'")
                .query(String.class).single().getBytes(StandardCharsets.UTF_8)).isEqualTo(file);
        assertThat(JdbcClient.create(db).sql("SELECT string_agg(rule_id, ',' ORDER BY row_order) FROM rle_rule WHERE tenant_id = 'bank-a'")
                .query(String.class).single()).isEqualTo("R1,R2,R3");
    }

    @Test
    void TR_TEN_01_another_tenant_neither_sees_nor_evaluates_the_rule_set() {
        publishCardFee();
        caller.as("bank-b", "carol");

        assertThat(engines.versionService().find("card-fee", 1, true)).isEmpty();
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD"))))
                .isInstanceOf(RuleSetNotFoundException.class);
        assertThat(decisionRows()).isZero();
    }

    private void publish(String resource, String name) {
        caller.as("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(resource(resource)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.as("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
    }

    private void publishCardFee() {
        caller.as("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee()));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.as("bank-a", "bob");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
    }

    private long decisionRows() {
        return count("rle_decision");
    }

    private long count(String table) {
        return JdbcClient.create(db).sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static byte[] cardFee() {
        return resource("/rules/card-fee.yaml");
    }

    static byte[] resource(String path) {
        try (InputStream in = RuleEnginePostgresIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataSource freshDatabase() {
        String name = "rle_engine_it_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSourceFor(name);
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

    /** The host security context: same for every thread of a test. */
    static final class Caller implements TenantResolver, CallerResolver {
        private volatile String tenant = "bank-a";
        private volatile String user = "alice";

        void as(String tenant, String user) {
            this.tenant = tenant;
            this.user = user;
        }

        @Override
        public String currentTenantId() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return user;
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read");
        }
    }

    /** Records the SQL of every prepared statement, to count the engine's inserts. */
    static final class SqlCapturingDataSource extends DelegatingDataSource {
        private final List<String> statements;

        SqlCapturingDataSource(DataSource target, List<String> statements) {
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
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }
}
