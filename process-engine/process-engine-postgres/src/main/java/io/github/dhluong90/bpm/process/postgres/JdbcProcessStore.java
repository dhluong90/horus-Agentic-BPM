package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.exec.HistoryEntry;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.exec.TokenKind;
import io.github.dhluong90.bpm.process.internal.exec.TokenStatus;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.ProcessStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link ProcessStore} on the host's PostgreSQL through {@link JdbcClient}. Every statement filters by
 * {@code tenant_id} first. Uses the host's {@link DataSource}, so statements join the caller's transaction.
 */
public final class JdbcProcessStore implements ProcessStore {

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcProcessStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    // ---- definitions --------------------------------------------------------------------------------------

    private static final String DEFINITION_COLUMNS = "tenant_id, id, key_name, version_num, status, name, "
            + "definition_json::text AS definition_json, definition_hash, source_format, source_document, source_hash, "
            + "node_count, source, effective_from, created_by, created_at, approved_by, approved_at";

    @Override
    public Optional<StoredDefinition> latestDefinition(String tenant, String key) {
        return jdbc.sql("SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE tenant_id = ? AND key_name = ? "
                        + "ORDER BY version_num DESC LIMIT 1")
                .params(tenant, key).query(JdbcProcessStore::definition).optional();
    }

    @Override
    public Optional<StoredDefinition> effectiveDefinition(String tenant, String key, Instant now) {
        return jdbc.sql("SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE tenant_id = ? AND key_name = ? "
                        + "AND status = 'ACTIVE' AND effective_from <= ? ORDER BY version_num DESC LIMIT 1")
                .params(tenant, key, ts(now)).query(JdbcProcessStore::definition).optional();
    }

