package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.postgres.ListQuery.Bound;
import java.util.List;

/**
 * The registry of every multi-row read of the Rule Engine store ({@code RLE-085}). Each query declares its bound;
 * the query-plan check runs {@code EXPLAIN} for each entry of {@link #all()}. A query built from parts, such as a
 * decision log search, registers its smallest and its fullest form.
 *
 * <p>To add a list query: add it here with its bound, read it through {@link ListQuery#list}, and give the
 * query-plan check sample parameters for it. A multi-row read anywhere else fails the build.
 */
final class RuleListQueries {

    /** Columns of a version joined to its rule set {@code s}, as {@code StoredVersion} reads them. */
    static final String VERSION_COLUMNS = """
            v.tenant_id, v.id, v.rule_set_id, s.name, v.version_num, v.status, v.hit_policy, v.effective_from,
            v.canonical_json::text AS canonical_json, v.canonical_hash, v.source_format, v.source_document,
            v.source_hash, v.lock_version, v.requested_by, v.requested_at, v.approved_by, v.approved_at,
            v.approval_comment, v.created_by, v.created_at, v.retired_at, v.copied_from, v.retire_requested_by,
            v.retire_requested_at, v.retire_reason, v.retire_confirm_no_version
            """;

    /**
     * Summary columns of a rule set {@code s}. {@code published_version} follows effective-version selection
     * (DESIGN section 3.3.1, as {@code findEffective}); {@code last_changed_at} is the latest version timestamp
     * or history row, so a rejection (which clears {@code requested_at}) still counts as a change.
     */
    static final String SUMMARY_SELECT = """
            SELECT s.name, lv.version_num AS latest_version, lv.status AS latest_status,
              (SELECT e.version_num FROM rle_version e
                WHERE e.tenant_id = s.tenant_id AND e.rule_set_id = s.id
                  AND e.approved_at IS NOT NULL AND e.effective_from <= :now
                  AND (e.retired_at IS NULL OR e.retired_at > :now)
                ORDER BY e.version_num DESC LIMIT 1) AS published_version,
              GREATEST(
                (SELECT max(GREATEST(v.created_at, v.requested_at, v.approved_at)) FROM rle_version v
                  WHERE v.tenant_id = s.tenant_id AND v.rule_set_id = s.id),
                (SELECT max(h.occurred_at) FROM rle_version_history h
                  JOIN rle_version v ON v.tenant_id = h.tenant_id AND v.id = h.version_id
                  WHERE v.tenant_id = s.tenant_id AND v.rule_set_id = s.id)) AS last_changed_at
            FROM rle_rule_set s
            JOIN LATERAL (SELECT v.version_num, v.status FROM rle_version v
                           WHERE v.tenant_id = s.tenant_id AND v.rule_set_id = s.id AND v.status <> 'DISCARDED'
                           ORDER BY v.version_num DESC LIMIT 1) lv ON TRUE
            """;

    static final String HISTORY_VERSION_COLUMNS = """
            v.id, v.version_num, v.status, v.effective_from, v.retired_at, v.canonical_hash, v.created_by, v.created_at,
            v.requested_by, v.requested_at, v.approved_by, v.approved_at, v.approval_comment
            """;

    /** The decision columns, as {@code JdbcDecisionLog} reads a row. */
    static final String DECISION_COLUMNS = """
            tenant_id, id, created_at, rule_set_name, version_num, hit_policy, outcome, error_code,
            masked_inputs::text AS inputs, outputs::text AS outputs, matched_rule_ids::text AS matched, caller_id,
            correlation_id, eval_clock, duration_micros, pinned, on_behalf_of, function_versions
            """;

    /** The lifecycle actions a version's history shows; the others belong to the rule set. */
    private static final String VERSION_ACTIONS = "('CREATED','REQUESTED','APPROVED','REJECTED','WITHDRAWN',"
            + "'DRAFT_UPDATED','RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED','DISCARDED')";

    /** The epoch of each named rule set of one tenant, for a replica's cache check. */
    static final ListQuery CACHE_EPOCHS = new ListQuery("cache-epochs",
            new Bound.KeySet("name", "the rule sets one evaluation or batch names, on the unique (tenant_id, name) index"),
            "SELECT name, cache_epoch FROM rle_rule_set WHERE tenant_id = :tenant AND name IN (:names)");

