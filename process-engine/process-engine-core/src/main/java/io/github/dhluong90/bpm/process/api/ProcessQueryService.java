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
}
