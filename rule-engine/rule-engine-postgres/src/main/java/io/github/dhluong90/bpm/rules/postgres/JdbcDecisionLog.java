package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.store.DecisionLog;
import io.github.dhluong90.bpm.rules.internal.store.DecisionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to {@code rle_decision} with exactly one {@code INSERT} per decision, in the caller's
 * transaction (RLE-013), and reads it back by rule set and period (RLE-029). The engine has no update or
 * delete path on this table.
 */
public final class JdbcDecisionLog implements DecisionLog {

    private static final String SEARCH = """
            SELECT tenant_id, id, created_at, rule_set_name, version_num, hit_policy, outcome, error_code,
              masked_inputs::text AS masked_inputs, outputs::text AS outputs, matched_rule_ids::text AS matched_rule_ids,
              caller_id, correlation_id, eval_clock, duration_micros
            FROM rle_decision
            WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND created_at >= :from AND created_at < :to
            """;

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
                  duration_micros, log_mode)
                VALUES (:tenant, :createdAt, :id, :ruleSet, :version, :hitPolicy, :outcome, :errorCode,
                  CAST(:inputs AS jsonb), CAST(:outputs AS jsonb), CAST(:matched AS jsonb), :caller, :correlation,
                  :clock, :micros, 'TRANSACTIONAL')
                """)
                .param("tenant", r.tenantId()).param("createdAt", Timestamp.from(r.createdAt())).param("id", r.id())
                .param("ruleSet", r.ruleSet()).param("version", r.version()).param("hitPolicy", r.hitPolicy())
                .param("outcome", r.outcome()).param("errorCode", r.errorCode())
                .param("inputs", r.maskedInputsJson()).param("outputs", r.outputsJson())
                .param("matched", r.matchedRuleIdsJson()).param("caller", r.callerId())
                .param("correlation", r.correlationId()).param("clock", Timestamp.from(r.evalClock()))
                .param("micros", r.durationMicros())
                .update();
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

    private static DecisionRecord record(ResultSet rs, int rowNum) throws SQLException {
        return new DecisionRecord(rs.getString("tenant_id"), rs.getString("id"), instant(rs, "created_at"),
                rs.getString("rule_set_name"), rs.getInt("version_num"), rs.getString("hit_policy"),
                rs.getString("outcome"), rs.getString("error_code"), rs.getString("masked_inputs"),
                rs.getString("outputs"), rs.getString("matched_rule_ids"), rs.getString("caller_id"),
                rs.getString("correlation_id"), instant(rs, "eval_clock"), rs.getLong("duration_micros"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