    /** A page of rule sets by name, byte order, so the keyset and the order agree for any locale. */
    static final ListQuery RULE_SETS = new ListQuery("rule-sets",
            new Bound.Page("keyset on name, the unique (tenant_id, name) index"),
            SUMMARY_SELECT + """
                    WHERE s.tenant_id = :tenant
                      AND (CAST(:after AS TEXT) IS NULL OR s.name COLLATE "C" > CAST(:after AS TEXT) COLLATE "C")
                    ORDER BY s.name COLLATE "C"
                    LIMIT :limit
                    """);

    /** A page of one rule set's versions, newest first. */
    static final ListQuery VERSIONS = new ListQuery("versions",
            new Bound.Page("keyset on version_num, the unique (tenant_id, rule_set_id, version_num) index"),
            """
                    SELECT s.name, v.version_num, v.status, v.hit_policy, v.effective_from, v.lock_version,
                      v.canonical_hash, v.created_by, v.created_at, v.requested_by, v.requested_at, v.approved_by,
                      v.approved_at, v.retire_requested_by
                    FROM rle_version v
                    JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    WHERE v.tenant_id = :tenant AND v.rule_set_id = :ruleSetId AND v.status <> 'DISCARDED'
                      AND (CAST(:before AS INT) IS NULL OR v.version_num < CAST(:before AS INT))
                    ORDER BY v.version_num DESC
                    LIMIT :limit
                    """);

    /** The lifecycle actions of one version, oldest first. */
    static final ListQuery VERSION_HISTORY = new ListQuery("version-history",
            new Bound.ParentKey("version_id", "the lifecycle actions of one version, on idx_rle_version_history_version"),
            """
                    SELECT tenant_id, id, version_id, action, from_status, to_status, actor, reason, superseded_by,
                      approval_mode, no_version_in_effect, lock_version, occurred_at, canonical_hash,
                      previous_canonical_hash, acknowledged_warnings::text AS acknowledged,
                      masked_by_default_inputs::text AS masked_by_default, copied_from, retire_requested_by
                    FROM rle_version_history
                    WHERE tenant_id = :tenant AND version_id = :versionId
                    ORDER BY occurred_at, id
                    """);

    /** A page of a rule set's versions for the history view, newest first. */
    static final ListQuery HISTORY_VERSIONS = new ListQuery("history-versions",
            new Bound.Page("keyset on version_num, the unique (tenant_id, rule_set_id, version_num) index"),
            "SELECT " + HISTORY_VERSION_COLUMNS + """
                    FROM rle_version v
                    WHERE v.tenant_id = :tenant AND v.rule_set_id = :ruleSetId
                      AND (CAST(:before AS INT) IS NULL OR v.version_num < CAST(:before AS INT))
                    ORDER BY v.version_num DESC
                    LIMIT :limit
                    """);

    /** The named versions of one rule set: a page of them, or the two a comparison names. */
    static final ListQuery VERSIONS_BY_NUMBER = new ListQuery("versions-by-number",
            new Bound.ParentKey("rule_set_id", "the versions of one rule set, of which the caller names a page or the two of a comparison"),
            "SELECT " + HISTORY_VERSION_COLUMNS + """
                    FROM rle_version v
                    WHERE v.tenant_id = :tenant AND v.rule_set_id = :ruleSetId AND v.version_num IN (:numbers)
                    ORDER BY v.version_num
                    """);

    /**
     * The history of a page of versions in one statement; each version's rows come from
     * {@code idx_rle_version_history_version} in time order, and the window keeps the oldest {@code :perVersion}.
     */
    static final ListQuery HISTORY_OF_VERSIONS = new ListQuery("history-of-versions",
            new Bound.KeySet("version_id", "the versions of one page, at most :perVersion rows each"),
            """
                    SELECT tenant_id, id, version_id, action, from_status, to_status, actor, reason, superseded_by,
                      approval_mode, no_version_in_effect, lock_version, occurred_at, canonical_hash,
                      previous_canonical_hash, acknowledged, masked_by_default, copied_from, retire_requested_by
                    FROM (
                      SELECT h.*, h.acknowledged_warnings::text AS acknowledged,
                        h.masked_by_default_inputs::text AS masked_by_default,
                        row_number() OVER (PARTITION BY h.version_id ORDER BY h.occurred_at, h.id) AS n
                      FROM rle_version_history h
                      WHERE h.tenant_id = :tenant AND h.version_id IN (:ids)
                        AND h.action IN """ + VERSION_ACTIONS + """

                    ) ranked
                    WHERE n <= :perVersion
                    ORDER BY version_id, occurred_at, id
                    """);

