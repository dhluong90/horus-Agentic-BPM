package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.DecisionLogBacklogException;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The asynchronous decision log, {@code decision-log.mode: async} (RLE-230, #45). It is opt-in; the default stays the
 * transactional {@link JdbcDecisionLog}.
 *
 * <p>An evaluation writes its decision to the narrow queue table {@code rle_decision_queue} in the caller's own
 * transaction, instead of inserting into the partitioned, indexed {@code rle_decision}. The queue row is therefore
 * durable when the caller's commit is acknowledged, and it rolls back with the caller, so a rolled-back business
 * transaction leaves no decision, as in the transactional mode. {@link #relayOnce} then moves queued rows into
 * {@code rle_decision} in batches, marked {@code log_mode = 'ASYNC'}, in one statement that deletes the queue row and
 * inserts the log row together: a row is neither lost nor written twice, and a failed batch stays queued.
 *
 * <p>Nothing is dropped silently. When the queue holds more than {@code max-backlog} rows an evaluation is refused with
 * {@code 503 DECISION_LOG_BACKLOG} ({@link DecisionLogBacklogException}) and counted; the depth, the relayed, refused and
 * failed counts are available for metrics. Reads see a decision from the moment it is queued: {@link #find} looks in the
 * queue when the log has no row yet, so replay of a decision id the caller already holds is unchanged. Searches list
 * only relayed rows, so they lag by about one poll interval.
 */
public final class AsyncDecisionLog implements DecisionLog {

    private static final int DEPTH_CHECK_EVERY = 256;
    private static final long REFUSING_RECHECK_NANOS = Duration.ofSeconds(1).toNanos();
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final DecisionLog delegate;
    private final JdbcClient jdbc;
    private final RuleEngineTransactions transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxBacklog;

    private final AtomicLong appended = new AtomicLong();
    private final AtomicLong relayed = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong relayFailures = new AtomicLong();
    private volatile long depth;
    private volatile boolean refusing;
    private volatile long lastDepthCheckNanos = System.nanoTime();
    private volatile Instant lastRelay;
    private volatile Instant oldestQueuedAt;

    /**
     * Creates the log on the host's data source.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, for the relay's own transactions
     * @param clock the engine clock
     * @param batchSize rows the relay moves in one transaction, 1 to 10,000
     * @param maxBacklog the queue depth above which an evaluation is refused, at least 1
     */
    public AsyncDecisionLog(DataSource dataSource, RuleEngineTransactions transactions, Clock clock, int batchSize,
            int maxBacklog) {
        if (batchSize < 1 || batchSize > 10_000) {
            throw new IllegalArgumentException("decision-log.async.batch-size must be 1 to 10000, was " + batchSize);
        }
        if (maxBacklog < 1) {
            throw new IllegalArgumentException("decision-log.async.max-backlog must be at least 1, was " + maxBacklog);
        }
        this.delegate = new JdbcDecisionLog(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.batchSize = batchSize;
        this.maxBacklog = maxBacklog;
    }

    /**
     * {@inheritDoc}
     *
     * <p>One {@code INSERT} into the queue, in the caller's transaction.
     *
     * @throws DecisionLogBacklogException when the queue holds more than {@code max-backlog} rows
     */
    @Override
    public void append(DecisionRecord r) {
        guard();
        jdbc.sql("""
                INSERT INTO rle_decision_queue (tenant_id, created_at, id, rule_set_name, version_num, hit_policy,
                  outcome, error_code, masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock,
                  duration_micros, pinned, on_behalf_of, function_versions, budget_limit)
                VALUES (:tenant, :createdAt, :id, :ruleSet, :version, :hitPolicy, :outcome, :errorCode,
                  CAST(:inputs AS jsonb), CAST(:outputs AS jsonb), CAST(:matched AS jsonb), :caller, :correlation,
                  :clock, :micros, :pinned, :onBehalfOf, :functionVersions, :budgetLimit)
                """)
                .param("tenant", r.tenantId()).param("createdAt", Timestamp.from(r.createdAt())).param("id", r.id())
                .param("ruleSet", r.ruleSet()).param("version", r.version()).param("hitPolicy", r.hitPolicy())
                .param("outcome", r.outcome()).param("errorCode", r.errorCode())
                .param("inputs", r.maskedInputsJson()).param("outputs", r.outputsJson())
                .param("matched", r.matchedRuleIdsJson()).param("caller", r.callerId())
                .param("correlation", r.correlationId()).param("clock", Timestamp.from(r.evalClock()))
                .param("micros", r.durationMicros()).param("pinned", r.pinned())
                .param("onBehalfOf", r.onBehalfOf()).param("functionVersions", r.functionVersions())
                .param("budgetLimit", r.budgetLimit())
                .update();
        if (appended.incrementAndGet() % DEPTH_CHECK_EVERY == 0) {
            refreshDepth();
        }
    }

    /** Refuses when the last count found the queue over its limit; while refusing, counts again every second. */
    private void guard() {
        if (!refusing) {
            return;
        }
        if (System.nanoTime() - lastDepthCheckNanos > REFUSING_RECHECK_NANOS) {
            refreshDepth();
        }
        if (refusing) {
            refused.incrementAndGet();
            throw new DecisionLogBacklogException(depth, maxBacklog);
        }
    }

    /**
     * Counts the queue, up to one more than the limit, and decides whether evaluations are refused.
     *
     * @return the depth counted, at most {@code max-backlog + 1}
     */
    public long refreshDepth() {
        long counted = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM rle_decision_queue LIMIT :cap) capped")
                .param("cap", (long) maxBacklog + 1).query(Long.class).single();
        depth = counted;
        refusing = counted > maxBacklog;
        lastDepthCheckNanos = System.nanoTime();
        return counted;
    }

    /**
     * Moves queued decisions into {@code rle_decision}, oldest first, in batches of {@code batch-size}, each batch in
     * a transaction of its own; stops when the queue is empty or after 50 batches, so one run is bounded. Replicas may
     * run it together: a batch locks its rows with {@code FOR UPDATE SKIP LOCKED}.
     *
     * @return the number of decisions moved
     * @throws RuntimeException when the database refuses a batch; its rows stay queued and the next run retries
     */
    public int relayOnce() {
        int total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                int moved = transactions.inIndependentTransaction(this::moveBatch);
                total += moved;
                if (moved < batchSize) {
                    break;
                }
            }
            relayed.addAndGet(total);
            refreshDepth();
            oldestQueuedAt = jdbc.sql("SELECT min(queued_at) FROM rle_decision_queue").query(Timestamp.class)
                    .optional().map(Timestamp::toInstant).orElse(null);
            lastRelay = clock.instant();
            return total;
        } catch (RuntimeException e) {
            relayed.addAndGet(total);
            relayFailures.incrementAndGet();
            throw e;
        }
    }

    private int moveBatch() {
        return jdbc.sql("""
                WITH batch AS (
                  SELECT tenant_id, id FROM rle_decision_queue ORDER BY queue_seq LIMIT :n FOR UPDATE SKIP LOCKED
                ), moved AS (
                  DELETE FROM rle_decision_queue q USING batch b WHERE q.tenant_id = b.tenant_id AND q.id = b.id
                  RETURNING q.*
                )
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                  error_code, masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock,
                  duration_micros, log_mode, pinned, on_behalf_of, function_versions, budget_limit)
                SELECT tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome, error_code,
                  masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock, duration_micros,
                  'ASYNC', pinned, on_behalf_of, function_versions, budget_limit
                FROM moved
                """).param("n", batchSize).update();
    }

    /** @return the queue depth at the last count, at most {@code max-backlog + 1} */
    public long depth() {
        return depth;
    }

    /** @return decisions queued by this replica since start */
    public long appendedCount() {
        return appended.get();
    }

    /** @return decisions this replica moved into the log since start */
    public long relayedCount() {
        return relayed.get();
    }

    /** @return evaluations this replica refused with {@code DECISION_LOG_BACKLOG} since start */
    public long refusedCount() {
        return refused.get();
    }

    /** @return relay runs of this replica that failed since start */
    public long relayFailureCount() {
        return relayFailures.get();
    }

    /** @return when the last relay run finished, or null before the first */
    public Instant lastRelay() {
        return lastRelay;
    }

    /** @return when the oldest queued decision was queued at the last relay run, or null when the queue was empty */
    public Instant oldestQueuedAt() {
        return oldestQueuedAt;
    }

    /** @return the limit above which evaluations are refused */
    public int maxBacklog() {
        return maxBacklog;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A decision still in the queue is found there, so a caller that holds its id can read and replay it at once.
     */
    @Override
    public Optional<DecisionRecord> find(String tenantId, String decisionId) {
        Optional<DecisionRecord> logged = delegate.find(tenantId, decisionId);
        if (logged.isPresent()) {
            return logged;
        }
        return jdbc.sql("SELECT " + RuleListQueries.DECISION_COLUMNS
                + " FROM rle_decision_queue WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", decisionId)
                .query(JdbcDecisionLog::record).optional();
    }

    @Override
    public List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, List<String> texts,
            Instant from, Instant to, int limit) {
        return delegate.findByLoggedInput(tenantId, ruleSet, input, texts, from, to, limit);
    }

    @Override
    public List<DecisionRecord> search(String tenantId, String ruleSet, Instant from, Instant to, Position after,
            int limit, Duration statementTimeout) {
        return delegate.search(tenantId, ruleSet, from, to, after, limit, statementTimeout);
    }

    @Override
    public List<DecisionRecord> searchByCorrelationId(String tenantId, String correlationId, String ruleSet,
            Instant from, Instant to, Position after, int limit, Duration statementTimeout) {
        return delegate.searchByCorrelationId(tenantId, correlationId, ruleSet, from, to, after, limit,
                statementTimeout);
    }

    @Override
    public List<DecisionRecord> searchByPlainInput(String tenantId, String ruleSet, String input, String valueJson,
            Instant from, Instant to, Position after, int limit, Duration statementTimeout) {
        return delegate.searchByPlainInput(tenantId, ruleSet, input, valueJson, from, to, after, limit,
                statementTimeout);
    }

    @Override
    public long countInPeriod(String tenantId, String ruleSet, Instant from, Instant to, int atMost,
            Duration statementTimeout) {
        return delegate.countInPeriod(tenantId, ruleSet, from, to, atMost, statementTimeout);
    }
}
