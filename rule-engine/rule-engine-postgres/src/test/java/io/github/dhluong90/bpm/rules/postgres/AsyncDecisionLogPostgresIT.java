package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogBacklogException;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.EditorApiPostgresIT.Movable;
import io.github.dhluong90.bpm.rules.postgres.RetireVersionPostgresIT.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-230 (#45): {@code decision-log.mode: async} on PostgreSQL. A decision is queued in the caller's transaction, so it
 * is durable at the caller's commit and gone with a rollback; a relay moves it into the log exactly once; replay reads
 * it either way; and a queue over its limit refuses the evaluation with a named code instead of dropping it.
 */
@Testcontainers
class AsyncDecisionLogPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate", "rules:read", "rules:log:read");

    final Caller caller = new Caller();
    final Movable clock = new Movable(Instant.parse("2027-02-01T09:00:00Z"));
    DataSource db;
    JdbcClient jdbc;
    DataSourceTransactionManager transactionManager;
    RuleEngineTransactions transactions;

    @BeforeEach
    void setUp() {
        String name = "asynclog_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureCurrentAndNextMonth(clock.instant());
        jdbc = JdbcClient.create(db);
        transactionManager = new DataSourceTransactionManager(db);
        transactions = new RuleEngineTransactions(transactionManager);
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    AsyncDecisionLog asyncLog(int batchSize, int maxBacklog) {
        return new AsyncDecisionLog(db, transactions, clock, batchSize, maxBacklog);
    }

    RuleEngines engine(AsyncDecisionLog log) {
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock,
                EngineSettings.defaults().withRequireContentHash(false), 
                io.github.dhluong90.bpm.rules.spi.MaskingPolicy.none(),
                io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings.defaults(),
                io.github.dhluong90.bpm.rules.internal.service.CatalogueExplainer.bundled(), List.of(),
                io.github.dhluong90.bpm.rules.internal.service.HostFunctions.VersionChange.WARN,
                io.github.dhluong90.bpm.rules.spi.ApprovalSealKey.none(), log);
        engines.cursorKeys().load();
        return engines;
    }

    void publishCardFee(RuleEngines engine) {
        caller.as("bank-a", "alice", AUTHOR);
        engine.versionService().importRuleSet(ImportRequest.yaml(("""
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "50000 VND" } }
                """).getBytes(StandardCharsets.UTF_8)));
        long lock = engine.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "bob", APPROVER);
        engine.versionService().approve("card-fee", 1, new WriteOptions(null, lock, null));
        caller.as("bank-a", "teller", TELLER);
    }

    DecisionResult evaluate(RuleEngines engine, String tier) {
        return engine.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", tier)));
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Test
    void RLE_230_a_decision_is_queued_at_once_readable_and_replayable_and_the_relay_moves_it_into_the_log_once() {
        // Given a card-fee rule set and an async log
        AsyncDecisionLog log = asyncLog(100, 100_000);
        RuleEngines engine = engine(log);
        publishCardFee(engine);

        // When a teller evaluates a GOLD customer
        DecisionResult first = evaluate(engine, "GOLD");

        // Then the decision is in the queue and not yet in the log, and it can be read and replayed by its id
        assertThat(count("rle_decision_queue")).isEqualTo(1);
        assertThat(count("rle_decision")).isZero();
        assertThat(engine.decisionLog().get(first.decisionId()).outcome()).isEqualTo("OK");
        assertThat(engine.ruleEngine().replay(first.decisionId()).outcome()).isEqualTo(first.outcome());

        // When the relay runs
        assertThat(log.relayOnce()).isEqualTo(1);

        // Then the row is in the log, marked ASYNC, with the id, time and clock it had, the queue is empty, and the
        // replay gives the same result as before
        assertThat(count("rle_decision_queue")).isZero();
        assertThat(jdbc.sql("SELECT log_mode FROM rle_decision WHERE id = :id").param("id", first.decisionId())
                .query(String.class).single()).isEqualTo("ASYNC");
        assertThat(engine.decisionLog().get(first.decisionId()).evaluatedAt()).isEqualTo(clock.instant());
        assertThat(engine.ruleEngine().replay(first.decisionId()).outcome()).isEqualTo(first.outcome());
        assertThat(log.relayOnce()).isZero();
        assertThat(count("rle_decision")).isEqualTo(1);
        assertThat(log.appendedCount()).isEqualTo(1);
        assertThat(log.relayedCount()).isEqualTo(1);
    }

    @Test
    void RLE_230_a_rolled_back_business_transaction_leaves_no_queued_decision_and_a_committed_one_keeps_it() {
        AsyncDecisionLog log = asyncLog(100, 100_000);
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        TransactionTemplate host = new TransactionTemplate(transactionManager);

        host.executeWithoutResult(status -> {
            evaluate(engine, "GOLD");
            status.setRollbackOnly();
        });
        assertThat(count("rle_decision_queue")).isZero();

        host.executeWithoutResult(status -> evaluate(engine, "STANDARD"));
        assertThat(count("rle_decision_queue")).isEqualTo(1);
        assertThat(log.relayOnce()).isEqualTo(1);
        assertThat(count("rle_decision")).isEqualTo(1);
    }

    @Test
    void RLE_230_a_queue_over_its_limit_refuses_the_evaluation_with_a_named_code_and_drops_nothing() {
        // Given a limit of 100 queued decisions, checked every 256 decisions
        AsyncDecisionLog log = asyncLog(100, 100);
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        for (int i = 0; i < 256; i++) {
            evaluate(engine, "GOLD");
        }
        assertThat(log.depth()).isEqualTo(101);

        // When another is evaluated
        // Then it is refused, counted, written nowhere, and the 256 queued ones are all still there
        assertThatThrownBy(() -> evaluate(engine, "GOLD")).isInstanceOfSatisfying(DecisionLogBacklogException.class,
                e -> {
                    assertThat(e.code()).isEqualTo("DECISION_LOG_BACKLOG");
                    assertThat(e.limit()).isEqualTo(100);
                });
        assertThat(log.refusedCount()).isEqualTo(1);
        assertThat(count("rle_decision_queue")).isEqualTo(256);

        // When the relay catches up
        assertThat(log.relayOnce()).isEqualTo(256);

        // Then evaluations are accepted again and every decision is in the log
        DecisionResult after = evaluate(engine, "GOLD");
        assertThat(after.decisionId()).isNotBlank();
        assertThat(log.relayOnce()).isEqualTo(1);
        assertThat(count("rle_decision")).isEqualTo(257);
        assertThat(log.depth()).isZero();
    }

    @Test
    void RLE_230_two_relays_running_together_write_every_queued_decision_exactly_once() throws Exception {
        AsyncDecisionLog log = asyncLog(50, 100_000);
        AsyncDecisionLog other = asyncLog(50, 100_000);
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        for (int i = 0; i < 300; i++) {
            evaluate(engine, i % 2 == 0 ? "GOLD" : "STANDARD");
        }

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit((Callable<Integer>) log::relayOnce);
            Future<Integer> b = pool.submit((Callable<Integer>) other::relayOnce);
            assertThat(a.get() + b.get()).isEqualTo(300);
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("rle_decision")).isEqualTo(300);
        assertThat(jdbc.sql("SELECT count(DISTINCT id) FROM rle_decision").query(Long.class).single()).isEqualTo(300);
        assertThat(count("rle_decision_queue")).isZero();
    }

    @Test
    void RLE_230_a_batch_the_log_refuses_stays_queued_and_the_failure_is_counted() {
        AsyncDecisionLog log = asyncLog(100, 100_000);
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        evaluate(engine, "GOLD");
        jdbc.sql("""
                INSERT INTO rle_decision_queue (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                  outcome, masked_inputs, matched_rule_ids, eval_clock, duration_micros)
                VALUES ('bank-a', now(), '01KBX9Q3M4R7T2V8W6Y0Z1A2B3', 'card-fee', 1, 'FIRST', 'NOT_AN_OUTCOME', '{}', '[]',
                  now(), 1)
                """).update();

        assertThatThrownBy(log::relayOnce).isInstanceOf(RuntimeException.class);

        assertThat(count("rle_decision_queue")).isEqualTo(2);
        assertThat(count("rle_decision")).isZero();
        assertThat(log.relayFailureCount()).isEqualTo(1);

        jdbc.sql("DELETE FROM rle_decision_queue WHERE outcome = 'NOT_AN_OUTCOME'").update();
        assertThat(log.relayOnce()).isEqualTo(1);
        assertThat(count("rle_decision")).isEqualTo(1);
    }

    @Test
    void RLE_230_the_queue_is_not_used_in_the_default_transactional_mode() {
        RuleEngines engine = PostgresRuleEngines.create(db, transactions, caller, caller, clock,
                EngineSettings.defaults().withRequireContentHash(false));
        engine.cursorKeys().load();
        publishCardFee(engine);

        evaluate(engine, "GOLD");

        assertThat(count("rle_decision")).isEqualTo(1);
        assertThat(count("rle_decision_queue")).isZero();
        assertThat(jdbc.sql("SELECT log_mode FROM rle_decision").query(String.class).single()).isEqualTo("TRANSACTIONAL");
    }
}
