package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to {@code rle_decision} with exactly one {@code INSERT} per decision, in the caller's
 * transaction (RLE-013, TR-EMB-04), and reads it inside one tenant: one row by id, for replay and the log read,
 * the search by a logged input, and the search by rule set and period (RLE-029). The engine has no update or delete
 * path on this table.
 */
public final class JdbcDecisionLog implements DecisionLog {

    private final JdbcClient jdbc;

    /**
     * Creates the log on the host's data source.
     *
     * @param dataSource the host's data source
     */
    public JdbcDecisionLog(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void append(DecisionRecord r) {
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                  error_code, masked_inputs, outputs, matched_rule_ids, caller_id, correlation_id, eval_clock,
                  duration_micros, log_mode, pinned)
                VALUES (:tenant, :createdAt, :id, :ruleSet, :version, :hitPolicy, :outcome, :errorCode,
                  CAST(:inputs AS jsonb), CAST(:outputs AS jsonb), CAST(:matched AS jsonb), :caller, :correlation,
                  :clock, :micros, 'TRANSACTIONAL', :pinned)
                """)
                .param("tenant", r.tenantId()).param("createdAt", Timestamp.from(r.createdAt())).param("id", r.id())
                .param("ruleSet", r.ruleSet()).param("version", r.version()).param("hitPolicy", r.hitPolicy())
                .param("outcome", r.outcome()).param("errorCode", r.errorCode())
                .param("inputs", r.maskedInputsJson()).param("outputs", r.outputsJson())
                .param("matched", r.matchedRuleIdsJson()).param("caller", r.callerId())
                .param("correlation", r.correlationId()).param("clock", Timestamp.from(r.evalClock()))
                .param("micros", r.durationMicros()).param("pinned", r.pinned())
                .update();
    }

    private static final String COLUMNS = """
            tenant_id, id, created_at, rule_set_name, version_num, hit_policy, outcome, error_code,
            masked_inputs::text AS inputs, outputs::text AS outputs, matched_rule_ids::text AS matched, caller_id,
            correlation_id, eval_clock, duration_micros, pinned
            """;

    private static final String SEARCH = "SELECT " + COLUMNS + """
             FROM rle_decision
            WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND created_at >= :from AND created_at < :to
            """;

    /**
     * Reads a decision by id. The id is a ULID whose first 48 bits are the millisecond of {@code created_at}, the
     * partition key, so the first query reads one millisecond of one partition through the primary key. Only when
     * the engine clock went back within the process is the id later than the row; the second query then reads the
     * tenant's rows up to that millisecond. A text that is not a ULID is not found, without a query.
     */
    @Override
    public Optional<DecisionRecord> find(String tenantId, String id) {
        if (!Ulid.isValid(id)) {
            return Optional.empty();
        }
        Instant time = Ulid.timeOf(id);
        Optional<DecisionRecord> exact = jdbc.sql("SELECT " + COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND created_at >= :from AND created_at < :to AND id = :id
                """)
                .param("tenant", tenantId).param("from", Timestamp.from(time))
                .param("to", Timestamp.from(time.plusMillis(1))).param("id", id)
                .query(JdbcDecisionLog::record).optional();
        if (exact.isPresent()) {
            return exact;
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM rle_decision WHERE tenant_id = :tenant AND created_at < :to AND id = :id")
                .param("tenant", tenantId).param("to", Timestamp.from(time.plusMillis(1))).param("id", id)
                .query(JdbcDecisionLog::record).optional();
    }

    @Override
    public List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, List<String> texts,
            Instant from, Instant to, int limit) {
        // Range on idx_rle_decision_lookup within at most two monthly partitions (ADR-0015); the input is compared
        // as text, so a masked input is found by its fingerprints only, one per key the tenant holds.
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet
                  AND created_at >= :from AND created_at < :to
                  AND masked_inputs ->> CAST(:input AS text) IN (:texts)
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("tenant", tenantId).param("ruleSet", ruleSet)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("input", input).param("texts", texts).param("limit", limit)
                .query(JdbcDecisionLog::record).list();
    }

    /**
     * {@inheritDoc}
     *
     * <p>One statement, tenant and rule set first and the period on the partition key, so PostgreSQL prunes every
     * monthly partition outside the period and reads {@code idx_rle_decision_lookup} in at most two. The
     * statement timeout is set for this statement only and the previous value is restored, so a host
     * transaction the search joins keeps its own.
     */
    @Override
    public List<DecisionRecord> search(String tenantId, String ruleSet, Instant from, Instant to, Position after,
            int limit, Duration statementTimeout) {
        String previous = jdbc.sql("SELECT current_setting('statement_timeout')").query(String.class).single();
        jdbc.sql("SELECT set_config('statement_timeout', :timeout, true)")
                .param("timeout", statementTimeout.toMillis() + "ms").query(String.class).single();
        try {
            var statement = jdbc.sql(searchSql(after != null))
                    .param("tenant", tenantId).param("ruleSet", ruleSet)
                    .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                    .param("limit", limit);
            if (after != null) {
                statement = statement.param("afterAt", Timestamp.from(after.createdAt())).param("afterId", after.id());
            }
            return statement.query(JdbcDecisionLog::record).list();
        } finally {
            jdbc.sql("SELECT set_config('statement_timeout', :timeout, true)")
                    .param("timeout", previous).query(String.class).single();
        }
    }

    /**
     * Returns the search statement, for the query plan check of RLE-029.
     *
     * @param afterPosition whether the statement continues after a keyset position
     * @return the SQL with named parameters
     */
    static String searchSql(boolean afterPosition) {
        return SEARCH + (afterPosition ? "  AND (created_at, id) < (:afterAt, :afterId)\n" : "")
                + "ORDER BY created_at DESC, id DESC\nLIMIT :limit\n";
    }

    private static DecisionRecord record(ResultSet rs, int row) throws SQLException {
        return new DecisionRecord(rs.getString("tenant_id"), rs.getString("id"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("rule_set_name"), rs.getInt("version_num"),
                rs.getString("hit_policy"), rs.getString("outcome"), rs.getString("error_code"),
                rs.getString("inputs"), rs.getString("outputs"), rs.getString("matched"), rs.getString("caller_id"),
                rs.getString("correlation_id"), rs.getTimestamp("eval_clock").toInstant(),
                rs.getLong("duration_micros"), rs.getBoolean("pinned"));
    }
}
