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
            + "completed_by, created_at, completed_at, review::text AS review";

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
    /** The distinct actors of one event type in the history of one instance, such as the makers of a step. */
    static final ListQuery HISTORY_ACTORS = new ListQuery("instance-history-actors",
            new Bound.ParentKey("instance_id", "the actors in the history of one instance, at most one per event"),
            "SELECT DISTINCT actor_id FROM pre_history WHERE tenant_id = ? AND instance_id = ? AND event_type = ? "
                    + "AND actor_id IS NOT NULL");

    /** The open incidents of one instance, on {@code idx_pre_incident_instance} (V13). */
    static final ListQuery OPEN_INCIDENTS = new ListQuery("instance-open-incidents",
            new Bound.ParentKey("instance_id", "the open incidents of one instance, at most one per waiting step"),
            "SELECT tenant_id, id, instance_id, definition_id, token_id, node_id, error_code, cause_summary, "
                    + "retry_count, status, lock_version, created_at, resolved_at FROM pre_incident "
                    + "WHERE tenant_id = ? AND instance_id = ? AND status = 'OPEN' ORDER BY created_at, id");

    /**
     * The step results agent steps read as memory (#101), on the primary key of {@code pre_step_result} (V14). The
     * agent step's declared memory lists the source steps; the instance list registers one and three of them.
     */
    static ListQuery stepResults(int nodes) {
        if (nodes < 1) {
            throw new IllegalArgumentException("The step results of at least one node");
        }
        return new ListQuery("instance-step-results-" + nodes,
                new Bound.ParentKey("instance_id", "the results of the steps one agent step declares as memory"),
                "SELECT node_id, result_json::text AS result FROM pre_step_result WHERE tenant_id = ? AND "
                        + "instance_id = ? AND node_id IN (" + String.join(", ", java.util.Collections.nCopies(nodes, "?"))
                        + ")");
    }

    /**
     * The recorded answers of one agent step in a period, oldest first (#98): the primary key range of
     * {@code pre_history} on {@code (tenant_id, occurred_at)} with a period of at most {@code query.max-range}, keyset
     * on {@code (occurred_at, id)}. Parameters: tenant, key, node, from, to, [after at, after id], limit.
     */
    static ListQuery replayAnswers(boolean after) {
        return new ListQuery(after ? "replay-answers-after" : "replay-answers",
                new Bound.Page("oldest first on the pre_history primary key, inside a period of at most query.max-range"),
                "SELECT h.occurred_at, h.id, h.instance_id, h.event_type, h.attempt_id, h.details::text AS details, "
                        + "j.payload::text AS payload FROM pre_history h "
                        + "JOIN pre_definition d ON d.tenant_id = h.tenant_id AND d.id = h.definition_id "
                        + "LEFT JOIN pre_timer j ON j.tenant_id = h.tenant_id AND j.id = h.details->>'jobId' "
                        + "AND j.job_kind = 'ASYNC_AGENT' "
                        + "WHERE h.tenant_id = ? AND d.key_name = ? AND h.node_id = ? "
                        + "AND h.event_type IN ('ASYNC_RESULT_ACCEPTED','ASYNC_RESULT_REFUSED') "
                        + "AND h.details->'outputFields' IS NOT NULL AND h.occurred_at >= ? AND h.occurred_at < ? "
                        + (after ? "AND (h.occurred_at, h.id) > (?, ?) " : "")
                        + "ORDER BY h.occurred_at, h.id LIMIT ?");
    }

    /** The first page of recorded answers of one agent step. */
    static final ListQuery REPLAY_ANSWERS = replayAnswers(false);
    /** A later page of recorded answers. */
    static final ListQuery REPLAY_ANSWERS_AFTER = replayAnswers(true);

    /**
     * The reviewer overrides among the answers of one replay page (#98), through {@code idx_pre_history_instance}:
     * the instances of that page only.
     */
    static final ListQuery REPLAY_OVERRIDES = new ListQuery("replay-overrides",
            new Bound.ParentKey("instance_id", "the instances of one page of recorded answers"),
            "SELECT details->>'refusedAttemptId' AS attempt_id FROM pre_history WHERE tenant_id = ? "
                    + "AND instance_id = ANY (?) AND event_type = 'HUMAN_OVERRIDE' "
                    + "AND details->>'refusedAttemptId' = ANY (?) AND EXISTS (SELECT 1 FROM jsonb_each(details->'changes') c "
                    + "WHERE c.value->>'from' IS DISTINCT FROM '***')");

    /** One page of the cases of a replay run, on the primary key of {@code pre_agent_replay_case} (V17). */
    static final ListQuery REPLAY_CASES = new ListQuery("replay-cases",
            new Bound.Page("by seq on the (tenant_id, replay_id, seq) primary key"),
            "SELECT seq, attempt_id, instance_id, recorded_at, recorded_outcome, candidate_outcome, change, "
                    + "recorded_rule_ids::text AS recorded_rule_ids, candidate_rule_ids::text AS candidate_rule_ids, "
                    + "code, overridden, masked_fields FROM pre_agent_replay_case "
                    + "WHERE tenant_id = ? AND replay_id = ? AND seq > ? ORDER BY seq LIMIT ?");

    /** Due timers, for every tenant: the poll of each replica. */
    static final ListQuery CLAIM_TIMERS = claim(ClaimKinds.TIMERS);
    /** Due remote steps, for every tenant: the poll of the async runner. */
    static final ListQuery CLAIM_ASYNC = claim(ClaimKinds.ASYNC);
    /** Due remote steps of one kind, for every tenant: the poll of that kind's runner, on the V9 index. */
    static final ListQuery CLAIM_ASYNC_KIND = claim(ClaimKinds.ONE_KIND);

    private ProcessListQueries() {
    }

    /** Every registered query, the instance list in its smallest and fullest form. */
    static List<ListQuery> all() {
        return List.of(LIVE_COUNTS, LOCK_DEFINITIONS, DEFINITION_HISTORY, TOKENS, TASKS, DEFINITION_KEYS,
                DEFINITION_KEYS_AFTER, HISTORY_ACTORS, CLAIM_TIMERS, CLAIM_ASYNC, CLAIM_ASYNC_KIND, OPEN_INCIDENTS,
                stepResults(1), stepResults(3), REPLAY_ANSWERS, REPLAY_ANSWERS_AFTER, REPLAY_OVERRIDES, REPLAY_CASES,
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
     * The job kinds a claim statement takes, as a constant predicate on {@code job_kind}; never caller text.
     * {@link #ONE_KIND} is the only one with a bind parameter, the kind itself.
     */
    enum ClaimKinds {
        /** Every kind but the remote steps, on {@code idx_pre_timer_poll}. */
        TIMERS("claim-timers", "job_kind NOT IN " + asyncKinds(), "idx_pre_timer_poll"),
        /** Both remote-step kinds, on {@code idx_pre_timer_poll}. */
        ASYNC("claim-async-steps", "job_kind IN " + asyncKinds(), "idx_pre_timer_poll"),
        /** One remote-step kind, bound as a parameter, on {@code idx_pre_timer_poll_kind} (V9). */
        ONE_KIND("claim-async-kind", "job_kind = ?", "idx_pre_timer_poll_kind (V9)");

        private final String queryName;
        private final String predicate;
        private final String index;

        ClaimKinds(String queryName, String predicate, String index) {
            this.queryName = queryName;
            this.predicate = predicate;
            this.index = index;
        }

        /** Whether the statement binds the kind after the two due-time parameters. */
        boolean bindsKind() {
            return this == ONE_KIND;
        }

        private static String asyncKinds() {
            return "('" + JobStore.ASYNC_SERVICE + "','" + JobStore.ASYNC_AGENT + "')";
        }
    }

    /**
     * The parameters of a claim statement, in placeholder order: now, now, [kind], limit, replica id, now.
     *
     * @param kinds the claim statement
     * @param kind the job kind, required for {@link ClaimKinds#ONE_KIND} and refused otherwise
     */
    static List<Object> claimParams(ClaimKinds kinds, String kind, Object now, int limit, String replicaId) {
        if (kinds.bindsKind() != (kind != null)) {
            throw new IllegalArgumentException("Claim " + kinds.queryName + (kinds.bindsKind()
                    ? " needs a job kind" : " takes no job kind"));
        }
        List<Object> params = new ArrayList<>(List.of(now, now));
        if (kind != null) {
            params.add(kind);
        }
        params.addAll(List.of(limit, replicaId, now));
        return params;
    }

    /**
     * One claim statement: a {@code FOR UPDATE SKIP LOCKED} select of due rows and the update that leases them.
     * Parameters: see {@link #claimParams}.
     */
    private static ListQuery claim(ClaimKinds kinds) {
        return new ListQuery(kinds.queryName,
                new Bound.Page("earliest due first on " + kinds.index + ", at most one poll batch"),
                "WITH due AS ("
                        + "SELECT tenant_id, id FROM pre_timer "
                        + "WHERE status IN ('DUE','CLAIMED') AND next_fire_at <= ? "
                        + "AND (status = 'DUE' OR locked_until <= ?) "
                        + "AND " + kinds.predicate + " "
                        + "ORDER BY next_fire_at, id LIMIT ? FOR UPDATE SKIP LOCKED) "
                        + "UPDATE pre_timer t SET status = 'CLAIMED', locked_by = ?, "
                        + "locked_until = ?::timestamptz + t.lease_duration, attempt = t.attempt + 1 "
                        + "FROM due WHERE t.tenant_id = due.tenant_id AND t.id = due.id "
                        + "RETURNING t.tenant_id, t.id, t.instance_id, t.definition_id, t.token_id, t.job_kind, t.node_id, "
                        + "t.status, t.next_fire_at, (EXTRACT(EPOCH FROM t.lease_duration) * 1000)::bigint AS lease_ms, "
                        + "t.locked_by, t.locked_until, t.attempt, t.max_attempts, t.payload::text AS payload");
    }
}