    /**
     * A rule set's own history rows, newest first, through {@code idx_rle_version_history_rule_set} (V24): tenant and
     * rule set lead, the index holds rule set rows only and is ordered as the query.
     */
    static final ListQuery RULE_SET_EVENTS = new ListQuery("rule-set-events",
            new Bound.Page("newest first on idx_rle_version_history_rule_set (V24)"),
            """
                    SELECT tenant_id, id, rule_set_id, action, actor, occurred_at, evidence::text AS evidence
                    FROM rle_version_history
                    WHERE tenant_id = :tenant AND rule_set_id = :ruleSetId AND action = 'EVIDENCE_EXPORTED'
                    ORDER BY occurred_at DESC, id DESC
                    LIMIT :limit
                    """);

    /** The inputs each version of one rule set declares, newest first, at most {@code :limit} versions (RLE-071). */
    static final ListQuery DECLARED_INPUTS = new ListQuery("declared-inputs",
            new Bound.Page("newest first on the unique (tenant_id, rule_set_id, version_num) index"),
            """
                    SELECT (v.canonical_json -> 'inputs')::text AS inputs
                    FROM rle_version v
                    JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    WHERE v.tenant_id = :tenant AND s.tenant_id = :tenant AND s.name = :ruleSet
                    ORDER BY v.version_num DESC
                    LIMIT :limit
                    """);

    /** Published versions of one tenant that were approved with masked-by-default inputs, at most {@code :limit}. */
    static final ListQuery MASKED_BY_DEFAULT = new ListQuery("published-masked-by-default",
            new Bound.Page("rule set name then version on the unique (tenant_id, name) and version indexes"),
            """
                    SELECT s.name, v.version_num, a.masked_by_default_inputs::text AS inputs, count(*) OVER () AS total
                    FROM rle_version v
                    JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    JOIN LATERAL (
                      SELECT h.masked_by_default_inputs FROM rle_version_history h
                      WHERE h.tenant_id = v.tenant_id AND h.version_id = v.id AND h.action = 'APPROVED'
                      ORDER BY h.occurred_at DESC, h.id DESC LIMIT 1) a ON true
                    WHERE v.tenant_id = :tenant AND v.status = 'PUBLISHED'
                      AND a.masked_by_default_inputs IS NOT NULL AND jsonb_array_length(a.masked_by_default_inputs) > 0
                    ORDER BY s.name, v.version_num
                    LIMIT :limit
                    """);

    /** The sweep that retires published versions a later published version has replaced. */
    static final ListQuery RETIRE_SUPERSEDED = new ListQuery("retire-superseded",
            new Bound.Sweep("the published versions a newer published version replaced, retired in the same statement"),
            """
                    UPDATE rle_version old
                       SET status = 'RETIRED', retired_at = :now, lock_version = old.lock_version + 1,
                           retire_requested_by = NULL, retire_requested_at = NULL, retire_reason = NULL,
                           retire_confirm_no_version = NULL
                      FROM (SELECT o.tenant_id, o.id, s.name, max(n.version_num) AS superseded_by
                              FROM rle_version o
                              JOIN rle_version n ON n.tenant_id = o.tenant_id AND n.rule_set_id = o.rule_set_id
                              JOIN rle_rule_set s ON s.tenant_id = o.tenant_id AND s.id = o.rule_set_id
                             WHERE o.status = 'PUBLISHED' AND n.status = 'PUBLISHED'
                               AND n.version_num > o.version_num AND n.effective_from <= :now
                               AND (CAST(:tenant AS TEXT) IS NULL OR o.tenant_id = CAST(:tenant AS TEXT))
                               AND (CAST(:ruleSet AS TEXT) IS NULL OR o.rule_set_id = CAST(:ruleSet AS TEXT))
                             GROUP BY o.tenant_id, o.id, s.name) newer
                     WHERE old.tenant_id = newer.tenant_id AND old.id = newer.id AND old.status = 'PUBLISHED'
                    RETURNING old.tenant_id, old.id, newer.name, old.version_num, old.lock_version, newer.superseded_by
                    """);

