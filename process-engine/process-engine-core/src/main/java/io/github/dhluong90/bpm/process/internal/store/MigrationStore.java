package io.github.dhluong90.bpm.process.internal.store;

import io.github.dhluong90.bpm.process.api.MigrationPlan;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Storage of migration plans ({@code pre_migration}, {@code pre_migration_history}) and the reads of a dry run (#74,
 * PRE-014). Every method takes the tenant, which leads every key and index. The dry-run reads only read: nothing here
 * writes {@code pre_instance}, {@code pre_token} or {@code pre_history}.
 */
public interface MigrationStore {

    /**
     * A stored plan.
     *
     * @param tenantId the tenant
     * @param view the plan as the API shows it
     * @param sourceDefinitionId the definition row of the source version
     * @param targetDefinitionId the definition row of the target version
     */
    record StoredPlan(String tenantId, MigrationPlan view, String sourceDefinitionId, String targetDefinitionId) {
    }

    void insertPlan(StoredPlan plan);

    /** Appends one row to the plan's history. */
    void insertHistory(String tenant, String id, String migrationId, String action, String actorId,
            Map<String, Object> details, Instant occurredAt);

    Optional<StoredPlan> findPlan(String tenant, String migrationId);

    /** @return live tokens per step of one definition version, from {@code idx_pre_token_live} */
    Map<String, Long> liveTokensByNode(String tenant, String definitionId);

    /** @return running instances of one definition version */
    long runningInstances(String tenant, String definitionId);

    /** @return running instances of one definition version with a live token at any of the steps */
    long instancesAt(String tenant, String definitionId, Collection<String> nodeIds);

    /** @return up to {@code limit} instance ids with a live token at the step, in id order */
    List<String> instanceIdsAt(String tenant, String definitionId, String nodeId, int limit);

    /** @return up to {@code limit} running instance ids with no live token at any of the steps, in id order */
    List<String> movableInstanceIds(String tenant, String definitionId, Collection<String> blockedNodeIds, int limit);
}
