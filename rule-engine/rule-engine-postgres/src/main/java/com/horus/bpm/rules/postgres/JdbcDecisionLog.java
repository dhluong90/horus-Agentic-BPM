package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.internal.store.DecisionLog;
import com.horus.bpm.rules.internal.store.DecisionRecord;
import java.sql.Timestamp;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Appends to {@code rle_decision} with exactly one {@code INSERT} per decision, in the caller's
 * transaction (RLE-013, TR-EMB-04). The engine has no update or delete path on this table.
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
}
