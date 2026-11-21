package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.LayoutRevision;
import java.util.List;
import java.util.Optional;

/**
 * Storage of diagram layout revisions ({@code pre_definition_layout}, #102). Append-only: the engine issues
 * {@code INSERT} only, and the table refuses anything else. Every method takes the tenant, which leads the key.
 */
public interface LayoutStore {

    /**
     * Appends a revision.
     *
     * @return false when a revision with this number exists, which means another save won the race
     */
    boolean insert(String tenant, String definitionId, LayoutRevision revision);

    /** @return the highest revision number of the version, 0 when none */
    int latestRevision(String tenant, String definitionId);

    /** @return the revision with its document */
    Optional<LayoutRevision> find(String tenant, String definitionId, int revision);

    /** @return up to {@code limit} revisions after {@code afterRevision}, ascending, without documents */
    List<LayoutRevision> list(String tenant, String definitionId, int afterRevision, int limit);
}
