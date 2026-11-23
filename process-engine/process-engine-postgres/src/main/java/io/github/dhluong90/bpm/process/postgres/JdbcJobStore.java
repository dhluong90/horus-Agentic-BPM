package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.postgres.ProcessListQueries.ClaimKinds;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link JobStore} on the host's PostgreSQL through {@link JdbcClient}. The claim is one statement: a
 * {@code FOR UPDATE SKIP LOCKED} select of due rows and the update that leases them, so two replicas polling at the
 * same moment never claim the same row and never wait for each other. Lease ends are computed per row in SQL from
 * {@code lease_duration}.
 */
public final class JdbcJobStore implements JobStore {

    private static final String COLUMNS = "tenant_id, id, instance_id, definition_id, token_id, job_kind, node_id, status, "
            + "next_fire_at, (EXTRACT(EPOCH FROM lease_duration) * 1000)::bigint AS lease_ms, locked_by, locked_until, "
            + "attempt, max_attempts, payload::text AS payload";

    private final JdbcClient jdbc;

    /** @param dataSource the host's data source */
    public JdbcJobStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void insertJob(JobRow job, Instant now) {
        if (!"DUE".equals(job.status()) && !"FAILED".equals(job.status())) {
            throw new IllegalArgumentException("A new job is DUE, or FAILED before any call; not " + job.status());
        }
        jdbc.sql("INSERT INTO pre_timer (tenant_id, id, instance_id, definition_id, token_id, job_kind, node_id, status, "
                        + "next_fire_at, lease_duration, attempt, max_attempts, payload, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, make_interval(secs => ?), 0, ?, ?::jsonb, ?)")
                .params(job.tenantId(), job.id(), job.instanceId(), job.definitionId(), job.tokenId(), job.jobKind(),
                        job.nodeId(), job.status(), ts(job.nextFireAt()), job.leaseDuration().toMillis() / 1000.0, job.maxAttempts(),
                        job.payloadJson(), ts(now))
                .update();
    }

    @Override
    public List<JobRow> claimDue(String replicaId, Instant now, int limit) {
        return claim(ClaimKinds.TIMERS, null, replicaId, now, limit);
    }

    @Override
    public List<JobRow> claimDueAsync(String replicaId, Instant now, int limit) {
        return limit < 1 ? List.of() : claim(ClaimKinds.ASYNC, null, replicaId, now, limit);
    }

    @Override
    public List<JobRow> claimDueAsync(String replicaId, Instant now, String jobKind, int limit) {
        if (!JobStore.isAsync(jobKind)) {
            throw new IllegalArgumentException("Not an async job kind: " + jobKind);
        }
        // One kind per statement, on idx_pre_timer_poll_kind: a backlog of another kind is never scanned.
        return limit < 1 ? List.of() : claim(ClaimKinds.ONE_KIND, jobKind, replicaId, now, limit);
    }

    @Override
    public Optional<JobRow> lockJob(String tenant, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pre_timer WHERE tenant_id = ? AND id = ? FOR UPDATE")
                .params(tenant, id).query(JdbcJobStore::job).optional();
    }

    /**
     * One claim statement of {@link ProcessListQueries}: the remote-step kinds, one remote-step kind or every other
     * kind. {@code kind} is the bind parameter of {@link ClaimKinds#ONE_KIND}, null for the others.
     */
    private List<JobRow> claim(ClaimKinds kinds, String kind, String replicaId, Instant now, int limit) {
        ListQuery query = switch (kinds) {
            case TIMERS -> ProcessListQueries.CLAIM_TIMERS;
            case ASYNC -> ProcessListQueries.CLAIM_ASYNC;
            case ONE_KIND -> ProcessListQueries.CLAIM_ASYNC_KIND;
        };
        return query.list(jdbc, ProcessListQueries.claimParams(kinds, kind, ts(now), limit, replicaId), JdbcJobStore::job)
                .stream().sorted((a, b) -> {
                    int byDue = a.nextFireAt().compareTo(b.nextFireAt());
                    return byDue != 0 ? byDue : a.id().compareTo(b.id());
                }).toList();
    }

