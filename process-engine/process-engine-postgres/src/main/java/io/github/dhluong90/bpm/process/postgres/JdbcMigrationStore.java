package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.store.MigrationStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link MigrationStore} on PostgreSQL (#74, #90, migrations V23 to V25 and V34). Plans and their history are written by the
 * plan command only. The dry-run reads are plain {@code SELECT} statements on the live token and running instance
 * indexes; multi-row reads go through {@link ProcessListQueries}.
 */
public final class JdbcMigrationStore implements MigrationStore {

    private static final String PLAN_COLUMNS = "tenant_id, id, definition_key, source_definition_id, source_version, "
            + "target_definition_id, target_version, plan_hash, approval, status, created_by, created_at";

    /** Running instances of one version, on {@code idx_pre_instance_running_definition} (V25). */
    static final String RUNNING_SQL = "SELECT count(*) FROM pre_instance WHERE tenant_id = ? AND definition_id = ? "
            + "AND status = 'RUNNING'";
    /** Instances with a live token at any of the steps, on {@code idx_pre_token_live_node_instance} (V24). */
    static final String INSTANCES_AT_SQL = "SELECT count(DISTINCT instance_id) FROM pre_token WHERE tenant_id = ? "
            + "AND definition_id = ? AND status IN " + ProcessListQueries.LIVE_STATUSES
            + " AND current_node_id = ANY (?::text[])";

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcMigrationStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void insertPlan(StoredPlan plan) {
        MigrationPlan p = plan.view();
        jdbc.sql("INSERT INTO pre_migration (" + PLAN_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(plan.tenantId(), p.id(), p.definitionKey(), plan.sourceDefinitionId(), p.sourceVersion(),
                        plan.targetDefinitionId(), p.targetVersion(), p.planHash(), p.approval().value(), p.status(),
                        p.createdBy(), Timestamp.from(p.createdAt()))
                .update();
    }

    @Override
    public void insertHistory(String tenant, String id, String migrationId, String action, String actorId,
            Map<String, Object> details, Instant occurredAt) {
        jdbc.sql("INSERT INTO pre_migration_history (tenant_id, id, migration_id, action, actor_id, details, "
                        + "occurred_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)")
                .params(tenant, id, migrationId, action, actorId, Values.write(new LinkedHashMap<>(details)),
                        Timestamp.from(occurredAt))
                .update();
    }

    @Override
    public Optional<StoredPlan> findPlan(String tenant, String migrationId) {
        return jdbc.sql("SELECT " + PLAN_COLUMNS + " FROM pre_migration WHERE tenant_id = ? AND id = ?")
                .params(tenant, migrationId).query(JdbcMigrationStore::plan).optional();
    }

    @Override
    public Map<String, Long> liveTokensByNode(String tenant, String definitionId) {
        Map<String, Long> out = new LinkedHashMap<>();
        ProcessListQueries.MIGRATION_LIVE_NODES.list(jdbc, List.of(tenant, definitionId),
                (rs, n) -> Map.entry(rs.getString("current_node_id"), rs.getLong("live")))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    @Override
    public long runningInstances(String tenant, String definitionId) {
        return jdbc.sql(RUNNING_SQL).params(tenant, definitionId).query(Long.class).single();
    }

    @Override
    public long instancesAt(String tenant, String definitionId, Collection<String> nodeIds) {
        return jdbc.sql(INSTANCES_AT_SQL).params(tenant, definitionId, nodeIds.toArray(String[]::new))
                .query(Long.class).single();
    }

    @Override
    public List<String> instanceIdsAt(String tenant, String definitionId, String nodeId, int limit) {
        return ProcessListQueries.MIGRATION_INSTANCES_AT_NODE.list(jdbc, List.of(tenant, definitionId, nodeId, limit),
                (rs, n) -> rs.getString(1));
    }

    @Override
    public List<String> movableInstanceIds(String tenant, String definitionId, Collection<String> blockedNodeIds,
            int limit) {
        return ProcessListQueries.MIGRATION_MOVABLE_INSTANCES.list(jdbc, List.of(tenant, definitionId,
                blockedNodeIds.toArray(String[]::new), limit), (rs, n) -> rs.getString(1));
    }

    // ---- execution (#90) -------------------------------------------------------------------------------------

    private static final String EXECUTION_COLUMNS = "tenant_id, id, status, approval, lock_version, proposed_by, "
            + "proposed_at, reason, dry_run_checked_at, approved_by, approved_at, rejected_by, rejected_at, "
            + "rejection_reason, total_count, migrated_count, failed_count, skipped_count, cursor_instance_id, "
            + "lease_owner, lease_until, started_at, finished_at";

    @Override
    public Optional<Execution> lockExecution(String tenant, String migrationId) {
        return jdbc.sql("SELECT " + EXECUTION_COLUMNS + " FROM pre_migration WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(tenant, migrationId).query(JdbcMigrationStore::execution).optional();
    }

    @Override
    public Optional<Execution> findExecution(String tenant, String migrationId) {
        return jdbc.sql("SELECT " + EXECUTION_COLUMNS + " FROM pre_migration WHERE tenant_id = ? AND id = ?")
                .params(tenant, migrationId).query(JdbcMigrationStore::execution).optional();
    }

    @Override
    public void updateExecution(Execution e) {
        jdbc.sql("UPDATE pre_migration SET status = ?, proposed_by = ?, proposed_at = ?, reason = ?, "
                        + "dry_run_checked_at = ?, approved_by = ?, approved_at = ?, rejected_by = ?, rejected_at = ?, "
                        + "rejection_reason = ?, total_count = ?, lock_version = lock_version + 1 "
                        + "WHERE tenant_id = ? AND id = ?")
                .params(e.status(), e.proposedBy(), ts(e.proposedAt()), e.reason(), ts(e.dryRunCheckedAt()),
                        e.approvedBy(), ts(e.approvedAt()), e.rejectedBy(), ts(e.rejectedAt()), e.rejectionReason(),
                        e.total(), e.tenantId(), e.migrationId())
                .update();
    }

    @Override
    public Optional<Execution> claimRunnable(String owner, Instant now, Instant leaseUntil) {
        return jdbc.sql("UPDATE pre_migration m SET status = 'RUNNING', lease_owner = ?, lease_until = ?, "
                        + "started_at = coalesce(m.started_at, ?) FROM (SELECT tenant_id, id FROM pre_migration "
                        + "WHERE status IN ('APPROVED','RUNNING') AND (lease_until IS NULL OR lease_until <= ?) "
                        + "ORDER BY lease_until NULLS FIRST, tenant_id, id LIMIT 1 FOR UPDATE SKIP LOCKED) c "
                        + "WHERE m.tenant_id = c.tenant_id AND m.id = c.id RETURNING m.tenant_id, m.id, m.status, "
                        + "m.approval, m.lock_version, m.proposed_by, m.proposed_at, m.reason, m.dry_run_checked_at, "
                        + "m.approved_by, m.approved_at, m.rejected_by, m.rejected_at, m.rejection_reason, "
                        + "m.total_count, m.migrated_count, m.failed_count, m.skipped_count, m.cursor_instance_id, "
                        + "m.lease_owner, m.lease_until, m.started_at, m.finished_at")
                .params(owner, ts(leaseUntil), ts(now), ts(now)).query(JdbcMigrationStore::execution).optional();
    }

    @Override
    public boolean advance(String tenant, String migrationId, String owner, Instant now, Instant leaseUntil,
            String cursor, int migrated, int failed, int skipped) {
        return jdbc.sql("UPDATE pre_migration SET cursor_instance_id = ?, migrated_count = migrated_count + ?, "
                        + "failed_count = failed_count + ?, skipped_count = skipped_count + ?, lease_until = ? "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' AND lease_owner = ? AND lease_until > ?")
                .params(cursor, migrated, failed, skipped, ts(leaseUntil), tenant, migrationId, owner, ts(now))
                .update() == 1;
    }

    @Override
    public boolean renewLease(String tenant, String migrationId, String owner, Instant now, Instant leaseUntil) {
        return jdbc.sql("UPDATE pre_migration SET lease_until = ? WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' "
                + "AND lease_owner = ? AND lease_until > ?").params(ts(leaseUntil), tenant, migrationId, owner, ts(now))
                .update() == 1;
    }

    @Override
    public boolean finish(String tenant, String migrationId, String owner, String status, Instant now) {
        return jdbc.sql("UPDATE pre_migration SET status = ?, finished_at = ?, lease_owner = NULL, lease_until = NULL "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'RUNNING' AND lease_owner = ? AND lease_until > ?")
                .params(status, ts(now), tenant, migrationId, owner, ts(now)).update() == 1;
    }

    @Override
    public void release(String tenant, String migrationId, String owner) {
        jdbc.sql("UPDATE pre_migration SET lease_owner = NULL, lease_until = NULL WHERE tenant_id = ? AND id = ? "
                + "AND status = 'RUNNING' AND lease_owner = ?").params(tenant, migrationId, owner).update();
    }

    @Override
    public List<String> nextBatch(String tenant, String definitionId, String cursor, int limit) {
        return ProcessListQueries.MIGRATION_BATCH.list(jdbc, List.of(tenant, definitionId, cursor, limit),
                (rs, n) -> rs.getString(1));
    }

    @Override
    public List<LiveToken> liveTokens(String tenant, String instanceId) {
        return ProcessListQueries.MIGRATION_INSTANCE_TOKENS.list(jdbc, List.of(tenant, instanceId),
                (rs, n) -> new LiveToken(rs.getString("id"), rs.getString("current_node_id")));
    }

    @Override
    public Optional<LockedInstance> lockInstance(String tenant, String instanceId) {
        try {
            return jdbc.sql("SELECT definition_id, status FROM pre_instance WHERE tenant_id = ? AND id = ? "
                            + "FOR UPDATE NOWAIT").params(tenant, instanceId)
                    .query((rs, n) -> new LockedInstance(rs.getString(1), rs.getString(2))).optional();
        } catch (DataAccessException e) {
            if (lockNotAvailable(e)) {
                throw new InstanceBusyException(instanceId);
            }
            throw e;
        }
    }

    private static boolean lockNotAvailable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void moveInstance(String tenant, String instanceId, String targetDefinitionId, int targetVersion) {
        jdbc.sql("UPDATE pre_instance SET definition_id = ?, definition_ver = ?, lock_version = lock_version + 1 "
                + "WHERE tenant_id = ? AND id = ?").params(targetDefinitionId, targetVersion, tenant, instanceId)
                .update();
        jdbc.sql("UPDATE pre_token SET definition_id = ? WHERE tenant_id = ? AND instance_id = ?")
                .params(targetDefinitionId, tenant, instanceId).update();
        jdbc.sql("UPDATE pre_task SET definition_id = ? WHERE tenant_id = ? AND instance_id = ? "
                + "AND status IN ('OPEN','CLAIMED')").params(targetDefinitionId, tenant, instanceId).update();
        jdbc.sql("UPDATE pre_timer SET definition_id = ? WHERE tenant_id = ? AND instance_id = ? "
                + "AND status IN ('DUE','CLAIMED')").params(targetDefinitionId, tenant, instanceId).update();
        jdbc.sql("UPDATE pre_step_result SET definition_id = ? WHERE tenant_id = ? AND instance_id = ?")
                .params(targetDefinitionId, tenant, instanceId).update();
        jdbc.sql("UPDATE pre_incident SET definition_id = ? WHERE tenant_id = ? AND instance_id = ? "
                + "AND status IN ('OPEN','RESOLUTION_PENDING')").params(targetDefinitionId, tenant, instanceId)
                .update();
    }

    @Override
    public boolean hasOutcome(String tenant, String migrationId, String instanceId) {
        return jdbc.sql("SELECT count(*) FROM pre_migration_instance WHERE tenant_id = ? AND migration_id = ? "
                + "AND instance_id = ?").params(tenant, migrationId, instanceId).query(Long.class).single() > 0;
    }

    @Override
    public boolean insertOutcome(String tenant, String migrationId, MigrationOutcome o) {
        return jdbc.sql("INSERT INTO pre_migration_instance (tenant_id, migration_id, instance_id, outcome, code, "
                        + "detail, incident_id, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")
                .params(tenant, migrationId, o.instanceId(), o.outcome(), o.code(), o.detail(), o.incidentId(),
                        Timestamp.from(o.occurredAt()))
                .update() == 1;
    }

    @Override
    public List<MigrationOutcome> outcomes(String tenant, String migrationId, String outcome, String afterInstanceId,
            int limit) {
        org.springframework.jdbc.core.RowMapper<MigrationOutcome> row = (rs, n) -> new MigrationOutcome(
                rs.getString("instance_id"), rs.getString("outcome"), rs.getString("code"), rs.getString("detail"),
                rs.getString("incident_id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant());
        return outcome == null
                ? ProcessListQueries.MIGRATION_OUTCOMES.list(jdbc, List.of(tenant, migrationId, afterInstanceId, limit),
                        row)
                : ProcessListQueries.MIGRATION_OUTCOMES_OF.list(jdbc, List.of(tenant, migrationId, afterInstanceId,
                        outcome, limit), row);
    }

    @Override
    public List<MigrationOutcome> failuresWithoutIncident(String tenant, String migrationId, String afterInstanceId,
            int limit) {
        return ProcessListQueries.MIGRATION_FAILURES_WITHOUT_INCIDENT.list(jdbc, List.of(tenant, migrationId,
                afterInstanceId, limit), (rs, n) -> new MigrationOutcome(rs.getString("instance_id"),
                rs.getString("outcome"), rs.getString("code"), rs.getString("detail"), rs.getString("incident_id"),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant()));
    }

    @Override
    public void setIncident(String tenant, String migrationId, String instanceId, String incidentId) {
        jdbc.sql("UPDATE pre_migration_instance SET incident_id = ? WHERE tenant_id = ? AND migration_id = ? "
                + "AND instance_id = ?").params(incidentId, tenant, migrationId, instanceId).update();
    }

    @Override
    public void limitLockWait(java.time.Duration wait) {
        // SET LOCAL takes no bind parameter; the value is a number of milliseconds built here, never from a request.
        jdbc.sql("SET LOCAL lock_timeout = '" + Math.max(1, wait.toMillis()) + "ms'").update();
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static Execution execution(ResultSet rs, int row) throws SQLException {
        return new Execution(rs.getString("tenant_id"), rs.getString("id"), rs.getString("status"),
                MigrationPlan.Approval.of(rs.getString("approval")), rs.getLong("lock_version"),
                rs.getString("proposed_by"), instant(rs, "proposed_at"), rs.getString("reason"),
                instant(rs, "dry_run_checked_at"), rs.getString("approved_by"), instant(rs, "approved_at"),
                rs.getString("rejected_by"), instant(rs, "rejected_at"), rs.getString("rejection_reason"),
                rs.getLong("total_count"), rs.getLong("migrated_count"), rs.getLong("failed_count"),
                rs.getLong("skipped_count"), rs.getString("cursor_instance_id"), rs.getString("lease_owner"),
                instant(rs, "lease_until"), instant(rs, "started_at"), instant(rs, "finished_at"));
    }

    private static StoredPlan plan(ResultSet rs, int row) throws SQLException {
        MigrationPlan view = new MigrationPlan(rs.getString("id"), rs.getString("definition_key"),
                rs.getInt("source_version"), rs.getInt("target_version"), rs.getString("status"),
                rs.getString("plan_hash"), MigrationPlan.Approval.of(rs.getString("approval")),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
        return new StoredPlan(rs.getString("tenant_id"), view, rs.getString("source_definition_id"),
                rs.getString("target_definition_id"));
    }
}
