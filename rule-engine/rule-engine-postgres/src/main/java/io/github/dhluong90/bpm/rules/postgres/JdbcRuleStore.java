package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RetirementRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.HistoryVersion;
import io.github.dhluong90.bpm.rules.internal.store.RuleSetEventRow;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import io.github.dhluong90.bpm.rules.internal.store.VersionInputs;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@link RuleStore} on {@code rle_rule_set}, {@code rle_version} and {@code rle_rule}. Every statement
 * filters by {@code tenant_id} first (TR-TEN-01) and joins the current transaction of the host
 * {@code DataSource}.
 */
public final class JdbcRuleStore implements RuleStore {

    private static final String VERSION_COLUMNS = RuleListQueries.VERSION_COLUMNS;
    private static final String SUMMARY_SELECT = RuleListQueries.SUMMARY_SELECT;

    /** At most this many versions of one rule set are read for the inputs they declare (RLE-071). */
    private static final int DECLARED_INPUT_VERSIONS = 1000;

    private final JdbcClient jdbc;

    /**
     * Creates the store on the host's data source.
     *
     * @param dataSource the host's data source
     */
    public JdbcRuleStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Optional<String> findRuleSetId(String tenantId, String name) {
        return jdbc.sql("SELECT id FROM rle_rule_set WHERE tenant_id = :tenant AND name = :name")
                .param("tenant", tenantId).param("name", name).query(String.class).optional();
    }

    @Override
    public void createRuleSet(String tenantId, String id, String name, Instant createdAt) {
        // ON CONFLICT instead of a failing INSERT: a unique violation would abort the caller's PostgreSQL
        // transaction, and the refusal must leave the host's transaction usable (TR-EMB-04).
        int inserted = jdbc.sql("""
                INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES (:tenant, :id, :name, :at)
                ON CONFLICT (tenant_id, name) DO NOTHING
                """)
                .param("tenant", tenantId).param("id", id).param("name", name)
                .param("at", Timestamp.from(createdAt)).update();
        if (inserted == 0) {
            throw new RuleSetExistsException(name);
        }
    }