    @Override
    public Optional<StoredDefinition> definition(String tenant, String id) {
        return jdbc.sql("SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).query(JdbcProcessStore::definition).optional();
    }

    @Override
    public Optional<StoredDefinition> definitionVersion(String tenant, String key, int version) {
        return jdbc.sql("SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE tenant_id = ? AND key_name = ? "
                        + "AND version_num = ?")
                .params(tenant, key, version).query(JdbcProcessStore::definition).optional();
    }

    @Override
    public void insertDefinition(StoredDefinition d) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                        + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                        + "created_by, created_at, approved_by, approved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(d.tenantId(), d.id(), d.key(), d.version(), d.status(), d.name(), d.definitionJson(),
                        d.definitionHash(), d.sourceFormat(), d.sourceDocument(), d.sourceHash(), d.nodeCount(), d.source(),
                        ts(d.effectiveFrom()), d.createdBy(), ts(d.createdAt()), d.approvedBy(), ts(d.approvedAt()))
                .update();
    }

    private static StoredDefinition definition(ResultSet rs, int row) throws SQLException {
        return new StoredDefinition(rs.getString("tenant_id"), rs.getString("id"), rs.getString("key_name"),
                rs.getInt("version_num"), rs.getString("status"), rs.getString("name"), rs.getString("definition_json"),
                rs.getString("definition_hash"), rs.getString("source_format"), rs.getString("source_document"),
                rs.getString("source_hash"), rs.getInt("node_count"), rs.getString("source"),
                instant(rs, "effective_from"), rs.getString("created_by"), instant(rs, "created_at"),
                rs.getString("approved_by"), instant(rs, "approved_at"));
    }

    // ---- instances ----------------------------------------------------------------------------------------

    private static final String INSTANCE_COLUMNS = "tenant_id, id, definition_id, definition_key, definition_ver, "
            + "business_key, status, variables::text AS variables, seq, lock_version, started_by, started_at, ended_at";

    @Override
    public boolean insertInstance(InstanceRow r) {
        // A concurrent start with the same business key blocks on the unique index, then inserts nothing.
        return jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, business_key, "
                        + "status, variables, seq, lock_version, started_by, started_at, ended_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, definition_key, business_key) WHERE business_key IS NOT NULL DO NOTHING")
                .params(r.tenantId(), r.id(), r.definitionId(), r.definitionKey(), r.definitionVersion(), r.businessKey(),
                        r.status(), r.variablesJson(), r.sequence(), r.lockVersion(), r.startedBy(), ts(r.startedAt()),
                        ts(r.endedAt()))
                .update() == 1;
    }

    @Override
    public Optional<InstanceRow> lockInstance(String tenant, String id) {
        return jdbc.sql("SELECT " + INSTANCE_COLUMNS + " FROM pre_instance WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(tenant, id).query(JdbcProcessStore::instance).optional();
    }

    @Override
    public Optional<InstanceRow> findInstance(String tenant, String id) {
        return jdbc.sql("SELECT " + INSTANCE_COLUMNS + " FROM pre_instance WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).query(JdbcProcessStore::instance).optional();
    }

    @Override
    public Optional<InstanceRow> findInstanceByBusinessKey(String tenant, String key, String businessKey) {
        return jdbc.sql("SELECT " + INSTANCE_COLUMNS + " FROM pre_instance WHERE tenant_id = ? AND definition_key = ? "
                        + "AND business_key = ?")
                .params(tenant, key, businessKey).query(JdbcProcessStore::instance).optional();
    }

    @Override
    public void updateInstance(InstanceRow r) {
        jdbc.sql("UPDATE pre_instance SET status = ?, variables = ?::jsonb, seq = ?, ended_at = ?, "
                        + "lock_version = lock_version + 1 WHERE tenant_id = ? AND id = ?")
                .params(r.status(), r.variablesJson(), r.sequence(), ts(r.endedAt()), r.tenantId(), r.id())
                .update();
    }

    private static InstanceRow instance(ResultSet rs, int row) throws SQLException {
        return new InstanceRow(rs.getString("tenant_id"), rs.getString("id"), rs.getString("definition_id"),
                rs.getString("definition_key"), rs.getInt("definition_ver"), rs.getString("business_key"),
                rs.getString("status"), rs.getString("variables"), rs.getLong("seq"), rs.getLong("lock_version"),
                rs.getString("started_by"), instant(rs, "started_at"), instant(rs, "ended_at"));
    }

    // ---- tokens -------------------------------------------------------------------------------------------

    @Override
    public List<Token> tokens(String tenant, String instanceId) {
        return jdbc.sql("SELECT id, token_kind, status, scope_token_id, current_node_id, activity_instance_id, "
                        + "locals::text AS locals FROM pre_token WHERE tenant_id = ? AND instance_id = ? ORDER BY id")
                .params(tenant, instanceId)
                .query((rs, row) -> new Token(rs.getString("id"), TokenKind.valueOf(rs.getString("token_kind")),
                        TokenStatus.valueOf(rs.getString("status")), rs.getString("scope_token_id"),
                        rs.getString("current_node_id"), rs.getString("activity_instance_id"),
                        Values.readMap(rs.getString("locals"))))
                .list();
    }

    @Override
    public void saveTokens(String tenant, String instanceId, String definitionId, List<Token> tokens, Instant now) {
        // Scope tokens first, so the self-reference of flow tokens resolves.
        List<Token> ordered = tokens.stream().sorted((a, b) -> Boolean.compare(a.kind() != TokenKind.SCOPE,
                b.kind() != TokenKind.SCOPE)).toList();
        for (Token t : ordered) {
            int written = jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                            + "current_node_id, status, activity_instance_id, locals, entered_at, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) "
                            + "ON CONFLICT (tenant_id, id) DO UPDATE SET current_node_id = EXCLUDED.current_node_id, "
                            + "status = EXCLUDED.status, activity_instance_id = EXCLUDED.activity_instance_id, "
                            + "locals = EXCLUDED.locals, entered_at = CASE WHEN pre_token.current_node_id "
                            + "<> EXCLUDED.current_node_id THEN EXCLUDED.entered_at ELSE pre_token.entered_at END "
                            // A token row of another instance is never touched: zero rows fails the transaction.
                            + "WHERE pre_token.instance_id = EXCLUDED.instance_id")
                    .params(tenant, t.id(), instanceId, definitionId, t.kind().name(), t.scopeTokenId(), t.nodeId(),
                            t.status().name(), t.activityInstanceId(), Values.write(t.locals()), ts(now), ts(now))
                    .update();
            if (written != 1) {
                throw new IllegalStateException("Token " + t.id() + " belongs to another instance");
            }
        }
    }

    // ---- tasks --------------------------------------------------------------------------------------------

    private static final String TASK_COLUMNS = "tenant_id, id, instance_id, token_id, definition_id, node_id, name, "
            + "assignee_user, assignee_role, assignee_group, four_eyes, status, lock_version, completed_by, created_at, "
            + "completed_at";

    @Override
    public void insertTask(TaskRow t) {
        int colon = t.assignee().indexOf(':');
        String kind = t.assignee().substring(0, colon);
        String value = t.assignee().substring(colon + 1);
        jdbc.sql("INSERT INTO pre_task (tenant_id, id, instance_id, token_id, definition_id, node_id, name, assignee_user, "
                        + "assignee_role, assignee_group, four_eyes, status, lock_version, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(t.tenantId(), t.id(), t.instanceId(), t.tokenId(), t.definitionId(), t.nodeId(), t.name(),
                        kind.equals("user") ? value : null, kind.equals("role") ? value : null,
                        kind.equals("group") ? value : null, t.fourEyes(), t.status(), t.lockVersion(), ts(t.createdAt()))
                .update();
    }

    @Override
    public Optional<TaskRow> findTask(String tenant, String id) {
        return jdbc.sql("SELECT " + TASK_COLUMNS + " FROM pre_task WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).query(JdbcProcessStore::task).optional();
    }

    @Override
    public int completeTask(String tenant, String id, long expectedLockVersion, String completedBy, Instant at) {
        return jdbc.sql("UPDATE pre_task SET status = 'COMPLETED', completed_by = ?, completed_at = ?, "
                        + "lock_version = lock_version + 1 WHERE tenant_id = ? AND id = ? AND lock_version = ? "
                        + "AND status IN ('OPEN','CLAIMED')")
                .params(completedBy, ts(at), tenant, id, expectedLockVersion).update();
    }

    @Override
    public void cancelTask(String tenant, String id) {
        jdbc.sql("UPDATE pre_task SET status = 'CANCELLED', lock_version = lock_version + 1 "
                        + "WHERE tenant_id = ? AND id = ? AND status IN ('OPEN','CLAIMED')")
                .params(tenant, id).update();
    }

    @Override
    public List<TaskRow> tasks(String tenant, String instanceId) {
        return jdbc.sql("SELECT " + TASK_COLUMNS + " FROM pre_task WHERE tenant_id = ? AND instance_id = ? "
                        + "ORDER BY created_at, id")
                .params(tenant, instanceId).query(JdbcProcessStore::task).list();
    }

    private static TaskRow task(ResultSet rs, int row) throws SQLException {
        String assignee = rs.getString("assignee_user") != null ? "user:" + rs.getString("assignee_user")
                : rs.getString("assignee_role") != null ? "role:" + rs.getString("assignee_role")
                : "group:" + rs.getString("assignee_group");
        return new TaskRow(rs.getString("tenant_id"), rs.getString("id"), rs.getString("instance_id"),
                rs.getString("token_id"), rs.getString("definition_id"), rs.getString("node_id"), rs.getString("name"),
                assignee, rs.getBoolean("four_eyes"), rs.getString("status"), rs.getLong("lock_version"),
                rs.getString("completed_by"), instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    // ---- history and idempotency --------------------------------------------------------------------------

    @Override
    public void appendHistory(String tenant, String instanceId, String definitionId, List<HistoryEntry> entries) {
        for (HistoryEntry e : entries) {
            jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                            + "token_id, actor_id, attempt_id, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)")
                    .params(tenant, ts(e.occurredAt()), Ulid.next(e.occurredAt()), instanceId, definitionId, e.type(),
                            e.nodeId(), e.tokenId(), e.actor(), e.attemptId(), Values.write(e.details()))
                    .update();
        }
    }

    @Override
    public Optional<IdempotencyRecord> idempotency(String tenant, String operation, String key, Instant now) {
        return jdbc.sql("SELECT tenant_id, operation, idempotency_key, request_hash, response_body::text AS body, expires_at "
                        + "FROM pre_idempotency WHERE tenant_id = ? AND operation = ? AND idempotency_key = ? "
                        + "AND expires_at > ?")
                .params(tenant, operation, key, ts(now))
                .query((rs, row) -> new IdempotencyRecord(rs.getString("tenant_id"), rs.getString("operation"),
                        rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getString("body"),
                        instant(rs, "expires_at")))
                .optional();
    }

    @Override
    public boolean claimIdempotency(String tenant, String operation, String key, String requestHash, Instant expiresAt,
            Instant now) {
        return jdbc.sql("INSERT INTO pre_idempotency (tenant_id, operation, idempotency_key, request_hash, status, "
                        + "response_body, expires_at) VALUES (?, ?, ?, ?, 'PENDING', NULL, ?) "
                        + "ON CONFLICT (tenant_id, operation, idempotency_key) DO UPDATE SET request_hash = EXCLUDED.request_hash, "
                        + "status = 'PENDING', response_body = NULL, expires_at = EXCLUDED.expires_at "
                        + "WHERE pre_idempotency.expires_at <= ?")
                .params(tenant, operation, key, requestHash, ts(expiresAt), ts(now))
                .update() == 1;
    }

    @Override
    public void completeIdempotency(String tenant, String operation, String key, String responseJson) {
        int updated = jdbc.sql("UPDATE pre_idempotency SET status = 'DONE', response_body = ?::jsonb "
                        + "WHERE tenant_id = ? AND operation = ? AND idempotency_key = ? AND status = 'PENDING'")
                .params(responseJson, tenant, operation, key).update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim lost for " + operation);
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
