package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.store.JobStore;
import io.github.dhluong90.bpm.process.postgres.ListQuery.Bound;
import java.util.ArrayList;
import java.util.List;

/**
 * The registry of every multi-row read of the Process Engine store ({@code PRE-036}). Each query declares its
 * bound; the query-plan check runs {@code EXPLAIN} for each entry of {@link #all()}. A query built from parts,
 * such as the instance list, registers its smallest and its fullest form.
 *
 * <p>To add a list query: add it here with its bound, read it through {@link ListQuery#list}, and give the
 * query-plan check sample parameters for it. A multi-row read anywhere else fails the build.
 */
final class ProcessListQueries {

    /**
     * The live statuses, written exactly as in the predicate of {@code idx_pre_token_live} (V1), so the planner can
     * answer the count with an index-only scan of that partial index and never touches {@code pre_history}.
     */
    static final String LIVE_STATUSES = "('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT')";

    static final String DEFINITION_COLUMNS = "tenant_id, id, key_name, version_num, status, name, "
            + "definition_json::text AS definition_json, definition_hash, source_format, source_document, source_hash, "
            + "node_count, source, effective_from, created_by, created_at, approved_by, approved_at, lock_version, "
            + "requested_by, requested_at, decision_comment, retired_by, retired_at, superseded_by";

    static final String INSTANCE_COLUMNS = "tenant_id, id, definition_id, definition_key, definition_ver, "
            + "business_key, status, variables::text AS variables, seq, lock_version, started_by, started_at, ended_at";

    static final String TASK_COLUMNS = "tenant_id, id, instance_id, token_id, definition_id, node_id, name, "
            + "assignee_user, assignee_role, assignee_group, four_eyes, status, lock_version, claimed_by, claimed_at, "
            + "completed_by, created_at, completed_at";

    /** Live tokens per node of one definition version (PRE-028). */
    static final ListQuery LIVE_COUNTS = new ListQuery("live-token-counts",
            new Bound.ParentKey("definition_id", "one row per node and status of one definition version"),
            "SELECT current_node_id, status, count(*) AS live FROM pre_token "
                    + "WHERE tenant_id = ? AND definition_id = ? AND status IN " + LIVE_STATUSES + " "
                    + "GROUP BY current_node_id, status");

    /** Every version of one key, locked, for a lifecycle change. */
    static final ListQuery LOCK_DEFINITIONS = new ListQuery("lock-definition-versions",
            new Bound.ParentKey("key_name", "the versions of one definition key"),
            "SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE tenant_id = ? AND key_name = ? "
                    + "ORDER BY version_num FOR UPDATE");

    /** The lifecycle actions of one definition version. */
    static final ListQuery DEFINITION_HISTORY = new ListQuery("definition-history",
            new Bound.ParentKey("definition_id", "the lifecycle actions of one definition version"),
            "SELECT tenant_id, id, definition_id, action, from_status, to_status, actor_id, comment, "
                    + "details::text AS details, occurred_at FROM pre_definition_history "
                    + "WHERE tenant_id = ? AND definition_id = ? ORDER BY occurred_at, id");

    /** The tokens of one instance. */
    static final ListQuery TOKENS = new ListQuery("instance-tokens",
            new Bound.ParentKey("instance_id", "the tokens of one instance, at most a few per node of its definition"),
            "SELECT id, token_kind, status, scope_token_id, current_node_id, activity_instance_id, "
                    + "locals::text AS locals FROM pre_token WHERE tenant_id = ? AND instance_id = ? ORDER BY id");

    /** The tasks of one instance. */
    static final ListQuery TASKS = new ListQuery("instance-tasks",
            new Bound.ParentKey("instance_id", "the tasks of one instance, at most a few per user task node"),
            "SELECT " + TASK_COLUMNS + " FROM pre_task WHERE tenant_id = ? AND instance_id = ? ORDER BY created_at, id");

    /** The first page of definition keys. */
    static final ListQuery DEFINITION_KEYS = definitionKeys(false);
    /** A later page of definition keys, after the last key of the previous page. */
    static final ListQuery DEFINITION_KEYS_AFTER = definitionKeys(true);
    /** Due timers, for every tenant: the poll of each replica. */
    static final ListQuery CLAIM_TIMERS = claim(false);
    /** Due remote steps, for every tenant: the poll of the async runner. */
    static final ListQuery CLAIM_ASYNC = claim(true);

    private ProcessListQueries() {
    }

    /** Every registered query, the instance list in its smallest and fullest form. */
    static List<ListQuery> all() {
        return List.of(LIVE_COUNTS, LOCK_DEFINITIONS, DEFINITION_HISTORY, TOKENS, TASKS, DEFINITION_KEYS,
                DEFINITION_KEYS_AFTER, CLAIM_TIMERS, CLAIM_ASYNC,
                instances(1, false, false, false, false),
                instances(3, true, true, true, true));
    }

