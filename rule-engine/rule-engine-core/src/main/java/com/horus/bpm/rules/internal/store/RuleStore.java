package com.horus.bpm.rules.internal.store;

import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.VersionSummary;
import java.time.Instant;
import java.util.List;
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
     * Replaces the document of a draft (RLE-047): the content columns and the version's rule rows, when the stored
     * row is still a {@code DRAFT} at {@code expectedLockVersion}.
     *
     * @param updated the new state, from {@link StoredVersion#replaced}
     * @param expectedLockVersion the lock version the change was based on
     * @throws com.horus.bpm.rules.api.VersionConflictException when the row changed meanwhile
     */
    void replaceDraft(StoredVersion updated, long expectedLockVersion);

    /**
     * Finds the published version effective at an instant: the latest {@code effectiveFrom} at or before it.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param at the evaluation instant
     * @return the version, or empty
     */
    Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at);

    /**
     * Lists rule set summaries by name, ascending.
     *
     * @param tenantId the tenant
     * @param afterName only names after this one, or null from the start
     * @param limit at most this many
     * @param now the instant {@code publishedVersion} is resolved at
     * @return the summaries
     */
    List<RuleSetSummary> listRuleSets(String tenantId, String afterName, int limit, Instant now);

    /**
     * Reads one rule set summary.
     *
     * @param tenantId the tenant
     * @param name the rule set name
     * @param now the instant {@code publishedVersion} is resolved at
     * @return the summary, or empty
     */
    Optional<RuleSetSummary> findRuleSetSummary(String tenantId, String name, Instant now);

    /**
     * Lists version summaries of a rule set by version number, descending.
     *
     * @param tenantId the tenant
     * @param ruleSetId the rule set id
     * @param beforeVersion only versions below this number, or null from the newest
     * @param limit at most this many
     * @return the summaries
     */
    List<VersionSummary> listVersions(String tenantId, String ruleSetId, Integer beforeVersion, int limit);

    /**
     * Appends one lifecycle history row.
     *
     * @param row the row
     */
    void appendHistory(HistoryRow row);

    /**
     * Reads the history of a version, oldest first.
     *
     * @param tenantId the tenant
     * @param versionId the version id
     * @return the rows
     */
    List<HistoryRow> history(String tenantId, String versionId);

    /**
     * Returns the engine key of a purpose, storing {@code candidate} first when none exists. Every replica gets the
     * same key: the first insert wins and later ones read it.
     *
     * @param name the purpose, for example {@code cursor-hmac}
     * @param candidate random bytes to store when no key exists yet
     * @param now the creation time
     * @return the stored key
     */
    byte[] engineKey(String name, byte[] candidate, Instant now);

    /**
     * Reads the engine key of a purpose without creating it.
     *
     * @param name the purpose
     * @return the key, or empty
     */
    Optional<byte[]> findEngineKey(String name);
}
