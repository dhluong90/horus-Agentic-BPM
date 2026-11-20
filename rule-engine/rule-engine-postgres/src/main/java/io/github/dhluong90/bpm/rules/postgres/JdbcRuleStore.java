package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.AcknowledgedWarning;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
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

    private static final String VERSION_COLUMNS = """
            v.tenant_id, v.id, v.rule_set_id, s.name, v.version_num, v.status, v.hit_policy, v.effective_from,
            v.canonical_json::text AS canonical_json, v.canonical_hash, v.source_format, v.source_document,
            v.source_hash, v.lock_version, v.requested_by, v.requested_at, v.approved_by, v.approved_at,
            v.approval_comment, v.created_by, v.created_at, v.retired_at, v.copied_from
            """;

    /**
     * Summary columns of a rule set {@code s}. {@code published_version} follows effective-version selection
     * (DESIGN section 3.3.1, as {@link #findEffective}); {@code last_changed_at} is the latest version timestamp
     * or history row, so a rejection (which clears {@code requested_at}) still counts as a change.
     */
    private static final String SUMMARY_SELECT = """
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
                           WHERE v.tenant_id = s.tenant_id AND v.rule_set_id = s.id
                           ORDER BY v.version_num DESC LIMIT 1) lv ON TRUE
            """;

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
                  approved_at = :approvedAt, approval_comment = :comment, retired_at = :retiredAt
                WHERE tenant_id = :tenant AND id = :id AND lock_version = :expected
                """)
                .param("retiredAt", timestamp(v.retiredAt()))
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
                .query((rs, row) -> row).list();
        return epoch;
    }

    @Override
    public java.util.Map<String, Long> cacheEpochs(String tenantId, java.util.Collection<String> ruleSets) {
        java.util.Map<String, Long> epochs = new java.util.HashMap<>();
        if (ruleSets.isEmpty()) {
            return epochs;
        }
        jdbc.sql("SELECT name, cache_epoch FROM rle_rule_set WHERE tenant_id = :tenant AND name IN (:names)")
                .param("tenant", tenantId).param("names", List.copyOf(ruleSets))
                .query((rs, row) -> epochs.put(rs.getString("name"), rs.getLong("cache_epoch"))).list();
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
        return jdbc.sql(SUMMARY_SELECT + """
                WHERE s.tenant_id = :tenant
                  AND (CAST(:after AS TEXT) IS NULL OR s.name COLLATE "C" > CAST(:after AS TEXT) COLLATE "C")
                ORDER BY s.name COLLATE "C"
                LIMIT :limit
                """)
                .param("tenant", tenantId).param("after", afterName).param("limit", limit)
                .param("now", Timestamp.from(now))
                .query(JdbcRuleStore::summary).list();
    }

    @Override
    public Optional<RuleSetSummary> findRuleSetSummary(String tenantId, String name, Instant now) {
        return jdbc.sql(SUMMARY_SELECT + " WHERE s.tenant_id = :tenant AND s.name = :name")
                .param("tenant", tenantId).param("name", name).param("now", Timestamp.from(now))
                .query(JdbcRuleStore::summary).optional();
    }

    @Override
    public List<VersionSummary> listVersions(String tenantId, String ruleSetId, Integer beforeVersion, int limit) {
        return jdbc.sql("""
                SELECT s.name, v.version_num, v.status, v.hit_policy, v.effective_from, v.lock_version,
                  v.canonical_hash, v.created_by, v.created_at, v.requested_by, v.requested_at, v.approved_by,
                  v.approved_at
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND v.rule_set_id = :ruleSetId
                  AND (CAST(:before AS INT) IS NULL OR v.version_num < CAST(:before AS INT))
                ORDER BY v.version_num DESC
                LIMIT :limit
                """)
                .param("tenant", tenantId).param("ruleSetId", ruleSetId).param("before", beforeVersion)
                .param("limit", limit)
                .query((rs, row) -> new VersionSummary(rs.getString("name"), rs.getInt("version_num"),
                        VersionStatus.valueOf(rs.getString("status")), rs.getString("hit_policy"),
                        instant(rs, "effective_from"), rs.getLong("lock_version"), rs.getString("canonical_hash"),
                        rs.getString("created_by"), instant(rs, "created_at"), rs.getString("requested_by"),
                        instant(rs, "requested_at"), rs.getString("approved_by"), instant(rs, "approved_at")))
                .list();
    }

    @Override
    public void appendHistory(HistoryRow row) {
        boolean approved = row.action() == LifecycleAction.APPROVED;
        jdbc.sql("""
                INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor,
                  reason, superseded_by, approval_mode, no_version_in_effect, lock_version, occurred_at,
                  canonical_hash, previous_canonical_hash, acknowledged_warnings, masked_by_default_inputs,
                  copied_from)
                VALUES (:tenant, :id, :versionId, :action, :from, :to, :actor, :reason, :supersededBy, :mode, :none,
                  :lock, :at, :hash, :previousHash, CAST(:acknowledged AS jsonb), CAST(:maskedByDefault AS jsonb),
                  :copiedFrom)
                """)
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

    /** An acknowledgement as stored in {@code acknowledged_warnings} (V15): a JSON array of {@code { code, input }}. */
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
        // Only the actions this release writes; the four-eyes retirement actions arrive with RLE-038.
        return jdbc.sql("""
                SELECT tenant_id, id, version_id, action, from_status, to_status, actor, reason, superseded_by,
                  approval_mode, no_version_in_effect, lock_version, occurred_at, canonical_hash,
                  previous_canonical_hash, acknowledged_warnings::text AS acknowledged,
                  masked_by_default_inputs::text AS masked_by_default, copied_from
                FROM rle_version_history
                WHERE tenant_id = :tenant AND version_id = :versionId
                  AND action IN ('CREATED','REQUESTED','APPROVED','REJECTED','WITHDRAWN','DRAFT_UPDATED','RETIRED')
                ORDER BY occurred_at, id
                """)
                .param("tenant", tenantId).param("versionId", versionId)
                .query((rs, row) -> {
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
                            strings(rs.getString("masked_by_default")), copiedFrom);
                })
                .list();
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
        return jdbc.sql("""
                UPDATE rle_version old
                   SET status = 'RETIRED', retired_at = :now, lock_version = old.lock_version + 1
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
                """)
                .param("tenant", tenantId).param("ruleSet", ruleSetId).param("now", Timestamp.from(now))
                .query((rs, row) -> new Superseded(rs.getString("tenant_id"), rs.getString("id"), rs.getString("name"),
                        rs.getInt("version_num"), rs.getLong("lock_version"), rs.getInt("superseded_by")))
                .list();
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
        return new java.util.TreeSet<>(jdbc.sql("SELECT tenant_id FROM rle_masking_key_check WHERE key_id = :keyId")
                .param("keyId", keyId).query(String.class).list());
    }

    @Override
    public List<VersionInputs> liveVersionInputs() {
        // Engine-internal, across tenants on purpose: the start-up masking check (RLE-035) runs outside any request.
        return jdbc.sql("""
                SELECT v.tenant_id, s.name, v.version_num, (v.canonical_json -> 'inputs')::text AS inputs
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.status <> 'RETIRED'
                ORDER BY v.tenant_id, s.name, v.version_num
                """)
                .query((rs, row) -> new VersionInputs(rs.getString("tenant_id"), rs.getString("name"),
                        rs.getInt("version_num"), rs.getString("inputs")))
                .list();
    }

    @Override
    public List<String> declaredInputs(String tenantId, String ruleSet) {
        // Tenant first, then the rule set by its unique name; at most 1,000 versions, newest first (RLE-071).
        return jdbc.sql("""
                SELECT (v.canonical_json -> 'inputs')::text AS inputs
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND s.tenant_id = :tenant AND s.name = :ruleSet
                ORDER BY v.version_num DESC
                LIMIT 1000
                """)
                .param("tenant", tenantId).param("ruleSet", ruleSet)
                .query(String.class).list();
    }

    @Override
    public Map<String, String> settingsSeen(String setting) {
        // Engine-internal, across tenants on purpose: read once at start-up, outside any request (RLE-052).
        Map<String, String> seen = new java.util.TreeMap<>();
        jdbc.sql("SELECT tenant_id, value FROM rle_setting_seen WHERE setting = :setting")
                .param("setting", setting)
                .query((rs, row) -> seen.put(rs.getString("tenant_id"), rs.getString("value")))
                .list();
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
        List<io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions.Version> versions = jdbc.sql("""
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
                """)
                .param("tenant", tenantId).param("limit", limit)
                .query((rs, row) -> {
                    total[0] = rs.getInt("total");
                    return new io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions.Version(
                            rs.getString("name"), rs.getInt("version_num"), strings(rs.getString("inputs")));
                })
                .list();
        return new io.github.dhluong90.bpm.rules.internal.store.MaskedByDefaultVersions(versions, total[0]);
    }

    private static RuleSetSummary summary(ResultSet rs, int row) throws SQLException {
        int published = rs.getInt("published_version");
        Integer publishedVersion = rs.wasNull() ? null : published;
        return new RuleSetSummary(rs.getString("name"), rs.getInt("latest_version"),
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
                instant(rs, "created_at"), instant(rs, "retired_at"), copiedFrom);
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
