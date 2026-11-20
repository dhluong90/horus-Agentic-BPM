package io.github.dhluong90.bpm.process.internal.store;

import java.util.List;
import java.util.Optional;

/**
 * Read-only storage port of the query services. Every method takes the tenant and every query filters by it
 * first (TR-TEN-01). Methods join the caller's transaction. Implementations must be thread-safe.
 */
public interface ProcessQueryStore {

    /** Live tokens of one status at one node. */
    record LiveCount(String nodeId, String status, long count) {
    }

    /** @return the id of the definition version, when it exists in the tenant */
    Optional<String> definitionId(String tenant, String key, int version);

    /** @return live token counts per node and status, read through the live token index only */
    List<LiveCount> liveTokenCounts(String tenant, String definitionId);
}
