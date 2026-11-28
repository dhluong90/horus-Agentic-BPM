package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DeadLetter;
import io.github.dhluong90.bpm.rules.api.DeadLetterAction;
import io.github.dhluong90.bpm.rules.api.DeadLetterState;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.PermissionDeniedException;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.EditorApiPostgresIT.Movable;
import io.github.dhluong90.bpm.rules.postgres.RetireVersionPostgresIT.Caller;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-235 (#45): the asynchronous decision log's queue is append-only; a row the log refuses is retried with back-off
 * and then dead-lettered without holding back the rows behind it; a dead letter is visible through the API and a
 * metric; and an operator retries or resolves it with a second person's approval, audited, never by deleting it.
 */
@Testcontainers
class DecisionLogDeadLetterPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate", "rules:read", "rules:log:read");
    static final Set<String> OPERATOR = Set.of("rules:log:read", "rules:log:operate");
    static final Set<String> OPERATOR_LEAD = Set.of("rules:log:read", "rules:log:operate", "rules:log:approve");
    static final String POISON = "01KBX9Q3M4R7T2V8W6Y0Z1A2B3";
    static final String BLOCKED = "01KBX9Q3M4R7T2V8W6Y0Z1A2B4";
    static final String BLOCKED_TWO = "01KBX9Q3M4R7T2V8W6Y0Z1A2B5";

    final Caller caller = new Caller();
    final Movable clock = new Movable(Instant.parse("2027-02-01T09:00:00Z"));
    DataSource db;
    JdbcClient jdbc;
    RuleEngineTransactions transactions;

    @BeforeEach
    void setUp() {
        String name = "deadletter_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureCurrentAndNextMonth(clock.instant());
        jdbc = JdbcClient.create(db);
        transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** Three attempts, five seconds doubling to five minutes. */
    AsyncDecisionLog log() {
        return new AsyncDecisionLog(db, transactions, clock, 100, 100_000, 3, Duration.ofSeconds(5),
                Duration.ofMinutes(5));
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

    void evaluate(RuleEngines engine, String tier) {
        engine.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", tier)));
    }

    /** A queued decision the log refuses: its outcome is not one the log accepts. */
    void queuePoison(String id) {
        queue(id, "card-fee", "NOT_AN_OUTCOME");
    }

    /** A queued decision that is fine, but of a rule set the log is blocked for (see {@link #blockRuleSet}). */
    void queueBlocked(String id) {
        queue(id, "blocked-set", "OK");
    }

    void queue(String id, String ruleSet, String outcome) {
        jdbc.sql("""
                INSERT INTO rle_decision_queue (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                  outcome, masked_inputs, matched_rule_ids, eval_clock, duration_micros)
                VALUES ('bank-a', :now, :id, :ruleSet, 1, 'FIRST', :outcome, '{}', '[]', :now, 1)
                """).param("now", java.sql.Timestamp.from(clock.instant())).param("id", id).param("ruleSet", ruleSet)
                .param("outcome", outcome).update();
    }

    void blockRuleSet() {
        jdbc.sql("ALTER TABLE rle_decision ADD CONSTRAINT tmp_blocked CHECK (rule_set_name <> 'blocked-set')").update();
    }

    void unblockRuleSet() {
        jdbc.sql("ALTER TABLE rle_decision DROP CONSTRAINT tmp_blocked").update();
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    void advance(Duration by) {
        clock.set(clock.instant().plus(by));
    }

    @Test
    void RLE_235_the_queue_and_its_events_refuse_update_delete_and_truncate_of_a_row_not_yet_in_the_log() {
        AsyncDecisionLog log = log();
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        evaluate(engine, "GOLD");
        queuePoison(POISON);

        // The payload cannot be changed or deleted by any statement, whoever sends it
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_decision_queue SET outcome = 'OK'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_decision_queue SET masked_inputs = '{}'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_decision_queue WHERE id = :id").param("id", POISON).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_decision_queue").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE rle_decision_queue").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(count("SELECT count(*) FROM rle_decision_queue")).isEqualTo(2);

        // A step is a new event row; the event table itself takes no UPDATE, DELETE or TRUNCATE
        log.relayOnce();
        assertThat(count("SELECT count(*) FROM rle_decision_queue_event")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_decision_queue_event SET detail = 'x'").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_decision_queue_event").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE rle_decision_queue_event").update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        // The relay's own move is the one DELETE allowed, because the same decision is already in the log
        assertThat(count("SELECT count(*) FROM rle_decision")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rle_decision_queue")).isEqualTo(1);
    }

    @Test
    void RLE_235_a_poison_row_is_retried_with_backoff_then_dead_lettered_and_later_rows_still_move() {
        AsyncDecisionLog log = log();
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        evaluate(engine, "GOLD");
        queuePoison(POISON);
        evaluate(engine, "SILVER");

        // Attempt 1: the two good decisions move, around the bad one, which waits five seconds
        assertThat(log.relayOnce()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM rle_decision")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM rle_decision_queue")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT kind || ':' || attempt || ':' || (next_attempt_at - CAST(:now AS timestamptz))::text "
                + "FROM rle_decision_queue_event").param("now", java.sql.Timestamp.from(clock.instant()))
                .query(String.class).single()).isEqualTo("ATTEMPT_FAILED:1:00:00:05");

        // Before its back-off ends it is not tried again, and a decision queued now still moves
        advance(Duration.ofSeconds(4));
        evaluate(engine, "GOLD");
        assertThat(log.relayOnce()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rle_decision_queue_event")).isEqualTo(1);

        // Attempt 2 after 5 s, which waits 10 s (the back-off doubles); attempt 3 is the last: dead letter
        advance(Duration.ofSeconds(1));
        assertThat(log.relayOnce()).isZero();
        assertThat(jdbc.sql("SELECT string_agg(kind || ':' || attempt, ',' ORDER BY event_seq) FROM "
                + "rle_decision_queue_event").query(String.class).single()).isEqualTo("ATTEMPT_FAILED:1,ATTEMPT_FAILED:2");
        advance(Duration.ofSeconds(10));
        assertThat(log.relayOnce()).isZero();
        assertThat(jdbc.sql("SELECT string_agg(kind || ':' || attempt, ',' ORDER BY event_seq) FROM "
                + "rle_decision_queue_event").query(String.class).single())
                .isEqualTo("ATTEMPT_FAILED:1,ATTEMPT_FAILED:2,DEAD_LETTERED:3");

        // A dead letter is never tried again and holds back nothing: later decisions move on the next run
        evaluate(engine, "SILVER");
        advance(Duration.ofHours(1));
        assertThat(log.relayOnce()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rle_decision_queue_event")).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM rle_decision")).isEqualTo(4);
        // and it is kept whole: in the queue, readable by its id, not counted against the backlog
        assertThat(count("SELECT count(*) FROM rle_decision_queue WHERE id = '" + POISON + "'")).isEqualTo(1);
        assertThat(log.refreshDepth()).isZero();
        assertThat(log.deadLetterDepth()).isEqualTo(1);
        assertThat(log.deadLetteredCount()).isEqualTo(1);
    }

    @Test
    void RLE_235_the_backoff_doubles_from_the_base_up_to_its_ceiling() {
        AsyncDecisionLog log = new AsyncDecisionLog(db, transactions, clock, 100, 100_000, 20, Duration.ofSeconds(5),
                Duration.ofSeconds(60));
        assertThat(List.of(1, 2, 3, 4, 5, 6, 19).stream().map(log::backoff).map(Duration::toSeconds).toList())
                .containsExactly(5L, 10L, 20L, 40L, 60L, 60L, 60L);
        assertThatThrownBy(() -> new AsyncDecisionLog(db, transactions, clock, 100, 100_000, 0, Duration.ofSeconds(5),
                Duration.ofSeconds(60))).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new AsyncDecisionLog(db, transactions, clock, 100, 100_000, 3, Duration.ofSeconds(5),
                Duration.ofSeconds(1))).hasMessageContaining("retry-backoff");
    }

    @Test
    void RLE_235_a_dead_letter_is_listed_with_why_it_failed_for_its_own_tenant_only() {
        AsyncDecisionLog log = log();
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        queuePoison(POISON);
        for (int i = 0; i < 3; i++) {
            log.relayOnce();
            advance(Duration.ofMinutes(1));
        }

        caller.as("bank-a", "olivia", OPERATOR);
        List<DeadLetter> letters = engine.decisionLog().deadLetters(100);

        assertThat(letters).singleElement().satisfies(letter -> {
            assertThat(letter.decisionId()).isEqualTo(POISON);
            assertThat(letter.ruleSet()).isEqualTo("card-fee");
            assertThat(letter.attempts()).isEqualTo(3);
            assertThat(letter.state()).isEqualTo(DeadLetterState.DEAD);
            assertThat(letter.lastError()).contains("rle_decision_outcome_check").doesNotContain("NOT_AN_OUTCOME");
            assertThat(letter.requestedBy()).isNull();
        });
        // the decision can still be read by its id, from the queue
        assertThat(engine.decisionLog().get(POISON).ruleSet()).isEqualTo("card-fee");
        // another tenant sees nothing, and cannot act on it
        caller.as("bank-b", "olga", OPERATOR_LEAD);
        assertThat(engine.decisionLog().deadLetters(100)).isEmpty();
        assertThatThrownBy(() -> engine.decisionLog().requestDeadLetterAction(POISON, DeadLetterAction.RETRY,
                "not mine", null)).isInstanceOf(DecisionNotFoundException.class);
        // a caller without the read permission is refused
        caller.as("bank-a", "teller", Set.of("rules:evaluate"));
        assertThatThrownBy(() -> engine.decisionLog().deadLetters(100)).isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void RLE_235_a_retry_needs_a_second_person_is_audited_and_moves_the_row_once_the_cause_is_fixed() {
        AsyncDecisionLog log = log();
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        blockRuleSet();
        queueBlocked(BLOCKED);
        for (int i = 0; i < 3; i++) {
            log.relayOnce();
            advance(Duration.ofMinutes(1));
        }
        assertThat(log.deadLetterDepth()).isEqualTo(1);

        // Asking needs rules:log:operate and a reason
        caller.as("bank-a", "teller", TELLER);
        assertThatThrownBy(() -> engine.decisionLog().requestDeadLetterAction(BLOCKED, DeadLetterAction.RETRY,
                "partition fixed", null)).isInstanceOf(PermissionDeniedException.class);
        caller.as("bank-a", "olivia", OPERATOR_LEAD);
        assertThatThrownBy(() -> engine.decisionLog().requestDeadLetterAction(BLOCKED, DeadLetterAction.RETRY, " ", null))
                .hasMessageContaining("comment");
        DeadLetter asked = engine.decisionLog().requestDeadLetterAction(BLOCKED, DeadLetterAction.RETRY,
                "The DBA lifted the block on blocked-set", "key-1");
        assertThat(asked.state()).isEqualTo(DeadLetterState.RETRY_REQUESTED);
        assertThat(asked.requestedBy()).isEqualTo("olivia");

        // A repeat with the same key returns the first result and writes nothing
        assertThat(engine.decisionLog().requestDeadLetterAction(BLOCKED, DeadLetterAction.RETRY,
                "The DBA lifted the block on blocked-set", "key-1")).isEqualTo(asked);
        assertThat(count("SELECT count(*) FROM rle_decision_queue_event WHERE kind = 'RETRY_REQUESTED'")).isEqualTo(1);
        // Without a key a second request is refused by the state
        assertThatThrownBy(() -> engine.decisionLog().requestDeadLetterAction(BLOCKED, DeadLetterAction.RESOLVE,
                "again please", null)).isInstanceOf(InvalidStateException.class);

        // Nothing moves while it waits for a second person, and the requester cannot approve or reject their own
        unblockRuleSet();
        advance(Duration.ofHours(1));
        assertThat(log.relayOnce()).isZero();
        assertThatThrownBy(() -> engine.decisionLog().decideDeadLetterRequest(BLOCKED, true, null, null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> engine.decisionLog().decideDeadLetterRequest(BLOCKED, false, "not now", null))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        // The operate permission alone cannot approve
        caller.as("bank-a", "oscar", OPERATOR);
        assertThatThrownBy(() -> engine.decisionLog().decideDeadLetterRequest(BLOCKED, true, null, null))
                .isInstanceOf(PermissionDeniedException.class);

        // A second holder of rules:log:approve approves; the row waits for the relay again, attempts from zero
        caller.as("bank-a", "omar", OPERATOR_LEAD);
        assertThat(engine.decisionLog().decideDeadLetterRequest(BLOCKED, true, "Checked with the DBA", null))
                .isEqualTo("RETRY_APPROVED");
        assertThat(engine.decisionLog().deadLetters(100)).isEmpty();
        assertThat(log.relayOnce()).isEqualTo(1);

        // It is in the log now; the queue row left only because its copy is there; the history stays, append-only
        assertThat(count("SELECT count(*) FROM rle_decision WHERE id = '" + BLOCKED + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rle_decision_queue")).isZero();
        assertThat(jdbc.sql("SELECT string_agg(kind || ':' || coalesce(actor, '-'), ',' ORDER BY event_seq) FROM "
                + "rle_decision_queue_event").query(String.class).single()).isEqualTo(
                "ATTEMPT_FAILED:-,ATTEMPT_FAILED:-,DEAD_LETTERED:-,RETRY_REQUESTED:olivia,RETRY_APPROVED:omar");
        assertThat(jdbc.sql("SELECT comment FROM rle_decision_queue_event WHERE kind = 'RETRY_REQUESTED'")
                .query(String.class).single()).isEqualTo("The DBA lifted the block on blocked-set");
        // and every step went to the outbox as an audit event naming who and why
        List<String> audit = jdbc.sql("SELECT payload::text FROM rle_decision_outbox WHERE payload::text LIKE "
                + "'%dead-letter-action%' ORDER BY id").query(String.class).list();
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).contains("retry-requested", "olivia", BLOCKED);
        assertThat(audit.get(1)).contains("retry-approved", "omar", "Checked with the DBA");
    }

    @Test
    void RLE_235_a_resolve_closes_the_row_with_a_second_persons_approval_and_a_rejection_leaves_it_dead_and_nothing_deletes() {
        AsyncDecisionLog log = log();
        RuleEngines engine = engine(log);
        publishCardFee(engine);
        queuePoison(POISON);
        for (int i = 0; i < 3; i++) {
            log.relayOnce();
            advance(Duration.ofMinutes(1));
        }

        // A rejected request leaves the dead letter dead, and a new request may follow
        caller.as("bank-a", "olivia", OPERATOR);
        engine.decisionLog().requestDeadLetterAction(POISON, DeadLetterAction.RESOLVE, "Duplicate of a decision logged by hand", null);
        caller.as("bank-a", "omar", OPERATOR_LEAD);
        assertThat(engine.decisionLog().decideDeadLetterRequest(POISON, false, "Not a duplicate: check again", null))
                .isEqualTo("REQUEST_REJECTED");
        assertThat(engine.decisionLog().deadLetters(100)).singleElement()
                .satisfies(l -> assertThat(l.state()).isEqualTo(DeadLetterState.DEAD));
        // with nothing waiting, an approval has nothing to approve
        assertThatThrownBy(() -> engine.decisionLog().decideDeadLetterRequest(POISON, true, null, null))
                .isInstanceOf(InvalidStateException.class);

        caller.as("bank-a", "olivia", OPERATOR);
        engine.decisionLog().requestDeadLetterAction(POISON, DeadLetterAction.RESOLVE,
                "Checked: the decision was recorded in the ledger by hand", null);
        caller.as("bank-a", "omar", OPERATOR_LEAD);
        assertThat(engine.decisionLog().decideDeadLetterRequest(POISON, true, null, null)).isEqualTo("RESOLVE_APPROVED");

        // Closed: no longer listed, counted or retried; the row and its history are all still there
        assertThat(engine.decisionLog().deadLetters(100)).isEmpty();
        advance(Duration.ofHours(1));
        assertThat(log.relayOnce()).isZero();
        assertThat(log.deadLetterDepth()).isZero();
        assertThat(count("SELECT count(*) FROM rle_decision_queue WHERE id = '" + POISON + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM rle_decision_queue_event")).isEqualTo(7);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_decision_queue WHERE id = :id").param("id", POISON).update())
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }
}
