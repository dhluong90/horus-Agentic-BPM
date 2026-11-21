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
     * Lists instances, newest first ({@code startedAt}, then {@code id}, both descending). Needs
     * {@code processes:read}.
     *
     * @param query the filters and the page
     * @return one page
     * @throws QueryInvalidException when the limit, the cursor, a status or the time range is not valid
     */
    Page<InstanceSummary> instances(InstanceQuery query);


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
