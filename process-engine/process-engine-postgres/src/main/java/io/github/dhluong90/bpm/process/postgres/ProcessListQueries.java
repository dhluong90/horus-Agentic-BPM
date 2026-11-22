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
     * The live statuses, written exactly as in the predicates of {@code idx_pre_token_live} (V1) and
     * {@code idx_pre_token_live_node_instance} (V24), so the planner can answer the count with an index-only scan of
     * a partial index and never touches {@code pre_history}.
     */
    static final String LIVE_STATUSES = "('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT',"
            + "'WAITING_CHILD')";

    static final String DEFINITION_COLUMNS = "tenant_id, id, key_name, version_num, status, name, "
            + "definition_json::text AS definition_json, definition_hash, source_format, source_document, source_hash, "
            + "node_count, source, effective_from, created_by, created_at, approved_by, approved_at, lock_version, "
            + "requested_by, requested_at, decision_comment, retired_by, retired_at, superseded_by";

    static final String INSTANCE_COLUMNS = "tenant_id, id, definition_id, definition_key, definition_ver, "
            + "business_key, status, variables::text AS variables, seq, lock_version, started_by, started_at, ended_at";

    static final String TASK_COLUMNS = "tenant_id, id, instance_id, token_id, definition_id, node_id, name, "
            + "assignee_user, assignee_role, assignee_group, four_eyes, status, lock_version, claimed_by, claimed_at, "
            + "completed_by, created_at, completed_at, review::text AS review, due_at";

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

    /**
     * The active versions of every tenant, read once at start-up for the unclassified-field summary (PD-146), through
     * the partial index {@code idx_pre_definition_active} (V45).
     */
    static final ListQuery ACTIVE_DEFINITIONS = new ListQuery("active-definition-versions",
            new Bound.EngineSweep(null, "at most 1,000 active versions, over idx_pre_definition_active (V45)"),
            "SELECT " + DEFINITION_COLUMNS + " FROM pre_definition WHERE status = 'ACTIVE' "
                    + "ORDER BY tenant_id, key_name, version_num LIMIT ?");

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

    static final String INCIDENT_COLUMNS = "tenant_id, id, instance_id, definition_id, token_id, node_id, error_code, "
            + "cause_summary, retry_count, status, lock_version, created_at, resolved_at, resolution, resolved_by, "
            + "approval_mode, proposed_by, proposed_at, proposal_reason, proposal_output::text AS proposal_output, "
            + "retrying";

    /**
     * The unresolved incidents of one instance ({@code OPEN} and {@code RESOLUTION_PENDING}), oldest first (#58, #68),
     * on {@code idx_pre_incident_unresolved} (V30).
     */
    static final ListQuery OPEN_INCIDENTS = new ListQuery("instance-open-incidents",
            new Bound.ParentKey("instance_id", "the unresolved incidents of one instance, at most one per failed step"),
            "SELECT " + INCIDENT_COLUMNS + " FROM pre_incident "
                    + "WHERE tenant_id = ? AND instance_id = ? AND status IN ('OPEN','RESOLUTION_PENDING') "
                    + "ORDER BY created_at, id");

    /**
     * The first page of the definition versions waiting for a second person, oldest request first, of every key of one
     * tenant (#31, PRE-190), on {@code idx_pre_definition_pending_page (tenant_id, requested_at, id)} (V35).
     */
    static final ListQuery PENDING_APPROVALS = pendingApprovals(false);
    /** A later page, after the last version of the previous page. */
    static final ListQuery PENDING_APPROVALS_AFTER = pendingApprovals(true);

    /** The columns of a compensation stack entry (#67, V31). */
    static final String COMPENSATION_COLUMNS = "tenant_id, id, instance_id, scope_token_id, seq, step_id, forward_job_id, "
            + "forward_attempt_id, handler, mode, input_snapshot, status, job_id, pushed_at, completed_at";

    /**
     * The compensation stack of one instance (#67), by scope token and newest first, on
     * {@code uq_pre_compensation_stack_seq} (V31).
     */
    static final ListQuery COMPENSATION_STACK = new ListQuery("instance-compensation-stack",
            new Bound.ParentKey("instance_id", "the undo entries of one instance, one per completed step with an undo"),
            "SELECT " + COMPENSATION_COLUMNS + " FROM pre_compensation_stack WHERE tenant_id = ? AND instance_id = ? "
                    + "ORDER BY scope_token_id, seq DESC");

    /** The first page of the incidents of one status (#68, PRE-080). */
    static final ListQuery INCIDENTS = incidents(false);
    /** A later page, after the last incident of the previous page. */
    static final ListQuery INCIDENTS_AFTER = incidents(true);

    /** The recorded results of some steps of one instance (V14), by the primary key; parameters: tenant, instance, nodes. */
    static final ListQuery STEP_RESULTS = new ListQuery("instance-step-results",
            new Bound.ParentKey("instance_id", "one row per step of one instance, at most the nodes asked for"),
            "SELECT node_id, result_json::text AS result FROM pre_step_result WHERE tenant_id = ? AND instance_id = ? "
                    + "AND node_id = ANY (?)");

    /**
     * The reviewer overrides among the attempts of a page of recorded answers (#98), through
     * {@code idx_pre_history_instance}; parameters: tenant, instance ids, attempt ids.
     */
    static final ListQuery OVERRIDDEN_ATTEMPTS = new ListQuery("replay-overridden-attempts",
            new Bound.ParentKeys("instance_id", "the instances of one page of recorded answers, at most one page"),
            "SELECT details->>'refusedAttemptId' AS attempt_id FROM pre_history WHERE tenant_id = ? "
                    + "AND instance_id = ANY (?) AND event_type = 'HUMAN_OVERRIDE' "
                    + "AND details->>'refusedAttemptId' = ANY (?) AND EXISTS (SELECT 1 FROM jsonb_each(details->'changes') c "
                    + "WHERE c.value->>'from' IS DISTINCT FROM '***')");

    /** The first page of recorded answers of one agent step in a period (#98). */
    static final ListQuery RECORDED_ANSWERS = recordedAnswers(false);
    /** A later page of recorded answers, after the last one of the previous page. */
    static final ListQuery RECORDED_ANSWERS_AFTER = recordedAnswers(true);

    /** A page of the cases of one replay run (V17), on its primary key; parameters: tenant, run, after seq, limit. */
    static final ListQuery REPLAY_CASES = new ListQuery("replay-cases",
            new Bound.Page("seq order on the (tenant_id, replay_id, seq) primary key"),
            "SELECT seq, attempt_id, instance_id, recorded_at, recorded_outcome, candidate_outcome, change, "
                    + "recorded_rule_ids::text AS recorded_rule_ids, candidate_rule_ids::text AS candidate_rule_ids, "
                    + "code, overridden, masked_fields FROM pre_agent_replay_case "
                    + "WHERE tenant_id = ? AND replay_id = ? AND seq > ? ORDER BY seq LIMIT ?");

    /** Live tokens per step of one definition version, for a migration dry run (PRE-014). */
    static final ListQuery MIGRATION_LIVE_NODES = new ListQuery("migration-live-nodes",
            new Bound.ParentKey("definition_id", "one row per step of one definition version, at most its node count"),
            "SELECT current_node_id, count(*) AS live FROM pre_token "
                    + "WHERE tenant_id = ? AND definition_id = ? AND status IN " + LIVE_STATUSES + " "
                    + "GROUP BY current_node_id");

    /**
     * The instances with a live token at one step of a definition version, in id order, on
     * {@code idx_pre_token_live_node_instance} (V24). Parameters: tenant, definition id, step id, limit.
     */
    static final ListQuery MIGRATION_INSTANCES_AT_NODE = new ListQuery("migration-instances-at-node",
            new Bound.Page("keyset on instance_id of idx_pre_token_live_node_instance (V24)"),
            "SELECT DISTINCT instance_id FROM pre_token WHERE tenant_id = ? AND definition_id = ? "
                    + "AND current_node_id = ? AND status IN " + LIVE_STATUSES + " ORDER BY instance_id LIMIT ?");

    /**
     * The running instances of a definition version with no live token at any of the given steps, in id order, on
     * {@code idx_pre_instance_running_definition} (V25); each candidate is checked through
     * {@code idx_pre_token_instance}. Parameters: tenant, definition id, the steps as a text array, limit.
     */
    static final ListQuery MIGRATION_MOVABLE_INSTANCES = new ListQuery("migration-movable-instances",
            new Bound.Page("id order of idx_pre_instance_running_definition (V25)"),
            "SELECT i.id FROM pre_instance i WHERE i.tenant_id = ? AND i.definition_id = ? AND i.status = 'RUNNING' "
                    + "AND NOT EXISTS (SELECT 1 FROM pre_token t WHERE t.tenant_id = i.tenant_id "
                    + "AND t.instance_id = i.id AND t.status IN " + LIVE_STATUSES + " "
                    + "AND t.current_node_id = ANY (?::text[])) ORDER BY i.id LIMIT ?");

    /**
     * The children of one parent instance that have not ended, in id order, on {@code idx_pre_instance_parent} (V43).
     * A cancel of the parent reads them to cancel them (#92, PRE-213). Parameters: tenant, parent instance, limit.
     */
    static final ListQuery INSTANCE_CHILDREN = new ListQuery("instance-children",
            new Bound.ParentKey("parent_instance_id", "the calls of one instance, bounded by its definition's steps "
                    + "and the retries of each; read a page at a time"),
            "SELECT id FROM pre_instance WHERE tenant_id = ? AND parent_instance_id = ? "
                    + "AND status IN ('RUNNING','COMPENSATING') ORDER BY id LIMIT ?");

    /**
     * The instances with a remote call in flight at any of the given steps of a definition version, in id order, on
     * {@code idx_pre_timer_inflight} (V41). Parameters: tenant, definition id, the steps as a text array, limit (#106).
     */
    static final ListQuery MIGRATION_IN_FLIGHT_INSTANCES = new ListQuery("migration-in-flight-instances",
            new Bound.Page("instance id order of idx_pre_timer_inflight (V41), claimed jobs only"),
            "SELECT DISTINCT instance_id FROM pre_timer WHERE tenant_id = ? AND definition_id = ? "
                    + "AND node_id = ANY (?::text[]) AND status = 'CLAIMED' "
                    + "AND job_kind IN ('ASYNC_SERVICE','ASYNC_AGENT') ORDER BY instance_id LIMIT ?");

    /**
     * The next running instances of the source version of an approved migration, after the plan's cursor, in id order,
     * on {@code idx_pre_instance_running_definition} (V25). Parameters: tenant, definition id, cursor, limit (#90).
     */
    static final ListQuery MIGRATION_BATCH = new ListQuery("migration-batch",
            new Bound.Page("keyset on id of idx_pre_instance_running_definition (V25), one batch"),
            "SELECT id FROM pre_instance WHERE tenant_id = ? AND definition_id = ? AND status = 'RUNNING' "
                    + "AND id > ? ORDER BY id LIMIT ?");

    /** The live tokens of one instance that a migration moves, on {@code idx_pre_token_instance}. Parameters: tenant, instance. */
    static final ListQuery MIGRATION_INSTANCE_TOKENS = new ListQuery("migration-instance-tokens",
            new Bound.ParentKey("instance_id", "the live tokens of one instance, at most a few per node of its definition"),
            "SELECT id, current_node_id FROM pre_token WHERE tenant_id = ? AND instance_id = ? AND status IN "
                    + LIVE_STATUSES + " ORDER BY id");

    /** The per-instance outcomes of a migration after an instance id, on the primary key (V34). Parameters: tenant, migration, after, limit. */
    static final ListQuery MIGRATION_OUTCOMES = new ListQuery("migration-outcomes",
            new Bound.Page("keyset on instance_id of the primary key of pre_migration_instance (V34)"),
            "SELECT instance_id, outcome, code, detail, incident_id, occurred_at FROM pre_migration_instance "
                    + "WHERE tenant_id = ? AND migration_id = ? AND instance_id > ? ORDER BY instance_id LIMIT ?");

    /** As {@link #MIGRATION_OUTCOMES}, of one outcome. Parameters: tenant, migration, after, outcome, limit. */
    static final ListQuery MIGRATION_OUTCOMES_OF = new ListQuery("migration-outcomes-of",
            new Bound.Page("keyset on instance_id of the primary key of pre_migration_instance (V34)"),
            "SELECT instance_id, outcome, code, detail, incident_id, occurred_at FROM pre_migration_instance "
                    + "WHERE tenant_id = ? AND migration_id = ? AND instance_id > ? AND outcome = ? "
                    + "ORDER BY instance_id LIMIT ?");

    /**
     * The failed instances of a migration that have no incident yet, in id order, on the partial index
     * {@code idx_pre_migration_instance_failed} (V34). Parameters: tenant, migration, after, limit.
     */
    static final ListQuery MIGRATION_FAILURES_WITHOUT_INCIDENT = new ListQuery("migration-failures-without-incident",
            new Bound.Page("keyset on instance_id of idx_pre_migration_instance_failed (V34)"),
            "SELECT instance_id, outcome, code, detail, incident_id, occurred_at FROM pre_migration_instance "
                    + "WHERE tenant_id = ? AND migration_id = ? AND instance_id > ? AND outcome = 'FAILED' "
                    + "AND incident_id IS NULL ORDER BY instance_id LIMIT ?");

    /** The revisions of the layout of one definition version after a revision (#102). Parameters: tenant, definition, after, limit. */
    static final ListQuery LAYOUT_REVISIONS = new ListQuery("layout-revisions",
            new Bound.Page("keyset on revision of the primary key of pre_definition_layout (V33)"),
            "SELECT revision, layout_hash, definition_hash, source, created_by, created_at FROM "
                    + "pre_definition_layout WHERE tenant_id = ? AND definition_id = ? AND revision > ? "
                    + "ORDER BY revision LIMIT ?");

    /** The entry time of each token of one instance (#110), for the duration of a completed step. */
    static final ListQuery TOKEN_ENTERED_AT = new ListQuery("instance-token-entered-at",
            new Bound.ParentKey("instance_id", "the tokens of one instance, at most a few per node of its definition"),
            "SELECT id, entered_at FROM pre_token WHERE tenant_id = ? AND instance_id = ?");

    /**
     * One slice of the hourly step statistics rollup (#110), for every tenant: the events of the slice, each completed
     * step with the entry time of its token through {@code idx_pre_history_instance}. The range is at most
     * {@code stats.rollup-max-slice}; it is read through {@code idx_pre_history_time_brin} on every partition (V27)
     * or, on PostgreSQL 18, a skip scan of the primary key. Parameters: from, to.
     */
    static final ListQuery ROLLUP_SLICE = new ListQuery("rollup-slice",
            new Bound.EngineSweep("occurred_at", "one slice of at most stats.rollup-max-slice, by occurred_at"),
            "SELECT h.tenant_id, h.definition_id, h.node_id, h.event_type, "
                    + "h.occurred_at, CASE WHEN h.event_type = 'NODE_COMPLETED' THEN (SELECT max(e.occurred_at) "
                    + "FROM pre_history e WHERE e.tenant_id = h.tenant_id AND e.instance_id = h.instance_id "
                    + "AND e.occurred_at <= h.occurred_at AND e.event_type = 'NODE_ENTERED' AND e.token_id = h.token_id "
                    + "AND e.node_id = h.node_id) END AS entered_at "
                    + "FROM pre_history h WHERE h.occurred_at >= ? AND h.occurred_at < ? AND h.node_id IS NOT NULL "
                    + "AND h.event_type IN ('NODE_ENTERED','NODE_COMPLETED','NODE_FAILED','INCIDENT_RAISED','SLA_BREACHED')");

    /**
     * The step sums of one definition version in a window (#110), on the primary key
     * {@code (tenant_id, definition_id, bucket_start, node_id)} of {@code pre_node_stats_hourly}; the window is at
     * most {@code query.max-range}. Parameters: tenant, definition, from, to.
     */
    static final ListQuery WINDOW_NODE_SUMS = new ListQuery("window-node-sums",
            new Bound.ParentKey("definition_id", "one row per step of one definition version, a window of at most "
                    + "query.max-range"),
            "SELECT node_id, sum(entered_count) AS entered, sum(completed_count) AS "
                    + "completed, sum(failed_count) AS failed, sum(incident_count) AS incidents, sum(sla_breach_count) AS "
                    + "sla_breaches, sum(duration_sum_ms) AS duration_sum FROM pre_node_stats_hourly "
                    + "WHERE tenant_id = ? AND definition_id = ? AND bucket_start >= ? AND bucket_start < ? GROUP BY node_id");

    /** The duration histogram per step of one definition version in a window, on the same primary key. */
    static final ListQuery WINDOW_NODE_HISTOGRAM = new ListQuery("window-node-histogram",
            new Bound.ParentKey("definition_id", "one row per step and histogram bucket of one definition version, a "
                    + "window of at most query.max-range"),
            "SELECT s.node_id, u.k, sum(u.h)::bigint AS n "
                    + "FROM pre_node_stats_hourly s CROSS JOIN LATERAL unnest(s.duration_hist) WITH ORDINALITY AS u(h, k) "
                    + "WHERE s.tenant_id = ? AND s.definition_id = ? AND s.bucket_start >= ? AND s.bucket_start < ? "
                    + "GROUP BY s.node_id, u.k");

    /** The running instances of every tenant, for a gauge (#86): an index-only count of V25's partial index. */
    static final ListQuery GAUGE_ACTIVE_INSTANCES = new ListQuery("gauge-active-instances",
            new Bound.EngineSweep(null, "one count over idx_pre_instance_running_definition (V25), running rows only"),
            "SELECT count(*) FROM pre_instance WHERE status = 'RUNNING'");

    /** The open incidents of every tenant, for a gauge (#86): an index-only count of {@code idx_pre_incident_open}. */
    static final ListQuery GAUGE_OPEN_INCIDENTS = new ListQuery("gauge-open-incidents",
            new Bound.EngineSweep(null, "one count over idx_pre_incident_open, open rows only"),
            "SELECT count(*) FROM pre_incident WHERE status = 'OPEN'");

    /** The oldest due job of one kind, for a gauge (#86), on {@code idx_pre_timer_poll_kind}. Parameter: the kind. */
    static final ListQuery GAUGE_OLDEST_DUE_JOB = new ListQuery("gauge-oldest-due-job",
            new Bound.EngineSweep(null, "the first entry of idx_pre_timer_poll_kind for one kind, LIMIT 1"),
            "SELECT next_fire_at FROM pre_timer WHERE job_kind = ? AND status = 'DUE' ORDER BY next_fire_at, id LIMIT 1");

    /**
     * The tasks whose next escalation level is due, for every tenant (#73, PRE-178): at most the sweep's batch size,
     * oldest first, read through {@code idx_pre_task_escalation_due} (V32), whose predicate this one repeats exactly.
     * Parameters: now, limit.
     */
    static final ListQuery ESCALATIONS_DUE = new ListQuery("escalations-due",
            new Bound.EngineSweep(null, "the first sweep.batch-size entries of idx_pre_task_escalation_due (V32), "
                    + "oldest first"),
            "SELECT tenant_id, id, instance_id FROM pre_task WHERE status IN ('OPEN','CLAIMED') "
                    + "AND next_escalation_at IS NOT NULL AND next_escalation_at <= ? "
                    + "ORDER BY next_escalation_at, tenant_id, id LIMIT ?");

    /** The first page of the tenant's calendars: no holidays, a summary each (PRE-181). Parameters: tenant, limit. */
    static final ListQuery CALENDARS = calendars(false);
    /** A later page of calendars, after the last key of the previous page. */
    static final ListQuery CALENDARS_AFTER = calendars(true);
    /** The first page of calendar changes of one status, newest first (PRE-181). */
    static final ListQuery CALENDAR_CHANGES = calendarChanges(false);
    /** A later page of calendar changes, after the last change of the previous page. */
    static final ListQuery CALENDAR_CHANGES_AFTER = calendarChanges(true);

    /** The first page of definition keys. */
    static final ListQuery DEFINITION_KEYS = definitionKeys(false);
    /** A later page of definition keys, after the last key of the previous page. */
    static final ListQuery DEFINITION_KEYS_AFTER = definitionKeys(true);
    /** The distinct actors of one event type in the history of one instance, such as the makers of a step. */
    static final ListQuery HISTORY_ACTORS = new ListQuery("instance-history-actors",
            new Bound.ParentKey("instance_id", "the actors in the history of one instance, at most one per event"),
            "SELECT DISTINCT actor_id FROM pre_history WHERE tenant_id = ? AND instance_id = ? AND event_type = ? "
                    + "AND actor_id IS NOT NULL");

    /** The holidays of one calendar (#72), at most {@code WorkingCalendar.MAX_HOLIDAYS}. */
    static final ListQuery CALENDAR_HOLIDAYS = new ListQuery("calendar-holidays",
            new Bound.ParentKey("calendar_id", "the holidays of one calendar, at most 2,000"),
            "SELECT holiday_date, description FROM pre_holiday WHERE tenant_id = ? AND calendar_id = ? "
                    + "ORDER BY holiday_date");

    /** The columns of a task list item: the task and its instance's key, version and business key; no review. */
    static final String TASK_SUMMARY_COLUMNS = "t.tenant_id, t.id, t.instance_id, t.node_id, t.name, t.assignee_user, "
            + "t.assignee_role, t.assignee_group, t.status, t.lock_version, t.claimed_by, t.created_at, t.due_at, "
            + "i.definition_key, i.definition_ver, i.business_key";

    /**
     * One range of the task query (#70, PRE-030; DESIGN section 8.2): the index that serves it and the predicate of
     * that partial index, written exactly as in V22 so the planner can use it. The engine reads each range of a
     * caller (their user, their claims, each role, each group) separately and merges the pages.
     */
    enum TaskRange {
        /** Tasks declared to a user, open or claimed, on {@code idx_pre_task_mine}. */
        MINE("assignee_user", "t.status IN ('OPEN','CLAIMED') AND t.assignee_user IS NOT NULL", "idx_pre_task_mine"),
        /** Tasks a user claimed, on {@code idx_pre_task_claimed}. */
        CLAIMED("claimed_by", "t.status = 'CLAIMED'", "idx_pre_task_claimed"),
        /** Open tasks of one role, on {@code idx_pre_task_role_due}. */
        ROLE("assignee_role", "t.status = 'OPEN' AND t.assignee_role IS NOT NULL", "idx_pre_task_role_due"),
        /** Open tasks of one group, on {@code idx_pre_task_group}. */
        GROUP("assignee_group", "t.status = 'OPEN' AND t.assignee_group IS NOT NULL", "idx_pre_task_group"),
        /** Every open or claimed task of the tenant, on {@code idx_pre_task_open_due}. */
        ANY(null, "t.status IN ('OPEN','CLAIMED')", "idx_pre_task_open_due");

        private final String column;
        private final String predicate;
        private final String index;

        TaskRange(String column, String predicate, String index) {
            this.column = column;
            this.predicate = predicate;
            this.index = index;
        }

        /** @return whether the range takes a key: the user, role or group */
        boolean keyed() {
            return column != null;
        }
    }

    /**
     * The dated part of a range: tasks with a due time, by {@code (due_at, id)} after a key and before an upper
     * bound. Parameters: tenant, [key], statuses (text array), after due time, after id, due before, limit.
     */
    static ListQuery datedTasks(TaskRange range) {
        return new ListQuery("tasks-" + range.name().toLowerCase(java.util.Locale.ROOT) + "-dated",
                new Bound.Page("earliest due first on " + range.index),
                "SELECT " + TASK_SUMMARY_COLUMNS + " FROM pre_task t JOIN pre_instance i ON i.tenant_id = t.tenant_id "
                        + "AND i.id = t.instance_id WHERE t.tenant_id = ? "
                        + (range.keyed() ? "AND t." + range.column + " = ? " : "")
                        + "AND " + range.predicate + " AND t.status = ANY (?) "
                        + "AND (t.due_at, t.id) > (?, ?) AND t.due_at < ? ORDER BY t.due_at, t.id LIMIT ?");
    }

    /**
     * The undated part of a range: tasks without a due time, which follow the dated ones, by id. Ordered by
     * {@code (due_at, id)} like the index, all due times being null, so the plan reads the index in order and never
     * sorts. Parameters: tenant, [key], statuses (text array), after id, limit.
     */
    static ListQuery undatedTasks(TaskRange range) {
        return new ListQuery("tasks-" + range.name().toLowerCase(java.util.Locale.ROOT) + "-undated",
                new Bound.Page("tasks without a due time, by id, on " + range.index),
                "SELECT " + TASK_SUMMARY_COLUMNS + " FROM pre_task t JOIN pre_instance i ON i.tenant_id = t.tenant_id "
                        + "AND i.id = t.instance_id WHERE t.tenant_id = ? "
                        + (range.keyed() ? "AND t." + range.column + " = ? " : "")
                        + "AND " + range.predicate + " AND t.status = ANY (?) "
                        + "AND t.due_at IS NULL AND t.id > ? ORDER BY t.due_at, t.id LIMIT ?");
    }

    private static final java.util.Map<TaskRange, ListQuery> DATED = taskQueries(true);
    private static final java.util.Map<TaskRange, ListQuery> UNDATED = taskQueries(false);

    private static java.util.Map<TaskRange, ListQuery> taskQueries(boolean dated) {
        java.util.EnumMap<TaskRange, ListQuery> queries = new java.util.EnumMap<>(TaskRange.class);
        for (TaskRange range : TaskRange.values()) {
            queries.put(range, dated ? datedTasks(range) : undatedTasks(range));
        }
        return java.util.Collections.unmodifiableMap(queries);
    }

    /** @return the registered query of one part of a range */
    static ListQuery tasks(TaskRange range, boolean dated) {
        return (dated ? DATED : UNDATED).get(range);
    }

    /**
     * The history of one instance aggregated per step (PRE-027), on {@code idx_pre_history_instance}, between the
     * instance's start and end so the planner prunes every partition outside its lifetime. Parameters: tenant,
     * instance, from, to.
     */
    static final ListQuery NODE_ACTIVITIES = new ListQuery("instance-node-activities",
            new Bound.ParentKey("instance_id", "one row per step of one instance, within its lifetime"),
            "SELECT node_id, "
                    + "min(occurred_at) FILTER (WHERE event_type = 'NODE_ENTERED') AS first_entered_at, "
                    + "max(occurred_at) FILTER (WHERE event_type = 'NODE_ENTERED') AS last_entered_at, "
                    + "max(occurred_at) FILTER (WHERE event_type = 'NODE_COMPLETED') AS last_completed_at, "
                    + "count(*) FILTER (WHERE event_type = 'NODE_ENTERED') AS entries, "
                    + "count(DISTINCT attempt_id) AS attempt_ids, "
                    + "(array_agg(actor_id ORDER BY occurred_at DESC, id DESC) FILTER (WHERE actor_id IS NOT NULL))[1] "
                    + "AS last_actor FROM pre_history WHERE tenant_id = ? AND instance_id = ? "
                    + "AND occurred_at >= ? AND occurred_at <= ? AND node_id IS NOT NULL GROUP BY node_id");

    /**
     * The history entries of one instance oldest first (PRE-027), on {@code idx_pre_history_instance}, within its
     * lifetime. Parameters: tenant, instance, from, to, limit.
     */
    static final ListQuery HISTORY_ENTRIES = new ListQuery("instance-history-entries",
            new Bound.Page("oldest first on idx_pre_history_instance (tenant_id, instance_id, occurred_at, id)"),
            "SELECT id, event_type, node_id, actor_id, occurred_at, token_id, attempt_id, details FROM pre_history "
                    + "WHERE tenant_id = ? AND instance_id = ? AND occurred_at >= ? AND occurred_at <= ? "
                    + "ORDER BY occurred_at, id LIMIT ?");

    /**
     * The undo of one instance (#80, PRE-203): the history types a compensation writes, on the same index and within the
     * same lifetime bound as {@link #HISTORY_ENTRIES}, so the scopes of the timeline never depend on its entry limit.
     * Parameters: tenant, instance, from, to, limit.
     */
    static final ListQuery COMPENSATION_ENTRIES = new ListQuery("instance-compensation-entries",
            new Bound.Page("oldest first on idx_pre_history_instance (tenant_id, instance_id, occurred_at, id)"),
            "SELECT id, event_type, node_id, actor_id, occurred_at, token_id, attempt_id, details FROM pre_history "
                    + "WHERE tenant_id = ? AND instance_id = ? AND occurred_at >= ? AND occurred_at <= ? "
                    + "AND (event_type LIKE 'COMPENSATION\\_%' OR event_type = 'COMPENSATED') "
                    + "ORDER BY occurred_at, id LIMIT ?");

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
        List<ListQuery> all = new ArrayList<>(List.of(LIVE_COUNTS, LOCK_DEFINITIONS, ACTIVE_DEFINITIONS, DEFINITION_HISTORY, TOKENS, TASKS,
                OPEN_INCIDENTS, COMPENSATION_STACK, INCIDENTS, INCIDENTS_AFTER, PENDING_APPROVALS, PENDING_APPROVALS_AFTER, STEP_RESULTS, OVERRIDDEN_ATTEMPTS, RECORDED_ANSWERS,
                RECORDED_ANSWERS_AFTER, REPLAY_CASES, DEFINITION_KEYS, DEFINITION_KEYS_AFTER, HISTORY_ACTORS,
                NODE_ACTIVITIES, HISTORY_ENTRIES, COMPENSATION_ENTRIES,
                CLAIM_TIMERS, CLAIM_ASYNC, CLAIM_ASYNC_KIND,
                CALENDAR_HOLIDAYS, MIGRATION_LIVE_NODES, MIGRATION_INSTANCES_AT_NODE, MIGRATION_MOVABLE_INSTANCES, MIGRATION_IN_FLIGHT_INSTANCES,
                INSTANCE_CHILDREN,
                MIGRATION_BATCH, MIGRATION_INSTANCE_TOKENS, MIGRATION_OUTCOMES, MIGRATION_OUTCOMES_OF, MIGRATION_FAILURES_WITHOUT_INCIDENT, LAYOUT_REVISIONS,
                TOKEN_ENTERED_AT, ROLLUP_SLICE, WINDOW_NODE_SUMS, WINDOW_NODE_HISTOGRAM, GAUGE_ACTIVE_INSTANCES,
                GAUGE_OPEN_INCIDENTS, GAUGE_OLDEST_DUE_JOB, ESCALATIONS_DUE, CALENDARS, CALENDARS_AFTER, CALENDAR_CHANGES,
                CALENDAR_CHANGES_AFTER,
                instances(1, false, false, false, false),
                instances(3, true, true, true, true)));
        all.addAll(DATED.values());
        all.addAll(UNDATED.values());
        return List.copyOf(all);
    }

    /**
     * The conditions of the recorded answers of one agent step: the result rows of its jobs, which carry the answer's
     * output. Parameters: tenant, definition key, node, from, to.
     */
    static final String ANSWERS_WHERE = "FROM pre_history h "
            + "JOIN pre_definition d ON d.tenant_id = h.tenant_id AND d.id = h.definition_id "
            + "WHERE h.tenant_id = ? AND d.key_name = ? AND h.node_id = ? "
            + "AND h.event_type IN ('ASYNC_RESULT_ACCEPTED','ASYNC_RESULT_REFUSED') AND h.details->'outputFields' IS NOT NULL "
            + "AND h.occurred_at >= ? AND h.occurred_at < ? ";

    /**
     * The recorded answers of one agent step in a period, oldest first, by the {@code (tenant_id, occurred_at, id)}
     * primary key of {@code pre_history} with each answer's job; keyset on {@code (occurred_at, id)}. Parameters:
     * tenant, definition key, node, from, to, [after occurred at, after id], limit.
     */
    private static ListQuery recordedAnswers(boolean after) {
        return new ListQuery(after ? "replay-recorded-answers-after" : "replay-recorded-answers",
                new Bound.Page("oldest first on the (tenant_id, occurred_at, id) primary key of pre_history"),
                "SELECT h.occurred_at, h.id, h.instance_id, h.event_type, h.attempt_id, h.details::text AS details, "
                        + "j.payload::text AS payload " + ANSWERS_WHERE.replace("WHERE",
                                "LEFT JOIN pre_timer j ON j.tenant_id = h.tenant_id AND j.id = h.details->>'jobId' "
                                        + "AND j.job_kind = 'ASYNC_AGENT' WHERE")
                        + (after ? "AND (h.occurred_at, h.id) > (?, ?) " : "")
                        + "ORDER BY h.occurred_at, h.id LIMIT ?");
    }

    /**
     * The calendars of one tenant by key, on the {@code (tenant_id, calendar_key)} unique index of {@code
     * pre_calendar}; keyset on the key. Parameters: tenant, [after key], limit.
     */
    private static ListQuery calendars(boolean after) {
        return new ListQuery(after ? "calendars-after" : "calendars",
                new Bound.Page("keyset on calendar_key of the (tenant_id, calendar_key) unique index"),
                "SELECT id, calendar_key, timezone, lock_version, updated_by, approved_by, updated_at FROM pre_calendar "
                        + "WHERE tenant_id = ? " + (after ? "AND calendar_key > ? " : "")
                        + "ORDER BY calendar_key LIMIT ?");
    }

    /**
     * The calendar changes of one status, newest first on {@code idx_pre_calendar_change_status (tenant_id, status,
     * proposed_at DESC, id DESC)} (V32); keyset on {@code (proposed_at, id)}. Parameters: tenant, status,
     * [after proposed at, after id], limit.
     */
    private static ListQuery calendarChanges(boolean after) {
        return new ListQuery(after ? "calendar-changes-after" : "calendar-changes",
                new Bound.Page("newest first on idx_pre_calendar_change_status (tenant_id, status, proposed_at DESC, "
                        + "id DESC)"),
                "SELECT " + CALENDAR_CHANGE_COLUMNS + " FROM pre_calendar_change WHERE tenant_id = ? AND status = ? "
                        + (after ? "AND (proposed_at, id) < (?, ?) " : "")
                        + "ORDER BY proposed_at DESC, id DESC LIMIT ?");
    }

    /** The columns of a calendar change, as {@link JdbcCalendarStore} reads them. */
    static final String CALENDAR_CHANGE_COLUMNS = "tenant_id, id, calendar_key, status, approval, base_version, "
            + "previous_value::text AS previous_value, proposed_value::text AS proposed_value, reason, proposed_by, "
            + "proposed_at, decided_by, decided_at, decision_comment";

    /**
     * The incidents of one status, newest first on {@code idx_pre_incident_status (tenant_id, status, created_at DESC,
     * id DESC)} (V30); keyset on {@code (created_at, id)}, never {@code OFFSET}. Parameters: tenant, status,
     * [after created at, after id], limit.
     */
    private static ListQuery incidents(boolean after) {
        return new ListQuery(after ? "incidents-after" : "incidents",
                new Bound.Page("newest first on idx_pre_incident_status (tenant_id, status, created_at DESC, id DESC)"),
                "SELECT " + INCIDENT_COLUMNS + " FROM pre_incident WHERE tenant_id = ? AND status = ? "
                        + (after ? "AND (created_at, id) < (?, ?) " : "")
                        + "ORDER BY created_at DESC, id DESC LIMIT ?");
    }

    /**
     * The pending approvals, oldest request first; keyset on {@code (requested_at, id)}, never {@code OFFSET}. The
     * definition and source are not read. Parameters: tenant, [after requested at, after id], limit.
     */
    private static ListQuery pendingApprovals(boolean after) {
        return new ListQuery(after ? "pending-approvals-after" : "pending-approvals",
                new Bound.Page("oldest request first on idx_pre_definition_pending_page (tenant_id, requested_at, id)"),
                "SELECT id, key_name, name, version_num, definition_hash, lock_version, created_by, requested_by, "
                        + "requested_at FROM pre_definition WHERE tenant_id = ? AND status = 'PENDING_APPROVAL' "
                        + (after ? "AND (requested_at, id) > (?, ?) " : "")
                        + "ORDER BY requested_at, id LIMIT ?");
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
            return "('" + JobStore.ASYNC_SERVICE + "','" + JobStore.ASYNC_AGENT + "','" + JobStore.COMPENSATION + "')";
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
