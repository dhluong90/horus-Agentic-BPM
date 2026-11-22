package io.github.dhluong90.bpm.process.api;

/**
 * Read models for supervisors and the cockpit: lists of definitions and instances of the caller's tenant, and live
 * token counts per node. Every list is a keyset page with a cursor signed for the
 * tenant and the list, at most {@code query.max-page-size} items. Safe for concurrent use. Reads only.
 */
public interface ProcessQueryService {

    /**
     * Lists the definition keys, ordered by key. Needs {@code processes:read}.
     *
     * @param page which page
     * @return one page
     * @throws QueryInvalidException when the limit or the cursor is not valid
     */
    Page<DefinitionSummary> definitions(PageRequest page);

    /**
     * Lists the definition versions of the caller's tenant that wait for a second person, oldest request first,
     * whatever their number: a pending version that is not the latest of its key is included (#31, PRE-190). Each
     * item carries the {@code definitionHash} a reviewer sends back with the decision and the actions the caller may
     * take now. Needs {@code processes:read}. Read only.
     *
     * @param page which page
     * @return one page; its cursor is signed and bound to the tenant and this list
     * @throws QueryInvalidException when the limit or the cursor is not valid
     * @throws PermissionDeniedException without {@code processes:read}
     */
    default Page<PendingApproval> pendingApprovals(PageRequest page) {
        throw new UnsupportedOperationException("This query service does not list pending approvals");
    }

    /**
     * Lists instances, newest first ({@code startedAt}, then {@code id}, both descending). Needs
     * {@code processes:read}.
     *
     * @param query the filters and the page
     * @return one page
     * @throws QueryInvalidException when the limit, the cursor, a status or the time range is not valid
     */
    Page<InstanceSummary> instances(InstanceQuery query);

    /**
     * Lists tasks of the caller's tenant ordered by {@code dueAt}, then {@code id}; tasks without a due time follow,
     * by {@code id} (PRE-030). Each range (the caller's user, their claims, each role, each group, or the tenant for
     * {@code any}) is read through its own index and stops after one page. Needs {@code processes:task:work};
     * {@code any} also needs {@code processes:task:manage}.
     *
     * @param query the filters and the page
     * @return one page
     * @throws QueryInvalidException when the limit, the cursor, an assignment, a status or a filter is not valid
     * @throws PermissionDeniedException when the caller lacks a permission
     */
    default Page<TaskSummary> tasks(TaskQuery query) {
        throw new UnsupportedOperationException("This query service does not list tasks");
    }


    /**
     * Counts the live tokens per node of one definition version in the caller's tenant, from the live token index,
     * never from history. Needs {@code processes:read}. An answer is cached per replica for
     * {@code stats.live-cache-ttl} ({@code stats.live-cache-ttl-large} when the last count exceeded
     * {@code stats.live-cache-large-threshold}); a cached answer keeps its {@code asOf}.
     *
     * @param definitionKey the process key
     * @param version the version number
     * @return the counts
     * @throws DefinitionNotFoundException when the version does not exist in the caller's tenant
     */
    NodeCounts liveNodeCounts(String definitionKey, int version);

    /**
     * Reads the hourly rollup of one definition version in the caller's tenant: counts and typical durations per node
     * over whole hours, with the rollup watermark and its lag. Needs {@code processes:read}. The range is widened to
     * whole hours and may be at most {@code query.max-range}. Never reads history.
     *
     * @param definitionKey the process key
     * @param version the version number
     * @param from start of the window, or null for 24 hours before {@code to}
     * @param to end of the window, or null for now
     * @return the figures
     * @throws DefinitionNotFoundException when the version does not exist in the caller's tenant
     * @throws QueryInvalidException when {@code from} is not before {@code to}, or the range is longer than
     *     {@code query.max-range}
     */
    NodeWindowStats nodeWindowStats(String definitionKey, int version, java.time.Instant from, java.time.Instant to);

    /**
     * Reads what happened at each step of one instance of the caller's tenant (PRE-027): per step when it was
     * entered and left, how long it took, how many attempts, who acted last and any open incident, and the history
     * entries in time order. History is read only between the instance's start and its end, so PostgreSQL scans only
     * the partitions the instance lived in. No variable or input value is returned. Needs {@code processes:read}.
     *
     * @param instanceId the instance id
     * @param limit the most history entries to return, 1 to {@code query.max-page-size}
     * @return the activities
     * @throws InstanceNotFoundException when no such instance exists in the tenant, also when another tenant has it
     * @throws QueryInvalidException when the limit is out of range
     */
    InstanceActivities activities(String instanceId, int limit);
}
