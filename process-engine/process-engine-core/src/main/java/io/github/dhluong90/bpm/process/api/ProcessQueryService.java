package io.github.dhluong90.bpm.process.api;

/**
 * Read models for supervisors and the cockpit. Safe for concurrent use. Reads only; never writes.
 */
public interface ProcessQueryService {

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
}
