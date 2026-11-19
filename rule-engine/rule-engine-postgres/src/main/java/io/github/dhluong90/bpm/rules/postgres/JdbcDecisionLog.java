package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.json.Ulid;
import com.horus.bpm.rules.internal.store.DecisionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to {@code rle_decision} with exactly one {@code INSERT} per decision, in the caller's
 * transaction (RLE-013, TR-EMB-04). The engine has no update or delete path on this table. Reads one row back by id
 * for replay.
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

    /**
     * Reads a decision by id. The id is a ULID whose first 48 bits are the millisecond of {@code created_at}, the
     * partition key, so the first query reads one millisecond of one partition through the primary key. Only when
     * the engine clock went back within the process is the id later than the row; the second query then reads the
     * tenant's rows up to that millisecond.
     */
    @Override
    public Optional<DecisionRecord> find(String tenantId, String decisionId) {
        Instant time = Ulid.timeOf(decisionId);
        Optional<DecisionRecord> exact = jdbc.sql(SELECT + """
                WHERE tenant_id = :tenant AND created_at >= :from AND created_at < :to AND id = :id
                """)
                .param("tenant", tenantId).param("from", Timestamp.from(time))
                .param("to", Timestamp.from(time.plusMillis(1))).param("id", decisionId)
                .query(JdbcDecisionLog::record).optional();
        if (exact.isPresent()) {
            return exact;
        }
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND created_at < :to AND id = :id")
                .param("tenant", tenantId).param("to", Timestamp.from(time.plusMillis(1))).param("id", decisionId)
                .query(JdbcDecisionLog::record).optional();
    }

    private static final String SELECT = """
            SELECT tenant_id, id, created_at, rule_set_name, version_num, hit_policy, outcome, error_code,
              masked_inputs::text AS masked_inputs, outputs::text AS outputs, matched_rule_ids::text AS matched_rule_ids,
              caller_id, correlation_id, eval_clock, duration_micros
            FROM rle_decision
            """;

    private static DecisionRecord record(ResultSet rs, int row) throws SQLException {
        return new DecisionRecord(rs.getString("tenant_id"), rs.getString("id"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("rule_set_name"), rs.getInt("version_num"),
                rs.getString("hit_policy"), rs.getString("outcome"), rs.getString("error_code"),
                rs.getString("masked_inputs"), rs.getString("outputs"), rs.getString("matched_rule_ids"),
                rs.getString("caller_id"), rs.getString("correlation_id"), rs.getTimestamp("eval_clock").toInstant(),
                rs.getLong("duration_micros"));
    }
}