    /**
     * The latest version of each key by the {@code (tenant_id, key_name, version_num)} unique index, with the
     * effective version and the last change per key; keyset on the key, never {@code OFFSET}. Parameters: now,
     * tenant, [after key], limit.
     */
    private static ListQuery definitionKeys(boolean after) {
        return new ListQuery(after ? "definition-keys-after" : "definition-keys",
                new Bound.Page("keyset on key_name of the (tenant_id, key_name, version_num) unique index"),
                "SELECT d.key_name, d.name, d.version_num, d.status, "
                        + "(SELECT max(e.version_num) FROM pre_definition e WHERE e.tenant_id = d.tenant_id "
                        + "AND e.key_name = d.key_name AND e.status = 'ACTIVE' AND e.effective_from <= ?) AS effective_version, "
                        + "(SELECT max(GREATEST(c.created_at, COALESCE(c.approved_at, c.created_at))) FROM pre_definition c "
                        + "WHERE c.tenant_id = d.tenant_id AND c.key_name = d.key_name) AS last_changed_at "
                        + "FROM pre_definition d WHERE d.tenant_id = ? "
                        + (after ? "AND d.key_name > ? " : "")
                        + "AND d.version_num = (SELECT max(m.version_num) FROM pre_definition m WHERE m.tenant_id = d.tenant_id "
                        + "AND m.key_name = d.key_name) ORDER BY d.key_name LIMIT ?");
    }

    /**
     * The instance list (PRE-049), served by {@code idx_pre_instance_status (tenant_id, status, started_at DESC)};
     * keyset on {@code (started_at, id)}. Parameters: tenant, the statuses, [definition key], [from], [to],
     * [after started at, after id], limit.
     */
    static ListQuery instances(int statuses, boolean byKey, boolean from, boolean to, boolean after) {
        if (statuses < 1) {
            throw new IllegalArgumentException("The instance list needs at least one status");
        }
        StringBuilder sql = new StringBuilder("SELECT " + INSTANCE_COLUMNS + " FROM pre_instance WHERE tenant_id = ? "
                + "AND status IN (");
        for (int i = 0; i < statuses; i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")");
        if (byKey) {
            sql.append(" AND definition_key = ?");
        }
        if (from) {
            sql.append(" AND started_at >= ?");
        }
        if (to) {
            sql.append(" AND started_at < ?");
        }
        if (after) {
            sql.append(" AND (started_at, id) < (?, ?)");
        }
        sql.append(" ORDER BY started_at DESC, id DESC LIMIT ?");
        List<String> parts = new ArrayList<>(List.of("instances", String.valueOf(statuses)));
        if (byKey) {
            parts.add("key");
        }
        if (from || to) {
            parts.add("range");
        }
        if (after) {
            parts.add("after");
        }
        return new ListQuery(String.join("-", parts),
                new Bound.Page("newest first on idx_pre_instance_status (tenant_id, status, started_at DESC)"),
                sql.toString());
    }

    /**
     * One claim statement: a {@code FOR UPDATE SKIP LOCKED} select of due rows and the update that leases them.
     * {@code async} picks the remote-step kinds or every other kind; never caller text. Parameters: now, now, limit,
     * replica id, now.
     */
    private static ListQuery claim(boolean async) {
        return new ListQuery(async ? "claim-async-steps" : "claim-timers",
                new Bound.Page("earliest due first on idx_pre_timer_poll, at most one poll batch"),
                "WITH due AS ("
                        + "SELECT tenant_id, id FROM pre_timer "
                        + "WHERE status IN ('DUE','CLAIMED') AND next_fire_at <= ? "
                        + "AND (status = 'DUE' OR locked_until <= ?) "
                        + "AND job_kind " + (async ? "IN" : "NOT IN")
                        + " ('" + JobStore.ASYNC_SERVICE + "','" + JobStore.ASYNC_AGENT + "') "
                        + "ORDER BY next_fire_at, id LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "UPDATE pre_timer t SET status = 'CLAIMED', locked_by = ?, "
                        + "locked_until = ?::timestamptz + t.lease_duration, attempt = t.attempt + 1 "
                        + "FROM due WHERE t.tenant_id = due.tenant_id AND t.id = due.id "
                        + "RETURNING t.tenant_id, t.id, t.instance_id, t.definition_id, t.token_id, t.job_kind, t.node_id, "
                        + "t.status, t.next_fire_at, (EXTRACT(EPOCH FROM t.lease_duration) * 1000)::bigint AS lease_ms, "
                        + "t.locked_by, t.locked_until, t.attempt, t.max_attempts, t.payload::text AS payload");
    }
}