    /** The tenants that hold a masking key check for one key id; start-up only. */
    static final ListQuery MASKING_KEY_SCOPES = new ListQuery("masking-key-scopes",
            new Bound.Sweep("one row per tenant and key id of rle_masking_key_check, read once at start-up"),
            "SELECT tenant_id FROM rle_masking_key_check WHERE key_id = :keyId");

    /** The inputs of every version not retired, for the start-up masking check (RLE-035). */
    static final ListQuery LIVE_VERSION_INPUTS = new ListQuery("live-version-inputs",
            new Bound.Sweep("the versions in service of every tenant, read once at start-up; retired versions are left out"),
            """
                    SELECT v.tenant_id, s.name, v.version_num, (v.canonical_json -> 'inputs')::text AS inputs
                    FROM rle_version v
                    JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                    WHERE v.status <> 'RETIRED'
                    ORDER BY v.tenant_id, s.name, v.version_num
                    """);

    /** The host functions one version was approved with, and their version labels (RLE-058). */
    static final ListQuery VERSION_FUNCTIONS = new ListQuery("version-functions",
            new Bound.ParentKey("version_id", "the functions one version calls, on the primary key of rle_version_function"),
            """
                    SELECT function_name, function_version
                    FROM rle_version_function
                    WHERE tenant_id = :tenant AND version_id = :versionId
                    ORDER BY function_name
                    """);

    /** The host functions every published version calls, for the start-up function check (RLE-058). */
    static final ListQuery PUBLISHED_FUNCTION_USES = new ListQuery("published-function-uses",
            new Bound.Sweep("the functions of the versions in service of every tenant, read once at start-up"),
            """
                    SELECT f.tenant_id, r.name, v.version_num, f.function_name, f.function_version
                    FROM rle_version_function f
                    JOIN rle_version v ON v.tenant_id = f.tenant_id AND v.id = f.version_id
                    JOIN rle_rule_set r ON r.tenant_id = v.tenant_id AND r.id = v.rule_set_id
                    WHERE v.status = 'PUBLISHED'
                    ORDER BY f.function_name, f.tenant_id, r.name, v.version_num
                    """);

    /** The monthly partitions attached to {@code rle_decision}, for retention (RLE-017); a maintenance sweep only. */
    static final ListQuery DECISION_PARTITIONS = new ListQuery("decision-partitions",
            new Bound.Sweep("the child tables of rle_decision in the catalog, one per month kept, read by the retention sweep"),
            """
                    SELECT child.relname FROM pg_inherits i
                    JOIN pg_class child ON child.oid = i.inhrelid
                    WHERE i.inhparent = 'rle_decision'::regclass
                    """);

    /** The value of one host setting last seen for each tenant (RLE-052); start-up only. */
    static final ListQuery SETTINGS_SEEN = new ListQuery("settings-seen",
            new Bound.Sweep("one row per tenant of rle_setting_seen for one setting, read once at start-up"),
            "SELECT tenant_id, value FROM rle_setting_seen WHERE setting = :setting");

    /** A decision log page of one rule set and period, newest first. */
    static final ListQuery DECISION_SEARCH = decisionSearch(false);
    /** The same page after a keyset position. */
    static final ListQuery DECISION_SEARCH_AFTER = decisionSearch(true);
    /** Decisions whose logged input is one of the given texts, newest first (the masked input search). */
    static final ListQuery DECISIONS_BY_LOGGED_INPUT = new ListQuery("decisions-by-logged-input",
            new Bound.PartitionedPage("newest first on idx_rle_decision_lookup within the period's partitions"),
            "SELECT " + DECISION_COLUMNS + """
                     FROM rle_decision
                    WHERE tenant_id = :tenant AND rule_set_name = :ruleSet
                      AND created_at >= :from AND created_at < :to
                      AND masked_inputs ->> CAST(:input AS text) IN (:texts)
                    ORDER BY created_at DESC, id DESC
                    LIMIT :limit
                    """);

