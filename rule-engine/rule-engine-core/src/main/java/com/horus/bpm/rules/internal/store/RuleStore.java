package com.horus.bpm.rules.internal.store;

import java.time.Instant;
import java.util.Optional;

/**
 * Storage of rule sets and versions. Every method is scoped to one tenant and joins the current
 * transaction. Implementations: PostgreSQL in {@code rule-engine-postgres}, in memory in tests.
 */
public interface RuleStore {

    /**
     * Finds a rule set id by name.
     *
     * @param tenantId the tenant
     * @param name the rule set name
     * @return the id, or empty
     */
    Optional<String> findRuleSetId(String tenantId, String name);

    /**
     * Creates a rule set.
     *
     * @param tenantId the tenant
     * @param id the new id
     * @param name the name
     * @param createdAt the creation time
     * @throws com.horus.bpm.rules.api.RuleSetExistsException when the name exists in the tenant
     */
    void createRuleSet(String tenantId, String id, String name, Instant createdAt);

    /**
     * Locks the rule set row for this transaction and returns the next version number.
     *
     * @param tenantId the tenant
     * @param ruleSetId the rule set id
     * @return the highest version number plus one, 1 for the first version
     */
    int lockAndNextVersionNumber(String tenantId, String ruleSetId);

    /**
     * Inserts a version.
     *
     * @param version the new version
     */
    void insertVersion(StoredVersion version);

    /**
     * Reads a version.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param version the version number
     * @param forUpdate whether to lock the row for this transaction
     * @return the version, or empty
     */
    Optional<StoredVersion> findVersion(String tenantId, String ruleSet, int version, boolean forUpdate);

    /**
     * Writes a status transition when the stored lock version still equals {@code expectedLockVersion}.
     *
     * @param updated the new state
     * @param expectedLockVersion the lock version the change was based on
     * @throws com.horus.bpm.rules.api.VersionConflictException when the row changed meanwhile
     */
    void updateVersion(StoredVersion updated, long expectedLockVersion);

    /**
     * Finds the published version effective at an instant: the latest {@code effectiveFrom} at or before it.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param at the evaluation instant
     * @return the version, or empty
     */
    Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at);
}
