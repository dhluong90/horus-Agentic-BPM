package io.github.dhluong90.bpm.rules.internal.store;

import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
     * @throws io.github.dhluong90.bpm.rules.api.RuleSetExistsException when the name exists in the tenant
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
     * @throws io.github.dhluong90.bpm.rules.api.VersionConflictException when the row changed meanwhile
     */
    void updateVersion(StoredVersion updated, long expectedLockVersion);

    /**
     * Replaces the document of a draft (RLE-047): the content columns and the version's rule rows, when the stored
     * row is still a {@code DRAFT} at {@code expectedLockVersion}.
     *
     * @param updated the new state, from {@link StoredVersion#replaced}
     * @param expectedLockVersion the lock version the change was based on
     * @throws io.github.dhluong90.bpm.rules.api.VersionConflictException when the row changed meanwhile
     */
    void replaceDraft(StoredVersion updated, long expectedLockVersion);

    /**
     * Finds the version in force at an instant (ADR-0025 decision 3, DESIGN section 3.3.1): the highest version number
     * among the approved versions ({@code approvedAt} set) whose {@code effectiveFrom} is at or before it and that were
     * not retired at it ({@code retiredAt} null or after it). The status is not read, so a newer version takes over at
     * its effective date whether or not the older one's status has changed yet, and a clock before a retirement still
     * selects the retired version.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param at the evaluation instant
     * @return the version, or empty
     */
    Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at);

    /**
     * Locks the rule set row for this transaction ({@code SELECT ... FOR UPDATE}), so two retirements of its last
     * versions run one after the other and the second sees that it is now the last (PD-101).
     *
     * @param tenantId the tenant
     * @param ruleSetId the rule set id
     */
    void lockRuleSet(String tenantId, String ruleSetId);

    /**
     * Finds the approved, not retired version that starts soonest after an instant, other than one version.
     *
     * @param tenantId the tenant
     * @param ruleSetId the rule set id
     * @param after the instant
     * @param excludingVersionId the version left out, usually the one being retired
     * @return the version, or empty when none is scheduled
     */
    Optional<StoredVersion> nextScheduled(String tenantId, String ruleSetId, Instant after, String excludingVersionId);

    /**
     * Retires every {@code PUBLISHED} version that a newer {@code PUBLISHED} version of its rule set, in effect at
     * {@code now}, has superseded (DESIGN section 3.3.2): one conditional {@code UPDATE ... WHERE status = 'PUBLISHED'
     * ... RETURNING}, so across replicas each version is returned exactly once, with no advisory lock. Sets status
     * {@code RETIRED}, {@code retiredAt = now} and the lock version plus one. The caller writes the history row and the
     * event for the returned rows only, in the same transaction.
     *
     * @param tenantId the tenant, or null for every tenant (the engine's sweep)
     * @param ruleSetId the rule set, or null for every rule set of the tenant; requires a tenant
     * @param now the engine clock
     * @return the retired versions, each with the version in force that superseded it
     */
    List<Superseded> retireSuperseded(String tenantId, String ruleSetId, Instant now);

    /**
     * A version retired because a newer one is in effect.
     *
     * @param tenantId the tenant
     * @param versionId the retired version's id
     * @param ruleSet the rule set name
     * @param version the retired version's number
     * @param lockVersion its lock version after the retirement
     * @param supersededBy the number of the newer version in force, the highest one in effect
     */
    record Superseded(String tenantId, String versionId, String ruleSet, int version, long lockVersion,
            int supersededBy) {
    }

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

    /**
     * Returns the version in force at an instant, selected as {@link #findEffective} does, together with what the
     * compiled cache needs to keep it: the rule set's {@code cache_epoch} and the window around the instant over which
     * that selection holds, read in one statement so they agree (ADR-0009). The window runs from the latest instant at
     * or before {@code at} to the earliest instant after it at which any approved version starts or is retired.
     *
     * @param tenantId the tenant
     * @param ruleSet the rule set name
     * @param at the evaluation instant
     * @return the effective version, or empty when none is effective
     */
    Optional<EffectiveVersion> findEffectiveForCache(String tenantId, String ruleSet, Instant at);

    /**
     * Increments the rule set's {@code cache_epoch} in the current transaction and notifies every replica after
     * commit (PostgreSQL {@code pg_notify('horus_rle_cache', ...)}).
     *
     * @param tenantId the tenant
     * @param ruleSetId the rule set id
     * @param ruleSet the rule set name, carried by the notification
     * @return the new epoch
     */
    long advanceCacheEpoch(String tenantId, String ruleSetId, String ruleSet);

    /**
     * Returns the current {@code cache_epoch} of rule sets of one tenant; a name the tenant does not have is absent.
     *
     * @param tenantId the tenant
     * @param ruleSets the rule set names, at most {@code cache.max-entries}
     * @return epoch by rule set name
     */
    java.util.Map<String, Long> cacheEpochs(String tenantId, java.util.Collection<String> ruleSets);

    /**
     * The version in force and its cache window.
     *
     * @param version the version
     * @param cacheEpoch the rule set's {@code cache_epoch}
     * @param validFrom the start of the window, inclusive
     * @param nextEffectiveFrom the end of the window, exclusive, or null when nothing is scheduled after it
     */
    record EffectiveVersion(StoredVersion version, long cacheEpoch, Instant validFrom, Instant nextEffectiveFrom) {
    }

    /**
     * Lists the declared inputs of every version that is not retired, in every tenant. Engine-internal, used once at
     * start-up outside any request; never exposed to a caller.
     *
     * @return the inputs, by tenant, rule set and version
     */
    List<VersionInputs> liveVersionInputs();

    /**
     * Reads the value of a setting each tenant last started with (RLE-052). Engine-internal, read once at start-up
     * outside any request.
     *
     * @param setting the setting name, for example {@code decision-log.mask-default}
     * @return the recorded value by tenant
     */
    Map<String, String> settingsSeen(String setting);

    /**
     * Records the value of a setting a tenant started with, replacing the previous one.
     *
     * @param tenantId the tenant
     * @param setting the setting name
     * @param value the value in force
     * @param at the start-up time
     */
    void recordSettingSeen(String tenantId, String setting, String value, Instant at);

    /**
     * Lists a tenant's {@code PUBLISHED} versions whose latest {@code APPROVED} history row has a non-empty
     * {@code masked_by_default_inputs} (PD-104). One read-only query, at start-up only, when the tenant's masking
     * default has been turned off.
     *
     * @param tenantId the tenant
     * @param limit the most versions to return
     * @return the first versions by rule set name and version number, and how many match in all
     */
    MaskedByDefaultVersions publishedMaskedByDefault(String tenantId, int limit);
}