    /**
     * The decisions of one rule set in a period, counted up to {@code :limit}: the subquery stops at the limit, so the
     * database never reads the period of a busy table in full (RLE-077). One row.
     */
    static final ListQuery DECISION_COUNT = new ListQuery("decision-count",
            new Bound.PartitionedPage("newest first on idx_rle_decision_lookup, stopping at the limit"),
            """
                    SELECT count(*) AS found FROM (
                      SELECT 1 FROM rle_decision
                       WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND created_at >= :from AND created_at < :to
                       ORDER BY created_at DESC
                       LIMIT :limit) bounded
                    """);

    private static final String KEYSET = "  AND (created_at, id) < (:afterAt, :afterId)\n";
    private static final String PAGE = "ORDER BY created_at DESC, id DESC\nLIMIT :limit\n";

    private RuleListQueries() {
    }

    /** Every registered query; a decision log search registers its smallest and its fullest form. */
    static List<ListQuery> all() {
        return List.of(CACHE_EPOCHS, RULE_SETS, VERSIONS, VERSION_HISTORY, HISTORY_VERSIONS, VERSIONS_BY_NUMBER,
                HISTORY_OF_VERSIONS, RULE_SET_EVENTS, DECLARED_INPUTS, MASKED_BY_DEFAULT, RETIRE_SUPERSEDED,
                MASKING_KEY_SCOPES, LIVE_VERSION_INPUTS, VERSION_FUNCTIONS, PUBLISHED_FUNCTION_USES, SETTINGS_SEEN, DECISION_PARTITIONS, DECISION_SEARCH, DECISION_SEARCH_AFTER,
                DECISIONS_BY_LOGGED_INPUT, DECISION_COUNT, byCorrelation(false, false), byCorrelation(true, true),
                byInput(false), byInput(true));
    }

    /**
     * The search by rule set and period (RLE-029): tenant and rule set first and the period on the partition key,
     * so PostgreSQL prunes every monthly partition outside the period and reads {@code idx_rle_decision_lookup} in
     * at most two. Parameters: tenant, ruleSet, from, to, [afterAt, afterId], limit.
     */
    static ListQuery decisionSearch(boolean after) {
        return new ListQuery(after ? "decision-search-after" : "decision-search",
                new Bound.PartitionedPage("newest first on idx_rle_decision_lookup within the period's partitions"),
                searchSql(after));
    }

    /** The search statement of {@link #decisionSearch}. */
    static String searchSql(boolean after) {
        return "SELECT " + DECISION_COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND created_at >= :from AND created_at < :to
                """ + (after ? KEYSET : "") + PAGE;
    }

    /**
     * The search by correlation id (RLE-070), led by tenant and correlation id with the period on the partition
     * key. Parameters: tenant, correlation, from, to, [ruleSet], [afterAt, afterId], limit.
     */
    static ListQuery byCorrelation(boolean ruleSet, boolean after) {
        String sql = "SELECT " + DECISION_COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND correlation_id = :correlation
                  AND created_at >= :from AND created_at < :to
                """ + (ruleSet ? "  AND rule_set_name = :ruleSet\n" : "") + (after ? KEYSET : "") + PAGE;
        return new ListQuery("decisions-by-correlation" + (ruleSet ? "-rule-set" : "") + (after ? "-after" : ""),
                new Bound.PartitionedPage("newest first on idx_rle_decision_correlation within the period's partitions"),
                sql);
    }

    /**
     * The search by a plain logged input (RLE-071): tenant and rule set first and the period on the partition key.
     * Parameters: tenant, ruleSet, input, value, from, to, [afterAt, afterId], limit.
     */
    static ListQuery byInput(boolean after) {
        String sql = "SELECT " + DECISION_COLUMNS + """
                 FROM rle_decision
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet
                  AND created_at >= :from AND created_at < :to
                  AND masked_inputs -> CAST(:input AS text) = CAST(:value AS jsonb)
                """ + (after ? KEYSET : "") + PAGE;
        return new ListQuery(after ? "decisions-by-input-after" : "decisions-by-input",
                new Bound.PartitionedPage("newest first on idx_rle_decision_lookup within the period's partitions"), sql);
    }
}
