package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.DecisionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to {@code rle_decision} with exactly one {@code INSERT} per decision, in the caller's
 * transaction (RLE-013, TR-EMB-04), and reads it inside one tenant. The engine has no update or delete path on this
 * table.
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

    private static final String COLUMNS = """
            tenant_id, id, created_at, rule_set_name, version_num, hit_policy, outcome, error_code,
            masked_inputs::text AS inputs, outputs::text AS outputs, matched_rule_ids::text AS matched, caller_id,
            correlation_id, eval_clock, duration_micros
            """;

    @Override
    public Optional<DecisionRecord> find(String tenantId, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM rle_decision WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenantId).param("id", id)
                .query(JdbcDecisionLog::record).optional();
    }

    @Override
    public List<DecisionRecord> findByLoggedInput(String tenantId, String ruleSet, String input, String text,
            Instant from, Instant to, int limit) {
        // Range on idx_rle_decision_lookup within at most two monthly partitions (ADR-0015); the input is compared
        // as text, so a masked input is found by its fingerprint only.
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet
                  AND created_at >= :from AND created_at < :to
                  AND masked_inputs ->> CAST(:input AS text) = :text
                ORDER BY created_at DESC, id DESC
                LIMIT :limit
                """)
                .param("tenant", tenantId).param("ruleSet", ruleSet)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("input", input).param("text", text).param("limit", limit)
                .query(JdbcDecisionLog::record).list();
    }

    private static DecisionRecord record(ResultSet rs, int row) throws SQLException {
        return new DecisionRecord(rs.getString("tenant_id"), rs.getString("id"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("rule_set_name"), rs.getInt("version_num"),
                rs.getString("hit_policy"), rs.getString("outcome"), rs.getString("error_code"),
                rs.getString("inputs"), rs.getString("outputs"), rs.getString("matched"), rs.getString("caller_id"),
                rs.getString("correlation_id"), rs.getTimestamp("eval_clock").toInstant(),
                rs.getLong("duration_micros"));
    }
}
