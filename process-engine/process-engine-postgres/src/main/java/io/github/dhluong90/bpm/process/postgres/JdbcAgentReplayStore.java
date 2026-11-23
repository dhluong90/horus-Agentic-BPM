package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.api.AgentReplayRun;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AgentReplayStore} on PostgreSQL (#98). Recorded answers are read from {@code pre_history} by
 * the primary key's range on {@code (tenant_id, occurred_at)}, keyset-paged over {@code (occurred_at, id)}, joined to
 * their job in {@code pre_timer} for the guard spec; reviewer overrides are read through the instance index, and count only when a
 * field whose recorded value was kept changed (a masked field cannot be compared). Every
 * read of live tables is a plain {@code SELECT}.
 */
public final class JdbcAgentReplayStore implements AgentReplayStore {

    private static final String RUN_COLUMNS = "tenant_id, id, status, definition_key, node_id, period_from, period_to, "
            + "candidate_rule_set, candidate_version, candidate_schema::text AS candidate_schema, idempotency_key, "
            + "request_hash, cursor_at, cursor_id, cases, unchanged, newly_allowed, newly_refused, newly_reviewed, "
            + "overridden, not_replayed, with_masked_fields, masked_input_required, failure_code, created_by, created_at, completed_at, "
            + "expires_at";

    private static final String ANSWERS_WHERE = ProcessListQueries.ANSWERS_WHERE;

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcAgentReplayStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public long countAnswers(String tenant, String definitionKey, String nodeId, Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) " + ANSWERS_WHERE).params(tenant, definitionKey, nodeId, ts(from), ts(to))
                .query(Long.class).single();
    }

    @Override
    public Optional<Map<String, Object>> latestRecordedSchema(String tenant, String definitionKey, String nodeId,
            Instant from, Instant to) {
        return jdbc.sql("SELECT (j.payload->'guard'->'outputSchema')::text " + ANSWERS_WHERE.replace("WHERE",
                        "JOIN pre_timer j ON j.tenant_id = h.tenant_id AND j.id = h.details->>'jobId' WHERE")
                        + "AND j.payload->'guard'->'outputSchema' IS NOT NULL ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1")
                .params(tenant, definitionKey, nodeId, ts(from), ts(to)).query(String.class).optional()
                .map(Values::readMap);
    }

    @Override
    public List<RecordedAnswer> answers(String tenant, String definitionKey, String nodeId, Instant from, Instant to,
            Instant afterAt, String afterId, int limit) {
        List<Object> params = new java.util.ArrayList<>(List.of(tenant, definitionKey, nodeId, ts(from), ts(to)));
        if (afterAt != null) {
            params.add(ts(afterAt));
            params.add(afterId);
        }
        params.add(limit);
        ListQuery query = afterAt == null ? ProcessListQueries.RECORDED_ANSWERS : ProcessListQueries.RECORDED_ANSWERS_AFTER;
        return query.list(jdbc, params, (rs, n) -> new RecordedAnswer(instant(rs, "occurred_at"), rs.getString("id"),
                rs.getString("instance_id"), rs.getString("event_type"), rs.getString("attempt_id"),
                Values.readMap(rs.getString("details")), rs.getString("payload") == null ? null
                        : Values.readMap(rs.getString("payload"))));
    }

    @Override
    public Set<String> overriddenAttempts(String tenant, Collection<String> instanceIds, Collection<String> attemptIds) {
        return ProcessListQueries.OVERRIDDEN_ATTEMPTS.list(jdbc,
                List.of(tenant, instanceIds.toArray(String[]::new), attemptIds.toArray(String[]::new)),
                (rs, n) -> rs.getString("attempt_id")).stream()
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    @Override
    public Optional<Map<String, Object>> recordedDetails(String tenant, String instanceId, String attemptId) {
        return jdbc.sql("SELECT details::text FROM pre_history WHERE tenant_id = ? AND instance_id = ? "
                        + "AND attempt_id = ? AND event_type IN ('ASYNC_RESULT_ACCEPTED','ASYNC_RESULT_REFUSED') "
                        + "ORDER BY occurred_at DESC, id DESC LIMIT 1")
                .params(tenant, instanceId, attemptId).query(String.class).optional().map(Values::readMap);
    }

    @Override
    public void insertRun(StoredRun run) {
        AgentReplayRun v = run.view();
        jdbc.sql("INSERT INTO pre_agent_replay (tenant_id, id, status, definition_key, node_id, period_from, period_to, "
                        + "candidate_rule_set, candidate_version, candidate_schema, idempotency_key, request_hash, "
                        + "created_by, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)")
                .params(run.tenantId(), v.id(), v.status(), v.definitionKey(), v.nodeId(), v.from(), v.to(),
                        v.candidateRuleSet(), v.candidateVersion(),
                        run.candidateSchema() == null ? null : Values.write(run.candidateSchema()),
                        run.idempotencyKey(), run.requestHash(), v.createdBy(), ts(v.createdAt()), ts(v.expiresAt()))
                .update();
    }

    @Override
    public Optional<StoredRun> findRun(String tenant, String runId) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM pre_agent_replay WHERE tenant_id = ? AND id = ?")
                .params(tenant, runId).query(JdbcAgentReplayStore::run).optional();
    }

    @Override
    public Optional<StoredRun> findRunByIdempotencyKey(String tenant, String idempotencyKey) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM pre_agent_replay WHERE tenant_id = ? AND idempotency_key = ?")
                .params(tenant, idempotencyKey).query(JdbcAgentReplayStore::run).optional();
    }

    @Override
    public Optional<StoredRun> lockNextRun() {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM pre_agent_replay WHERE status IN ('QUEUED','RUNNING') "
                        + "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .query(JdbcAgentReplayStore::run).optional();
    }

    @Override
    public void insertCases(String tenant, String runId, int firstSeq, List<AgentReplayCase> lines) {
        int seq = firstSeq;
        for (AgentReplayCase c : lines) {
            jdbc.sql("INSERT INTO pre_agent_replay_case (tenant_id, replay_id, seq, attempt_id, instance_id, recorded_at, "
                            + "recorded_outcome, candidate_outcome, change, recorded_rule_ids, candidate_rule_ids, code, "
                            + "overridden, masked_fields) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)")
                    .params(tenant, runId, seq++, c.attemptId(), c.instanceId(), ts(c.recordedAt()), c.recordedOutcome(),
                            c.candidateOutcome(), c.change(), json(c.recordedRuleIds()), json(c.candidateRuleIds()),
                            c.code(), c.overriddenByReviewer(), c.maskedFields())
                    .update();
        }
    }

    @Override
    public void advance(String tenant, String runId, String status, Instant cursorAt, String cursorId,
            AgentReplayRun.Summary s, Instant completedAt) {
        jdbc.sql("UPDATE pre_agent_replay SET status = ?, cursor_at = ?, cursor_id = ?, cases = ?, unchanged = ?, "
                        + "newly_allowed = ?, newly_refused = ?, newly_reviewed = ?, overridden = ?, not_replayed = ?, "
                        + "with_masked_fields = ?, masked_input_required = ?, completed_at = ? WHERE tenant_id = ? AND id = ?")
                .params(status, ts(cursorAt), cursorId, s.cases(), s.unchanged(), s.newlyAllowed(), s.newlyRefused(),
                        s.newlyRoutedToReview(), s.overriddenByReviewer(), s.notReplayed(), s.withMaskedFields(),
                        s.maskedInputRequired(), ts(completedAt), tenant, runId)
                .update();
    }

    @Override
    public void fail(String tenant, String runId, String failureCode, Instant at) {
        jdbc.sql("UPDATE pre_agent_replay SET status = 'FAILED', failure_code = ?, completed_at = ? "
                        + "WHERE tenant_id = ? AND id = ? AND status IN ('QUEUED','RUNNING')")
                .params(failureCode, ts(at), tenant, runId).update();
    }

    @Override
    public List<CaseRow> cases(String tenant, String runId, int afterSeq, int limit) {
        return ProcessListQueries.REPLAY_CASES.list(jdbc, List.of(tenant, runId, afterSeq, limit),
                (rs, n) -> new CaseRow(rs.getInt("seq"), line(rs)));
    }

    @Override
    public Optional<AgentReplayCase> findCase(String tenant, String runId, String attemptId) {
        return jdbc.sql("SELECT seq, attempt_id, instance_id, recorded_at, recorded_outcome, candidate_outcome, change, "
                        + "recorded_rule_ids::text AS recorded_rule_ids, candidate_rule_ids::text AS candidate_rule_ids, "
                        + "code, overridden, masked_fields FROM pre_agent_replay_case "
                        + "WHERE tenant_id = ? AND replay_id = ? AND attempt_id = ? ORDER BY seq LIMIT 1")
                .params(tenant, runId, attemptId).query((rs, n) -> line(rs)).optional();
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("DELETE FROM pre_agent_replay WHERE (tenant_id, id) IN (SELECT tenant_id, id FROM "
                        + "pre_agent_replay WHERE expires_at <= ? ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)")
                .params(ts(now), limit).update();
    }

    private static StoredRun run(ResultSet rs, int n) throws SQLException {
        AgentReplayRun.Summary summary = new AgentReplayRun.Summary(rs.getInt("cases"), rs.getInt("unchanged"),
                rs.getInt("newly_allowed"), rs.getInt("newly_refused"), rs.getInt("newly_reviewed"),
                rs.getInt("overridden"), rs.getInt("not_replayed"), rs.getInt("with_masked_fields"),
                rs.getInt("masked_input_required"));
        String schema = rs.getString("candidate_schema");
        AgentReplayRun view = new AgentReplayRun(rs.getString("id"), rs.getString("status"),
                rs.getString("definition_key"), rs.getString("node_id"), rs.getDate("period_from").toLocalDate(),
                rs.getDate("period_to").toLocalDate(), rs.getString("candidate_rule_set"),
                rs.getInt("candidate_version"), schema != null, summary, rs.getString("failure_code"),
                rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "completed_at"),
                instant(rs, "expires_at"));
        return new StoredRun(rs.getString("tenant_id"), view, schema == null ? null : Values.readMap(schema),
                rs.getString("idempotency_key"), rs.getString("request_hash"), instant(rs, "cursor_at"),
                rs.getString("cursor_id"));
    }

    private static AgentReplayCase line(ResultSet rs) throws SQLException {
        return new AgentReplayCase(rs.getString("attempt_id"), rs.getString("instance_id"), instant(rs, "recorded_at"),
                rs.getString("recorded_outcome"), rs.getString("candidate_outcome"), rs.getString("change"),
                strings(rs.getString("recorded_rule_ids")), strings(rs.getString("candidate_rule_ids")),
                rs.getString("code"), rs.getBoolean("overridden"), rs.getInt("masked_fields"));
    }

    private static List<String> strings(String json) {
        Object value = Values.readMap("{\"v\":" + json + "}").get("v");
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    private static String json(List<String> values) {
        String wrapped = Values.write(Map.of("v", values));
        return wrapped.substring("{\"v\":".length(), wrapped.length() - 1);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