    @Override
    public Optional<JobRow> lockClaim(String tenant, String id, String replicaId, int attempt, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pre_timer WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' "
                        + "AND locked_by = ? AND attempt = ? AND locked_until > ? FOR UPDATE")
                .params(tenant, id, replicaId, attempt, ts(now)).query(JdbcJobStore::job).optional();
    }

    @Override
    public boolean markDone(String tenant, String id, String replicaId, int attempt, Instant now) {
        return jdbc.sql("UPDATE pre_timer SET status = 'DONE', completed_at = ?, locked_until = NULL "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND locked_by = ? AND attempt = ?")
                .params(ts(now), tenant, id, replicaId, attempt).update() == 1;
    }

    @Override
    public boolean release(String tenant, String id, String replicaId, int attempt, Instant retryAt, boolean failed,
            Instant now) {
        return jdbc.sql("UPDATE pre_timer SET status = ?, next_fire_at = ?, locked_by = NULL, locked_until = NULL, "
                        + "completed_at = ? WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND locked_by = ? "
                        + "AND attempt = ?")
                .params(failed ? "FAILED" : "DUE", ts(retryAt), failed ? ts(now) : null, tenant, id, replicaId, attempt)
                .update() == 1;
    }

    @Override
    public boolean retrySchema(String tenant, String id, String replicaId, int attempt, Instant retryAt) {
        return jdbc.sql("UPDATE pre_timer SET status = 'DUE', next_fire_at = ?, locked_by = NULL, locked_until = NULL, "
                        + "max_attempts = max_attempts + 1, payload = jsonb_set(payload, '{" + SCHEMA_RETRIES + "}', "
                        + "to_jsonb(COALESCE((payload ->> '" + SCHEMA_RETRIES + "')::int, 0) + 1)) "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND locked_by = ? AND attempt = ?")
                .params(ts(retryAt), tenant, id, replicaId, attempt)
                .update() == 1;
    }

    @Override
    public int cancelJob(String tenant, String id, Instant now) {
        return jdbc.sql("UPDATE pre_timer SET status = 'CANCELLED', completed_at = ?, locked_until = NULL "
                        + "WHERE tenant_id = ? AND id = ? AND status IN ('DUE','CLAIMED')")
                .params(ts(now), tenant, id).update();
    }

    @Override
    public int cancelDefinitionJobs(String tenant, String definitionId, Instant now) {
        return jdbc.sql("UPDATE pre_timer SET status = 'CANCELLED', completed_at = ?, locked_until = NULL "
                        + "WHERE tenant_id = ? AND definition_id = ? AND job_kind = 'DEFINITION_EFFECTIVE' "
                        + "AND status IN ('DUE','CLAIMED')")
                .params(ts(now), tenant, definitionId).update();
    }

    @Override
    public boolean issueCompletionToken(String tenant, String id, int attempt, byte[] tokenHash) {
        return jdbc.sql("UPDATE pre_timer SET completion_token_hash = ? "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'CLAIMED' AND attempt = ?")
                .params(tokenHash, tenant, id, attempt).update() == 1;
    }

    @Override
    public Optional<byte[]> completionTokenHash(String tenant, String id) {
        return jdbc.sql("SELECT completion_token_hash FROM pre_timer WHERE tenant_id = ? AND id = ? "
                        + "AND completion_token_hash IS NOT NULL")
                .params(tenant, id).query((rs, row) -> rs.getBytes(1)).optional();
    }

    @Override
    public boolean markLateResult(String tenant, String id, Instant now) {
        return jdbc.sql("UPDATE pre_timer SET late_result_at = ? WHERE tenant_id = ? AND id = ? AND late_result_at IS NULL")
                .params(ts(now), tenant, id).update() == 1;
    }

    @Override
    public boolean retryFailed(String tenant, String id, Instant now) {
        return jdbc.sql("UPDATE pre_timer SET status = 'DUE', next_fire_at = ?, locked_by = NULL, locked_until = NULL, "
                        + "completed_at = NULL, max_attempts = attempt + 1 "
                        + "WHERE tenant_id = ? AND id = ? AND status = 'FAILED'")
                .params(ts(now), tenant, id).update() == 1;
    }

    @Override
    public Optional<JobRow> findJob(String tenant, String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM pre_timer WHERE tenant_id = ? AND id = ?")
                .params(tenant, id).query(JdbcJobStore::job).optional();
    }

    private static JobRow job(ResultSet rs, int row) throws SQLException {
        return new JobRow(rs.getString("tenant_id"), rs.getString("id"), rs.getString("instance_id"),
                rs.getString("definition_id"), rs.getString("token_id"), rs.getString("job_kind"), rs.getString("node_id"),
                rs.getString("status"), instant(rs, "next_fire_at"), Duration.ofMillis(rs.getLong("lease_ms")),
                rs.getString("locked_by"), instant(rs, "locked_until"), rs.getInt("attempt"), rs.getInt("max_attempts"),
                rs.getString("payload"));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
