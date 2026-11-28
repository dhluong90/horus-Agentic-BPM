package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.DeadLetter;
import io.github.dhluong90.bpm.rules.api.DeadLetterState;
import io.github.dhluong90.bpm.rules.api.DecisionLogBacklogException;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionQueueAdmin;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
 * {@code rle_decision} in batches, marked {@code log_mode = 'ASYNC'}: the log row is inserted and the queue row leaves
 * in one transaction, and the database lets a queue row go only once the same decision is in the log, so a row is
 * neither lost nor written twice.
 *
 * <p>The queue is append-only (RLE-231): a trigger refuses every UPDATE of it, and every DELETE but that one. What
 * happens to a row is recorded as new rows of {@code rle_decision_queue_event}. A row the log refuses does not stall
 * the relay: the batch falls back to one row at a time, the bad row is retried with back-off up to {@code max-attempts}
 * and then dead-lettered, and the rows behind it go on. The decision log needs no order among decisions (a read orders
 * by time and id, never by queue position), so no row waits for another, in any tenant. A dead letter is listed by
 * {@link #deadLetters}, counted in a metric, and an operator retries or resolves it through a four-eyes request
 * ({@link DecisionQueueAdmin}); nothing deletes it.
 *
 * <p>Nothing is dropped silently. When the queue holds more than {@code max-backlog} rows an evaluation is refused with
 * {@code 503 DECISION_LOG_BACKLOG} ({@link DecisionLogBacklogException}) and counted; the depth, the relayed, refused and
 * failed counts are available for metrics. Reads see a decision from the moment it is queued: {@link #find} looks in the
 * queue when the log has no row yet, so replay of a decision id the caller already holds is unchanged. Searches list
 * only relayed rows, so they lag by about one poll interval.
 */
public final class AsyncDecisionLog implements DecisionLog, DecisionQueueAdmin {

    /** Attempts on a row the log refuses before it is dead-lettered. */
    public static final int DEFAULT_MAX_ATTEMPTS = 5;
    /** The wait after the first failed attempt. */
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(5);
    /** The longest wait between two attempts. */
    public static final Duration DEFAULT_MAX_RETRY_BACKOFF = Duration.ofMinutes(5);

    private static final int DEPTH_CHECK_EVERY = 256;
    private static final long REFUSING_RECHECK_NANOS = Duration.ofSeconds(1).toNanos();
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final DecisionLog delegate;
    private final JdbcClient jdbc;
    private final RuleEngineTransactions transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxBacklog;
    private final int maxAttempts;
    private final Duration retryBackoff;
    private final Duration maxRetryBackoff;

    private final AtomicLong appended = new AtomicLong();
    private final AtomicLong relayed = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong relayFailures = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private volatile long deadLetterCount;
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
        this(dataSource, transactions, clock, batchSize, maxBacklog, DEFAULT_MAX_ATTEMPTS, DEFAULT_RETRY_BACKOFF,
                DEFAULT_MAX_RETRY_BACKOFF);
    }

    /**
     * Creates the log with its retry policy.
     *
     * @param dataSource the host's data source
     * @param transactions the host's transaction manager, for the relay's own transactions
     * @param clock the engine clock
     * @param batchSize rows the relay moves in one transaction, 1 to 10,000
     * @param maxBacklog the queue depth above which an evaluation is refused, at least 1
     * @param maxAttempts attempts the relay makes on a row the log refuses before it dead-letters it, 1 to 100
     * @param retryBackoff the wait after the first failed attempt; it doubles with each further one
     * @param maxRetryBackoff the longest wait between two attempts, at least {@code retryBackoff}
     */
    public AsyncDecisionLog(DataSource dataSource, RuleEngineTransactions transactions, Clock clock, int batchSize,
            int maxBacklog, int maxAttempts, Duration retryBackoff, Duration maxRetryBackoff) {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("decision-log.async.max-attempts must be 1 to 100, was " + maxAttempts);
        }
        Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        Objects.requireNonNull(maxRetryBackoff, "maxRetryBackoff must not be null");
        if (retryBackoff.isNegative() || retryBackoff.isZero() || maxRetryBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException("decision-log.async.retry-backoff must be positive and at most "
                    + "max-retry-backoff, was " + retryBackoff + " and " + maxRetryBackoff);
        }
        this.maxAttempts = maxAttempts;
        this.retryBackoff = retryBackoff;
        this.maxRetryBackoff = maxRetryBackoff;
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
                  duration_micros, pinned, on_behalf_of, function_versions, budget_limit, step_budget, timeout_ms)
                VALUES (:tenant, :createdAt, :id, :ruleSet, :version, :hitPolicy, :outcome, :errorCode,
                  CAST(:inputs AS jsonb), CAST(:outputs AS jsonb), CAST(:matched AS jsonb), :caller, :correlation,
                  :clock, :micros, :pinned, :onBehalfOf, :functionVersions, :budgetLimit, :stepBudget, :timeoutMs)
                """)
                .param("tenant", r.tenantId()).param("createdAt", Timestamp.from(r.createdAt())).param("id", r.id())
                .param("ruleSet", r.ruleSet()).param("version", r.version()).param("hitPolicy", r.hitPolicy())
                .param("outcome", r.outcome()).param("errorCode", r.errorCode())
                .param("inputs", r.maskedInputsJson()).param("outputs", r.outputsJson())
                .param("matched", r.matchedRuleIdsJson()).param("caller", r.callerId())
                .param("correlation", r.correlationId()).param("clock", Timestamp.from(r.evalClock()))
                .param("micros", r.durationMicros()).param("pinned", r.pinned())
                .param("onBehalfOf", r.onBehalfOf()).param("functionVersions", r.functionVersions())
                .param("budgetLimit", r.budgetLimit()).param("stepBudget", r.stepBudget())
                .param("timeoutMs", r.timeoutMillis())
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
        long counted = jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM rle_decision_queue q WHERE " + RuleListQueries.COUNTED
                + " LIMIT :cap) capped")
                .param("cap", (long) maxBacklog + 1).query(Long.class).single();
        depth = counted;
        refusing = counted > maxBacklog;
        lastDepthCheckNanos = System.nanoTime();
        return counted;
    }

    /**
     * Moves queued decisions into {@code rle_decision}, oldest first, in batches of {@code batch-size}, each batch in
     * a transaction of its own; stops when nothing is waiting or after 50 batches, so one run is bounded. Replicas may
     * run it together: a batch locks its rows with {@code FOR UPDATE SKIP LOCKED}.
     *
     * <p>When the log refuses a batch, the relay tries the rows of the batch one at a time, each in a transaction of its
     * own: the good rows move, and the bad one gets a failed attempt, which waits a back-off before the next, and
     * after {@code max-attempts} is dead-lettered. A bad row therefore never holds back the rows behind it.
     *
     * @return the number of decisions moved
     * @throws RuntimeException when the database cannot be read or an event cannot be written; the rows stay queued
     *     and the next run retries
     */
    public int relayOnce() {
        int total = 0;
        try {
            for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
                Timestamp now = Timestamp.from(clock.instant());
                int moved;
                try {
                    moved = transactions.inIndependentTransaction(() -> moveBatch(now));
                } catch (RuntimeException refused) {
                    moved = moveOneByOne(now);
                }
                total += moved;
                if (moved < batchSize) {
                    break;
                }
            }
            relayed.addAndGet(total);
            refreshDepth();
            deadLetterCount = jdbc.sql(DEAD_COUNT).query(Long.class).single();
            oldestQueuedAt = jdbc.sql("SELECT min(queued_at) FROM rle_decision_queue q WHERE " + RuleListQueries.COUNTED)
                    .query(Timestamp.class).optional().map(Timestamp::toInstant).orElse(null);
            lastRelay = clock.instant();
            return total;
        } catch (RuntimeException e) {
            relayed.addAndGet(total);
            relayFailures.incrementAndGet();
            throw e;
        }
    }

    private static final String INSERT_INTO_LOG = """
            INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
              error_code, masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock,
              duration_micros, log_mode, pinned, on_behalf_of, function_versions, budget_limit, step_budget, timeout_ms)
            SELECT tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome, error_code,
              masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock, duration_micros,
              'ASYNC', pinned, on_behalf_of, function_versions, budget_limit, step_budget, timeout_ms
            FROM rle_decision_queue WHERE queue_seq IN (:seqs)
            """;

    private static final String DEAD_COUNT = "SELECT count(*) FROM rle_decision_queue_event s JOIN rle_decision_queue q"
            + " ON q.queue_seq = s.queue_seq WHERE s.kind IN " + RuleListQueries.DEAD_KINDS + " AND NOT EXISTS (SELECT 1 FROM "
            + "rle_decision_queue_event n WHERE n.queue_seq = s.queue_seq AND n.event_seq > s.event_seq)";

    /** One transaction: lock a batch, write it to the log, and let it leave the queue (the trigger checks the copy). */
    private int moveBatch(Timestamp now) {
        List<Long> seqs = RuleListQueries.RELAY_LOCK_BATCH.list(jdbc,
                ListQuery.params("now", now, "n", batchSize), (rs, i) -> rs.getLong(1));
        return move(seqs);
    }

    private int move(List<Long> seqs) {
        if (seqs.isEmpty()) {
            return 0;
        }
        int inserted = jdbc.sql(INSERT_INTO_LOG).param("seqs", seqs).update();
        jdbc.sql("DELETE FROM rle_decision_queue WHERE queue_seq IN (:seqs)").param("seqs", seqs).update();
        return inserted;
    }

    /** The log refused the batch: find the bad rows by moving each alone, and record why they failed. */
    private int moveOneByOne(Timestamp now) {
        List<Long> seqs = RuleListQueries.RELAY_BATCH.list(jdbc, ListQuery.params("now", now, "n", batchSize),
                (rs, i) -> rs.getLong(1));
        int moved = 0;
        for (long seq : seqs) {
            try {
                moved += transactions.inIndependentTransaction(() -> {
                    // Locked, and still waiting: another replica may have moved or failed it in the meantime
                    List<Long> mine = RuleListQueries.RELAY_ROW_LOCK.list(jdbc,
                            ListQuery.params("seq", seq, "now", now), (rs, i) -> rs.getLong(1));
                    return move(mine);
                });
            } catch (RuntimeException failure) {
                relayFailures.incrementAndGet();
                transactions.inIndependentTransaction(() -> {
                    recordFailure(seq, failure);
                    return null;
                });
            }
        }
        return moved;
    }

    /** Appends the failed attempt, or the dead letter when it was the last attempt allowed. */
    private void recordFailure(long seq, RuntimeException failure) {
        Instant now = clock.instant();
        var row = jdbc.sql("SELECT q.tenant_id, q.id FROM rle_decision_queue q WHERE q.queue_seq = :seq FOR UPDATE OF q")
                .param("seq", seq).query((rs, i) -> new String[] {rs.getString(1), rs.getString(2)}).optional();
        if (row.isEmpty()) {
            return;
        }
        // Attempts count from the last approved retry; any other latest event restarts at none
        int previous = jdbc.sql("""
                SELECT s.attempt FROM rle_decision_queue_event s WHERE s.queue_seq = :seq AND s.kind = 'ATTEMPT_FAILED'
                  AND NOT EXISTS (SELECT 1 FROM rle_decision_queue_event n WHERE n.queue_seq = s.queue_seq
                                  AND n.event_seq > s.event_seq)
                """).param("seq", seq).query(Integer.class).optional().orElse(0);
        int attempt = previous + 1;
        boolean dead = attempt >= maxAttempts;
        Instant next = dead ? null : now.plus(backoff(attempt));
        jdbc.sql("""
                INSERT INTO rle_decision_queue_event (queue_seq, tenant_id, decision_id, kind, attempt, next_attempt_at,
                  detail, occurred_at) VALUES (:seq, :tenant, :id, :kind, :attempt, :next, :detail, :at)
                """).param("seq", seq).param("tenant", row.get()[0]).param("id", row.get()[1])
                .param("kind", dead ? "DEAD_LETTERED" : "ATTEMPT_FAILED").param("attempt", attempt)
                .param("next", next == null ? null : Timestamp.from(next)).param("detail", describe(failure))
                .param("at", Timestamp.from(now)).update();
        if (dead) {
            deadLettered.incrementAndGet();
        }
    }

    /** The wait before attempt {@code attempt + 1}: the base, doubled for each attempt made, up to the ceiling. */
    Duration backoff(int attempt) {
        Duration wait = retryBackoff;
        for (int i = 1; i < attempt && wait.compareTo(maxRetryBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxRetryBackoff) > 0 ? maxRetryBackoff : wait;
    }

    /** What the log said, as the first line of the database's message: no row values, which are on the later lines. */
    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        String first = message.lines().findFirst().orElse(message);
        String sqlState = root instanceof java.sql.SQLException sql && sql.getSQLState() != null
                ? " [" + sql.getSQLState() + "]" : "";
        String text = first + sqlState;
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    @Override
    public List<DeadLetter> deadLetters(String tenantId, int limit) {
        return RuleListQueries.DEAD_LETTERS.list(jdbc, ListQuery.params("tenant", tenantId, "limit", limit),
                AsyncDecisionLog::deadLetter);
    }

    @Override
    public Optional<DeadLetter> deadLetter(String tenantId, String decisionId) {
        return jdbc.sql(RuleListQueries.DEAD_LETTERS_SQL + " AND q.id = :id").param("tenant", tenantId).param("id", decisionId)
                .query(AsyncDecisionLog::deadLetter).optional();
    }

    private static DeadLetter deadLetter(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String kind = rs.getString("kind");
        DeadLetterState state = switch (kind) {
            case "RETRY_REQUESTED" -> DeadLetterState.RETRY_REQUESTED;
            case "RESOLVE_REQUESTED" -> DeadLetterState.RESOLVE_REQUESTED;
            default -> DeadLetterState.DEAD;
        };
        boolean requested = state != DeadLetterState.DEAD;
        Timestamp occurred = rs.getTimestamp("occurred_at");
        return new DeadLetter(rs.getString("id"), rs.getString("rule_set_name"), rs.getInt("version_num"),
                rs.getTimestamp("queued_at").toInstant(), rs.getInt("attempts"), rs.getString("last_error"), state,
                requested ? rs.getString("actor") : null, requested ? rs.getString("comment") : null,
                requested ? occurred.toInstant() : null);
    }

    @Override
    public boolean transition(String tenantId, String decisionId, Set<DeadLetterState> from, String kind, String actor,
            String comment, Instant at) {
        return Boolean.TRUE.equals(transactions.inTransaction(() -> {
            // The queue row is locked first, so two people acting at once are put in line and the second sees the first
            var locked = jdbc.sql("SELECT q.queue_seq FROM rle_decision_queue q WHERE q.tenant_id = :tenant AND "
                    + "q.id = :id FOR UPDATE OF q").param("tenant", tenantId).param("id", decisionId)
                    .query(Long.class).optional();
            if (locked.isEmpty()) {
                return false;
            }
            Optional<DeadLetter> current = deadLetter(tenantId, decisionId);
            if (current.isEmpty() || !from.contains(current.get().state())) {
                return false;
            }
            jdbc.sql("""
                    INSERT INTO rle_decision_queue_event (queue_seq, tenant_id, decision_id, kind, actor, comment,
                      occurred_at) VALUES (:seq, :tenant, :id, :kind, :actor, :comment, :at)
                    """).param("seq", locked.get()).param("tenant", tenantId).param("id", decisionId)
                    .param("kind", kind).param("actor", actor).param("comment", comment)
                    .param("at", Timestamp.from(at)).update();
            return true;
        }));
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

    /** @return rows this replica gave up on and dead-lettered since start */
    public long deadLetteredCount() {
        return deadLettered.get();
    }

    /** @return dead letters an operator still has to deal with, at the last relay run */
    public long deadLetterDepth() {
        return deadLetterCount;
    }

    /** @return failed attempts and failed runs of this replica since start */
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
