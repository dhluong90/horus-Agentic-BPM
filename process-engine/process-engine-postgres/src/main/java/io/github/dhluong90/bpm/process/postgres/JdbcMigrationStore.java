package io.github.dhluong90.bpm.process.postgres;

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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link MigrationStore} on PostgreSQL (#74, migrations V23 to V25). Plans and their history are written by the
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

    private static StoredPlan plan(ResultSet rs, int row) throws SQLException {
        MigrationPlan view = new MigrationPlan(rs.getString("id"), rs.getString("definition_key"),
                rs.getInt("source_version"), rs.getInt("target_version"), rs.getString("status"),
                rs.getString("plan_hash"), MigrationPlan.Approval.of(rs.getString("approval")),
                rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
        return new StoredPlan(rs.getString("tenant_id"), view, rs.getString("source_definition_id"),
                rs.getString("target_definition_id"));
    }
}