    @Override
    public int lockAndNextVersionNumber(String tenantId, String ruleSetId) {
        jdbc.sql("SELECT id FROM rle_rule_set WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                .param("tenant", tenantId).param("id", ruleSetId).query(String.class).single();
        return jdbc.sql("SELECT COALESCE(MAX(version_num), 0) + 1 FROM rle_version WHERE tenant_id = :tenant AND rule_set_id = :id")
                .param("tenant", tenantId).param("id", ruleSetId).query(Integer.class).single();
    }

    @Override
    public void insertVersion(StoredVersion v) {
        jdbc.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, effective_from,
                  canonical_json, canonical_hash, source_format, source_document, source_hash, lock_version,
                  requested_by, requested_at, approved_by, approved_at, approval_comment, created_by, created_at,
                  copied_from)
                VALUES (:tenant, :id, :ruleSetId, :num, :status, :hitPolicy, :effectiveFrom,
                  CAST(:canonicalJson AS jsonb), :canonicalHash, :sourceFormat, :sourceDocument, :sourceHash, :lock,
                  :requestedBy, :requestedAt, :approvedBy, :approvedAt, :comment, :createdBy, :createdAt,
                  :copiedFrom)
                """)
                .param("tenant", v.tenantId()).param("id", v.id()).param("ruleSetId", v.ruleSetId())
                .param("num", v.version()).param("status", v.status().name()).param("hitPolicy", v.hitPolicy())
                .param("effectiveFrom", timestamp(v.effectiveFrom()))
                .param("canonicalJson", v.canonicalJson()).param("canonicalHash", v.canonicalHash())
                .param("sourceFormat", v.sourceFormat().name()).param("sourceDocument", v.sourceDocument())
                .param("sourceHash", v.sourceHash()).param("lock", v.lockVersion())
                .param("requestedBy", v.requestedBy()).param("requestedAt", timestamp(v.requestedAt()))
                .param("approvedBy", v.approvedBy()).param("approvedAt", timestamp(v.approvedAt()))
                .param("comment", v.approvalComment()).param("createdBy", v.createdBy())
                .param("createdAt", Timestamp.from(v.createdAt())).param("copiedFrom", v.copiedFrom())
                .update();
        insertRules(v, v.createdAt());
    }

    /** One {@code rle_rule} row per rule of the version's canonical document, in document order. */
    private void insertRules(StoredVersion v, java.time.Instant idTime) {
        JsonNode rules = EngineValues.read(v.canonicalJson()).get("rules");
        int order = 0;
        for (JsonNode rule : rules) {
            jdbc.sql("""
                    INSERT INTO rle_rule (tenant_id, id, version_id, row_order, rule_id, when_expr_json, then_expr_json)
                    VALUES (:tenant, :id, :versionId, :order, :ruleId, CAST(:when AS jsonb), CAST(:then AS jsonb))
                    """)
                    .param("tenant", v.tenantId()).param("id", Ulid.next(idTime)).param("versionId", v.id())
                    .param("order", order++).param("ruleId", rule.get("id").stringValue())
                    .param("when", CanonicalJson.write(rule.get("when")))
                    .param("then", CanonicalJson.write(rule.get("then")))
                    .update();
        }
    }

    @Override
    public void replaceDraft(StoredVersion v, long expectedLockVersion) {
        // Guarded by the lock version and the status, so a concurrent replace, request or second writer on another
        // replica loses with VERSION_CONFLICT instead of overwriting (RLE-047).
        int updated = jdbc.sql("""
                UPDATE rle_version SET hit_policy = :hitPolicy, effective_from = :effectiveFrom,
                  canonical_json = CAST(:canonicalJson AS jsonb), canonical_hash = :canonicalHash,
                  source_format = :sourceFormat, source_document = :sourceDocument, source_hash = :sourceHash,
                  lock_version = :lock
                WHERE tenant_id = :tenant AND id = :id AND lock_version = :expected AND status = 'DRAFT'
                """)
                .param("hitPolicy", v.hitPolicy()).param("effectiveFrom", timestamp(v.effectiveFrom()))
                .param("canonicalJson", v.canonicalJson()).param("canonicalHash", v.canonicalHash())
                .param("sourceFormat", v.sourceFormat().name()).param("sourceDocument", v.sourceDocument())
                .param("sourceHash", v.sourceHash()).param("lock", v.lockVersion())
                .param("tenant", v.tenantId()).param("id", v.id()).param("expected", expectedLockVersion)
                .update();
        if (updated != 1) {
            throw new VersionConflictException(v.ruleSet() + " v" + v.version()
                    + " changed meanwhile; read it again and retry with its current ETag");
        }
        jdbc.sql("DELETE FROM rle_rule WHERE tenant_id = :tenant AND version_id = :versionId")
                .param("tenant", v.tenantId()).param("versionId", v.id()).update();
        insertRules(v, v.createdAt());
    }

    @Override
    public Optional<StoredVersion> findVersion(String tenantId, String ruleSet, int version, boolean forUpdate) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND s.name = :name AND v.version_num = :num
                """ + (forUpdate ? " FOR UPDATE OF v" : ""))
                .param("tenant", tenantId).param("name", ruleSet).param("num", version)
                .query(JdbcRuleStore::version).optional();
    }

    @Override
    public void updateVersion(StoredVersion v, long expectedLockVersion) {
        int updated = jdbc.sql("""
                UPDATE rle_version SET status = :status, effective_from = :effectiveFrom, lock_version = :lock,
                  requested_by = :requestedBy, requested_at = :requestedAt, approved_by = :approvedBy,
                  approved_at = :approvedAt, approval_comment = :comment, retired_at = :retiredAt,
                  retire_requested_by = :retireBy, retire_requested_at = :retireAt, retire_reason = :retireReason,
                  retire_confirm_no_version = :retireConfirm
                WHERE tenant_id = :tenant AND id = :id AND lock_version = :expected
                """)
                .param("retiredAt", timestamp(v.retiredAt()))
                .param("retireBy", v.retirementRequest() == null ? null : v.retirementRequest().requestedBy())
                .param("retireAt", v.retirementRequest() == null ? null : timestamp(v.retirementRequest().requestedAt()))
                .param("retireReason", v.retirementRequest() == null ? null : v.retirementRequest().reason())
                .param("retireConfirm", v.retirementRequest() == null ? null
                        : v.retirementRequest().confirmNoVersionInEffect())
                .param("status", v.status().name()).param("effectiveFrom", timestamp(v.effectiveFrom()))
                .param("lock", v.lockVersion()).param("requestedBy", v.requestedBy())
                .param("requestedAt", timestamp(v.requestedAt())).param("approvedBy", v.approvedBy())
                .param("approvedAt", timestamp(v.approvedAt())).param("comment", v.approvalComment())
                .param("tenant", v.tenantId()).param("id", v.id()).param("expected", expectedLockVersion)
                .update();
        if (updated != 1) {
            throw new VersionConflictException(v.ruleSet() + " v" + v.version() + " changed meanwhile; reload and retry");
        }
    }

    /** Channel of the cache notifications (ADR-0009); one per database, shared by every tenant. */
    static final String CACHE_CHANNEL = "horus_rle_cache";

    @Override
    public Optional<EffectiveVersion> findEffectiveForCache(String tenantId, String ruleSet, Instant at) {
        // The same selection as findEffective, with the epoch and the window read in the same statement. The window
        // is bounded by the nearest start or retirement of any approved version on each side of :at, so the
        // selection cannot change inside it. GREATEST and LEAST ignore a null argument.
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                , s.cache_epoch,
                  (SELECT max(GREATEST(CASE WHEN n.effective_from <= :at THEN n.effective_from END,
                                       CASE WHEN n.retired_at <= :at THEN n.retired_at END))
                     FROM rle_version n
                    WHERE n.tenant_id = v.tenant_id AND n.rule_set_id = v.rule_set_id
                      AND n.approved_at IS NOT NULL) AS valid_from,
                  (SELECT min(LEAST(CASE WHEN n.effective_from > :at THEN n.effective_from END,
                                    CASE WHEN n.retired_at > :at THEN n.retired_at END))
                     FROM rle_version n
                    WHERE n.tenant_id = v.tenant_id AND n.rule_set_id = v.rule_set_id
                      AND n.approved_at IS NOT NULL) AS next_effective_from
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND s.name = :name
                  AND v.approved_at IS NOT NULL AND v.effective_from <= :at
                  AND (v.retired_at IS NULL OR v.retired_at > :at)
                ORDER BY v.version_num DESC
                LIMIT 1
                """)
                .param("tenant", tenantId).param("name", ruleSet).param("at", Timestamp.from(at))
                .query((rs, row) -> new EffectiveVersion(version(rs, row), rs.getLong("cache_epoch"),
                        instant(rs, "valid_from"), instant(rs, "next_effective_from")))
                .optional();
    }

    @Override
    public long advanceCacheEpoch(String tenantId, String ruleSetId, String ruleSet) {
        long epoch = jdbc.sql("""
                UPDATE rle_rule_set SET cache_epoch = cache_epoch + 1
                WHERE tenant_id = :tenant AND id = :id
                RETURNING cache_epoch
                """).param("tenant", tenantId).param("id", ruleSetId).query(Long.class).single();
        // Delivered by PostgreSQL after commit, to every session listening on the channel; dropped on rollback.
        String payload = EngineValues.write(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                .put("tenantId", tenantId).put("ruleSet", ruleSet).put("epoch", epoch));
        jdbc.sql("SELECT pg_notify(:channel, :payload)").param("channel", CACHE_CHANNEL).param("payload", payload)
                .query((rs, row) -> row).single();
        return epoch;
    }

    @Override
    public java.util.Map<String, Long> cacheEpochs(String tenantId, java.util.Collection<String> ruleSets) {
        java.util.Map<String, Long> epochs = new java.util.HashMap<>();
        if (ruleSets.isEmpty()) {
            return epochs;
        }
        RuleListQueries.CACHE_EPOCHS.list(jdbc, ListQuery.params("tenant", tenantId, "names", List.copyOf(ruleSets)),
                (rs, row) -> epochs.put(rs.getString("name"), rs.getLong("cache_epoch")));
        return epochs;
    }

    @Override
    public Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND s.name = :name
                  AND v.approved_at IS NOT NULL AND v.effective_from <= :at
                  AND (v.retired_at IS NULL OR v.retired_at > :at)
                ORDER BY v.version_num DESC
                LIMIT 1
                """)
                .param("tenant", tenantId).param("name", ruleSet).param("at", Timestamp.from(at))
                .query(JdbcRuleStore::version).optional();
    }

    @Override
    public List<RuleSetSummary> listRuleSets(String tenantId, String afterName, int limit, Instant now) {
        // Byte order (COLLATE "C") so the keyset and the order agree for any locale of the host database.
        return RuleListQueries.RULE_SETS.list(jdbc, ListQuery.params("tenant", tenantId, "after", afterName,
                "limit", limit, "now", Timestamp.from(now)), JdbcRuleStore::summary);
    }

    @Override
    public Optional<RuleSetSummary> findRuleSetSummary(String tenantId, String name, Instant now) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE s.tenant_id = :tenant AND s.name = :name")
                .param("tenant", tenantId).param("name", name).param("now", Timestamp.from(now))
                .query(JdbcRuleStore::summary).optional();
    }

    @Override
    public List<VersionSummary> listVersions(String tenantId, String ruleSetId, Integer beforeVersion, int limit) {
        return RuleListQueries.VERSIONS.list(jdbc, ListQuery.params("tenant", tenantId, "ruleSetId", ruleSetId,
                "before", beforeVersion, "limit", limit),
                (rs, row) -> new VersionSummary(rs.getString("name"), rs.getInt("version_num"),
                VersionStatus.valueOf(rs.getString("status")), rs.getString("hit_policy"),
                instant(rs, "effective_from"), rs.getLong("lock_version"), rs.getString("canonical_hash"),
                rs.getString("created_by"), instant(rs, "created_at"), rs.getString("requested_by"),
                instant(rs, "requested_at"), rs.getString("approved_by"), instant(rs, "approved_at"),
                rs.getString("retire_requested_by")));
    }

    @Override
    public List<io.github.dhluong90.bpm.rules.internal.store.PendingVersion> listPendingApprovals(String tenantId,
            io.github.dhluong90.bpm.rules.internal.store.DecisionLog.Position after, int limit) {
        org.springframework.jdbc.core.RowMapper<io.github.dhluong90.bpm.rules.internal.store.PendingVersion> mapper = (rs, row) ->
                new io.github.dhluong90.bpm.rules.internal.store.PendingVersion(rs.getString("id"), rs.getString("name"),
                        rs.getInt("version_num"), rs.getString("canonical_hash"), rs.getLong("lock_version"),
                        rs.getString("created_by"), rs.getString("requested_by"), instant(rs, "requested_at"),
                        instant(rs, "effective_from"));
        if (after == null) {
            return RuleListQueries.PENDING_APPROVALS.list(jdbc,
                    ListQuery.params("tenant", tenantId, "limit", limit), mapper);
        }
        return RuleListQueries.PENDING_APPROVALS_AFTER.list(jdbc, ListQuery.params("tenant", tenantId,
                "afterAt", java.sql.Timestamp.from(after.createdAt()), "afterId", after.id(), "limit", limit), mapper);
    }

    @Override
    public void appendHistory(HistoryRow row) {
        boolean approved = row.action() == LifecycleAction.APPROVED;
        jdbc.sql("""
                INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor,
                  reason, superseded_by, approval_mode, no_version_in_effect, lock_version, occurred_at,
                  canonical_hash, previous_canonical_hash, acknowledged_warnings, masked_by_default_inputs,
                  copied_from, retire_requested_by)
                VALUES (:tenant, :id, :versionId, :action, :from, :to, :actor, :reason, :supersededBy, :mode, :none,
                  :lock, :at, :hash, :previousHash, CAST(:acknowledged AS jsonb), CAST(:maskedByDefault AS jsonb),
                  :copiedFrom, :retireRequestedBy)
                """)
                .param("retireRequestedBy", row.retireRequestedBy())
                .param("supersededBy", row.supersededBy()).param("mode", row.approvalMode())
                .param("none", row.noVersionInEffect())
                .param("tenant", row.tenantId()).param("id", row.id()).param("versionId", row.versionId())
                .param("action", row.action().name())
                .param("from", row.fromStatus() == null ? null : row.fromStatus().name())
                .param("to", row.toStatus().name()).param("actor", row.actor()).param("reason", row.reason())
                .param("lock", row.lockVersion()).param("at", Timestamp.from(row.occurredAt()))
                .param("hash", row.canonicalHash()).param("previousHash", row.previousCanonicalHash())
                .param("acknowledged", approved ? acknowledgedJson(row.acknowledgedWarnings()) : null)
                .param("maskedByDefault", approved ? stringsJson(row.maskedByDefaultInputs()) : null)
                .param("copiedFrom", row.copiedFrom())
                .update();
    }

    /** An acknowledgement as stored in {@code acknowledged_warnings}: a JSON array of {@code { code, input }}. */
    private static String acknowledgedJson(List<AcknowledgedWarning> warnings) {
        tools.jackson.databind.node.ArrayNode array = EngineJson.NODES.arrayNode();
        warnings.forEach(w -> array.addObject().put("code", w.code()).put("input", w.input()));
        return EngineValues.write(array);
    }

    private static String stringsJson(List<String> values) {
        tools.jackson.databind.node.ArrayNode array = EngineJson.NODES.arrayNode();
        values.forEach(array::add);
        return EngineValues.write(array);
    }

    private static List<AcknowledgedWarning> acknowledged(String json) {
        List<AcknowledgedWarning> warnings = new java.util.ArrayList<>();
        if (json != null) {
            for (tools.jackson.databind.JsonNode w : EngineValues.read(json)) {
                warnings.add(new AcknowledgedWarning(w.get("code").stringValue(), w.get("input").stringValue()));
            }
        }
        return warnings;
    }

    private static List<String> strings(String json) {
        List<String> values = new java.util.ArrayList<>();
        if (json != null) {
            EngineValues.read(json).forEach(v -> values.add(v.stringValue()));
        }
        return values;
    }

    @Override
    public List<HistoryRow> history(String tenantId, String versionId) {
        return RuleListQueries.VERSION_HISTORY.list(jdbc, ListQuery.params("tenant", tenantId, "versionId", versionId),
                JdbcRuleStore::historyRow);
    }

    /**
     * A rule set's own history rows, newest first, through {@code idx_rle_version_history_rule_set}: tenant and
     * rule set lead, the index holds rule set rows only and is ordered as the query.
     */
    static final String RULE_SET_EVENTS_SQL = RuleListQueries.RULE_SET_EVENTS.sql();


    @Override
    public List<HistoryVersion> historyVersions(String tenantId, String ruleSetId, Integer beforeVersion, int limit) {
        // Keyset on (tenant_id, rule_set_id, version_num), the unique index of V1.
        return RuleListQueries.HISTORY_VERSIONS.list(jdbc, ListQuery.params("tenant", tenantId,
                "ruleSetId", ruleSetId, "before", beforeVersion, "limit", limit), JdbcRuleStore::historyVersion);
    }

    @Override
    public List<HistoryVersion> versionsByNumber(String tenantId, String ruleSetId, java.util.Collection<Integer> numbers) {
        if (numbers.isEmpty()) {
            return List.of();
        }
        return RuleListQueries.VERSIONS_BY_NUMBER.list(jdbc, ListQuery.params("tenant", tenantId,
                "ruleSetId", ruleSetId, "numbers", List.copyOf(numbers)), JdbcRuleStore::historyVersion);
    }

    @Override
    public List<HistoryRow> historyOf(String tenantId, java.util.Collection<String> versionIds, int perVersionLimit) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        // One query for a page of versions; each version's rows come from idx_rle_version_history_version in
        // time order, and the window keeps the oldest perVersionLimit of them.
        return RuleListQueries.HISTORY_OF_VERSIONS.list(jdbc, ListQuery.params("tenant", tenantId,
                "ids", List.copyOf(versionIds), "perVersion", perVersionLimit), JdbcRuleStore::historyRow);
    }

    @Override
    public void appendRuleSetEvent(RuleSetEventRow row) {
        jdbc.sql("""
                INSERT INTO rle_version_history (tenant_id, id, version_id, rule_set_id, action, actor, occurred_at,
                  evidence)
                VALUES (:tenant, :id, NULL, :ruleSetId, :action, :actor, :at, CAST(:evidence AS jsonb))
                """)
                .param("tenant", row.tenantId()).param("id", row.id()).param("ruleSetId", row.ruleSetId())
                .param("action", row.action().name()).param("actor", row.actor())
                .param("at", Timestamp.from(row.occurredAt())).param("evidence", row.evidenceJson())
                .update();
    }

    @Override
    public List<RuleSetEventRow> ruleSetEvents(String tenantId, String ruleSetId, int limit) {
        return RuleListQueries.RULE_SET_EVENTS.list(jdbc, ListQuery.params("tenant", tenantId,
                "ruleSetId", ruleSetId, "limit", limit),
                (rs, n) -> new RuleSetEventRow(rs.getString("tenant_id"), rs.getString("id"),
                rs.getString("rule_set_id"), RuleSetHistory.Action.valueOf(rs.getString("action")),
                rs.getString("actor"), instant(rs, "occurred_at"), rs.getString("evidence")));
    }

    private static HistoryVersion historyVersion(ResultSet rs, int row) throws SQLException {
        return new HistoryVersion(rs.getString("id"), rs.getInt("version_num"),
                VersionStatus.valueOf(rs.getString("status")), instant(rs, "effective_from"), instant(rs, "retired_at"),
                rs.getString("canonical_hash"), rs.getString("created_by"), instant(rs, "created_at"),
                rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("approved_by"),
                instant(rs, "approved_at"), rs.getString("approval_comment"));
    }

    private static HistoryRow historyRow(ResultSet rs, int row) throws SQLException {
        int supersededBy = rs.getInt("superseded_by");
        Integer superseded = rs.wasNull() ? null : supersededBy;
        boolean none = rs.getBoolean("no_version_in_effect");
        Boolean noVersionInEffect = rs.wasNull() ? null : none;
        int copied = rs.getInt("copied_from");
        Integer copiedFrom = rs.wasNull() ? null : copied;
        return new HistoryRow(rs.getString("tenant_id"), rs.getString("id"), rs.getString("version_id"),
                LifecycleAction.valueOf(rs.getString("action")),
                rs.getString("from_status") == null ? null : VersionStatus.valueOf(rs.getString("from_status")),
                VersionStatus.valueOf(rs.getString("to_status")), rs.getString("actor"),
                rs.getString("reason"), rs.getLong("lock_version"), instant(rs, "occurred_at"), superseded,
                rs.getString("approval_mode"), noVersionInEffect, rs.getString("canonical_hash"),
                rs.getString("previous_canonical_hash"), acknowledged(rs.getString("acknowledged")),
                strings(rs.getString("masked_by_default")), copiedFrom, rs.getString("retire_requested_by"));
    }

    @Override
    public void lockRuleSet(String tenantId, String ruleSetId) {
        jdbc.sql("SELECT id FROM rle_rule_set WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                .param("tenant", tenantId).param("id", ruleSetId).query(String.class).single();
    }

    @Override
    public Optional<StoredVersion> nextScheduled(String tenantId, String ruleSetId, Instant after,
            String excludingVersionId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND v.rule_set_id = :ruleSetId AND v.id <> :excluded
                  AND v.approved_at IS NOT NULL AND v.retired_at IS NULL AND v.effective_from > :after
                ORDER BY v.effective_from, v.version_num DESC
                LIMIT 1
                """)
                .param("tenant", tenantId).param("ruleSetId", ruleSetId).param("excluded", excludingVersionId)
                .param("after", Timestamp.from(after))
                .query(JdbcRuleStore::version).optional();
    }

    /**
     * The conditional update of DESIGN section 3.3.2. The inner query picks, per published version, the highest newer
     * published version of its rule set in effect at {@code now}; the outer update re-checks {@code status =
     * 'PUBLISHED'} on the row it locks. Under READ COMMITTED a second replica's update waits for the first one's row
     * lock, re-reads the committed row, finds it {@code RETIRED} and returns nothing, so each version comes back from
     * exactly one call across replicas. No advisory lock and no session state (TR-OPS-05).
     */
    @Override
    public List<Superseded> retireSuperseded(String tenantId, String ruleSetId, Instant now) {
        if (ruleSetId != null && tenantId == null) {
            throw new IllegalArgumentException("a rule set needs its tenant");
        }
        return RuleListQueries.RETIRE_SUPERSEDED.list(jdbc, ListQuery.params("tenant", tenantId,
                "ruleSet", ruleSetId, "now", Timestamp.from(now)),
                (rs, row) -> new Superseded(rs.getString("tenant_id"), rs.getString("id"), rs.getString("name"),
                rs.getInt("version_num"), rs.getLong("lock_version"), rs.getInt("superseded_by")));
    }

    @Override
    public byte[] engineKey(String name, byte[] candidate, Instant now) {
        // ON CONFLICT so concurrent first uses on two replicas neither fail nor doom the caller's transaction.
        jdbc.sql("""
                INSERT INTO rle_engine_key (name, key_bytes, created_at) VALUES (:name, :key, :at)
                ON CONFLICT (name) DO NOTHING
                """)
                .param("name", name).param("key", candidate).param("at", Timestamp.from(now)).update();
        return jdbc.sql("SELECT key_bytes FROM rle_engine_key WHERE name = :name")
                .param("name", name).query(byte[].class).single();
    }

    @Override
    public Optional<byte[]> findEngineKey(String name) {
        return jdbc.sql("SELECT key_bytes FROM rle_engine_key WHERE name = :name")
                .param("name", name).query(byte[].class).optional();
    }

    @Override
    public String maskingKeyCheck(String scope, String keyId, String checkValue, Instant now) {
        // Engine-internal, across tenants on purpose: runs once at start-up outside any request. ON CONFLICT so
        // replicas starting together neither fail nor doom the transaction; the first insert wins.
        jdbc.sql("""
                INSERT INTO rle_masking_key_check (tenant_id, key_id, check_value, first_seen_at)
                VALUES (:scope, :keyId, :checkValue, :at)
                ON CONFLICT (tenant_id, key_id) DO NOTHING
                """)
                .param("scope", scope).param("keyId", keyId).param("checkValue", checkValue)
                .param("at", Timestamp.from(now)).update();
        return jdbc.sql("SELECT check_value FROM rle_masking_key_check WHERE tenant_id = :scope AND key_id = :keyId")
                .param("scope", scope).param("keyId", keyId).query(String.class).single();
    }

    @Override
    public java.util.Set<String> maskingKeyScopes(String keyId) {
        // Engine-internal, across tenants on purpose: the start-up key id check runs outside any request.
        return new java.util.TreeSet<>(RuleListQueries.MASKING_KEY_SCOPES.list(jdbc,
                ListQuery.params("keyId", keyId), (rs, row) -> rs.getString("tenant_id")));
    }

    @Override
    public List<VersionInputs> liveVersionInputs() {
        // Engine-internal, across tenants on purpose: the start-up masking check (RLE-035) runs outside any request.
        return RuleListQueries.LIVE_VERSION_INPUTS.list(jdbc, ListQuery.params(),
                (rs, row) -> new VersionInputs(rs.getString("tenant_id"), rs.getString("name"),
                rs.getInt("version_num"), rs.getString("inputs")));
    }

    @Override
    public List<VersionInputs> publishedVersionInputs() {
        // Engine-internal, across tenants on purpose: the start-up summary of unclassified inputs (PD-146).
        return RuleListQueries.PUBLISHED_VERSION_INPUTS.list(jdbc, ListQuery.params(),
                (rs, row) -> new VersionInputs(rs.getString("tenant_id"), rs.getString("name"),
                rs.getInt("version_num"), rs.getString("inputs")));
    }

    @Override
    public List<String> declaredInputs(String tenantId, String ruleSet) {
        // Tenant first, then the rule set by its unique name; at most 1,000 versions, newest first (RLE-071).
        return RuleListQueries.DECLARED_INPUTS.list(jdbc, ListQuery.params("tenant", tenantId, "ruleSet", ruleSet,
                "limit", DECLARED_INPUT_VERSIONS), (rs, row) -> rs.getString("inputs"));
    }

    @Override
    public Map<String, String> settingsSeen(String setting) {
        // Engine-internal, across tenants on purpose: read once at start-up, outside any request (RLE-052).
        Map<String, String> seen = new java.util.TreeMap<>();
        RuleListQueries.SETTINGS_SEEN.list(jdbc, ListQuery.params("setting", setting),
                (rs, row) -> seen.put(rs.getString("tenant_id"), rs.getString("value")));
        return seen;
    }

    @Override
    public void recordSettingSeen(String tenantId, String setting, String value, Instant at) {
        // ON CONFLICT so replicas that start together neither fail nor block one another.
        jdbc.sql("""
                INSERT INTO rle_setting_seen (tenant_id, setting, value, recorded_at)
                VALUES (:tenant, :setting, :value, :at)
                ON CONFLICT (tenant_id, setting) DO UPDATE SET value = EXCLUDED.value, recorded_at = EXCLUDED.recorded_at
                """)
                .param("tenant", tenantId).param("setting", setting).param("value", value)
                .param("at", Timestamp.from(at)).update();
    }

    @Override
    public io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions publishedMaskedByDefault(
            String tenantId, int limit) {
        int[] total = {0};
        List<io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions.Version> versions =
                RuleListQueries.MASKED_BY_DEFAULT.list(jdbc, ListQuery.params("tenant", tenantId, "limit", limit),
                (rs, row) -> {
                total[0] = rs.getInt("total");
                return new io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions.Version(
                        rs.getString("name"), rs.getInt("version_num"), strings(rs.getString("inputs")));
                });
        return new io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions(versions, total[0]);
    }

    private static RuleSetSummary summary(ResultSet rs, int row) throws SQLException {
        int published = rs.getInt("published_version");
        Integer publishedVersion = rs.wasNull() ? null : published;
        return new RuleSetSummary(rs.getString("name"), rs.getString("display_name"), rs.getInt("latest_version"),
                VersionStatus.valueOf(rs.getString("latest_status")), publishedVersion, instant(rs, "last_changed_at"));
    }

    private static StoredVersion version(ResultSet rs, int row) throws SQLException {
        int copied = rs.getInt("copied_from");
        Integer copiedFrom = rs.wasNull() ? null : copied;
        return new StoredVersion(rs.getString("tenant_id"), rs.getString("id"), rs.getString("rule_set_id"),
                rs.getString("name"), rs.getInt("version_num"), VersionStatus.valueOf(rs.getString("status")),
                rs.getString("hit_policy"), instant(rs, "effective_from"), canonical(rs.getString("canonical_json")),
                rs.getString("canonical_hash"), SourceFormat.valueOf(rs.getString("source_format")),
                rs.getString("source_document"), rs.getString("source_hash"), rs.getLong("lock_version"),
                rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("approved_by"),
                instant(rs, "approved_at"), rs.getString("approval_comment"), rs.getString("created_by"),
                instant(rs, "created_at"), instant(rs, "retired_at"), copiedFrom,
                retirementRequest(rs));
    }

    /** The pending four-eyes retirement of a version row, or null; the columns are set together. */
    private static RetirementRequest retirementRequest(ResultSet rs) throws SQLException {
        String requestedBy = rs.getString("retire_requested_by");
        if (requestedBy == null) {
            return null;
        }
        return new RetirementRequest(requestedBy, instant(rs, "retire_requested_at"), rs.getString("retire_reason"),
                rs.getBoolean("retire_confirm_no_version"));
    }

    /**
     * JSONB keeps the value but not the text: it reorders keys and drops whitespace. The RFC 8785 form is a function
     * of the value, so writing it again gives back exactly the canonical document that was stored, and a version
     * read from the database equals the one returned at import or by an idempotent replay.
     */
    private static String canonical(String jsonb) {
        return CanonicalJson.write(EngineValues.read(jsonb));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
