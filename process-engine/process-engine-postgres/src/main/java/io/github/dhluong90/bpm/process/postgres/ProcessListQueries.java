package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.postgres.ListQuery.Bound;
import java.util.List;

/**
 * The registry of the multi-row reads of the Process Engine store ({@code PRE-036}). Each query declares its bound;
 * the query-plan check runs {@code EXPLAIN} for each entry of {@link #all()} on a seeded database.
 *
 * <p>To add a list query: add it here with its bound, read it through {@link ListQuery#list}, and give the
 * query-plan check sample parameters for it.
 */
final class ProcessListQueries {

    /**
     * The live statuses, written exactly as in the predicates of {@code idx_pre_token_live} (V1) and
     * {@code idx_pre_token_live_node_instance} (V24), so the planner can use those partial indexes.
     */
    static final String LIVE_STATUSES = "('ACTIVE','WAITING_USER','WAITING_ASYNC','WAITING_TIMER','WAITING_EVENT')";

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

    private ProcessListQueries() {
    }

    /** @return every registered query */
    static List<ListQuery> all() {
        return List.of(MIGRATION_LIVE_NODES, MIGRATION_INSTANCES_AT_NODE, MIGRATION_MOVABLE_INSTANCES);
    }
}
