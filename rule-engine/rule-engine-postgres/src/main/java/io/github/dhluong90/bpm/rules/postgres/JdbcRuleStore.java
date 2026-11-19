package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetSummary;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.VersionConflictException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.internal.json.CanonicalJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.HistoryRow;
import io.github.dhluong90.bpm.rules.internal.store.RuleStore;
import io.github.dhluong90.bpm.rules.internal.store.StoredVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
            v.approval_comment, v.created_by, v.created_at
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
                  AND e.status = 'PUBLISHED' AND e.effective_from <= :now
                ORDER BY e.effective_from DESC, e.version_num DESC LIMIT 1) AS published_version,
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
                  requested_by, requested_at, approved_by, approved_at, approval_comment, created_by, created_at)
                VALUES (:tenant, :id, :ruleSetId, :num, :status, :hitPolicy, :effectiveFrom,
                  CAST(:canonicalJson AS jsonb), :canonicalHash, :sourceFormat, :sourceDocument, :sourceHash, :lock,
                  :requestedBy, :requestedAt, :approvedBy, :approvedAt, :comment, :createdBy, :createdAt)
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
                .param("createdAt", Timestamp.from(v.createdAt()))
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
                  approved_at = :approvedAt, approval_comment = :comment
                WHERE tenant_id = :tenant AND id = :id AND lock_version = :expected
                """)
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

    @Override
    public Optional<StoredVersion> findEffective(String tenantId, String ruleSet, Instant at) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + """
                FROM rle_version v
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = :tenant AND s.name = :name AND v.status = 'PUBLISHED' AND v.effective_from <= :at
                ORDER BY v.effective_from DESC, v.version_num DESC
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
        jdbc.sql("""
                INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor,
                  reason, lock_version, occurred_at, canonical_hash, previous_canonical_hash)
                VALUES (:tenant, :id, :versionId, :action, :from, :to, :actor, :reason, :lock, :at, :hash, :previousHash)
                """)
                .param("tenant", row.tenantId()).param("id", row.id()).param("versionId", row.versionId())
                .param("action", row.action().name())
                .param("from", row.fromStatus() == null ? null : row.fromStatus().name())
                .param("to", row.toStatus().name()).param("actor", row.actor()).param("reason", row.reason())
                .param("lock", row.lockVersion()).param("at", Timestamp.from(row.occurredAt()))
                .param("hash", row.canonicalHash()).param("previousHash", row.previousCanonicalHash())
                .update();
    }

    @Override
    public List<HistoryRow> history(String tenantId, String versionId) {
        // Only the actions this release writes; the retirement actions arrive with their features.
        return jdbc.sql("""
                SELECT tenant_id, id, version_id, action, from_status, to_status, actor, reason, lock_version,
                  occurred_at, canonical_hash, previous_canonical_hash
                FROM rle_version_history
                WHERE tenant_id = :tenant AND version_id = :versionId
                  AND action IN ('CREATED','REQUESTED','APPROVED','REJECTED','DRAFT_UPDATED')
                ORDER BY occurred_at, id
                """)
                .param("tenant", tenantId).param("versionId", versionId)
                .query((rs, row) -> new HistoryRow(rs.getString("tenant_id"), rs.getString("id"),
                        rs.getString("version_id"), LifecycleAction.valueOf(rs.getString("action")),
                        rs.getString("from_status") == null ? null : VersionStatus.valueOf(rs.getString("from_status")),
                        VersionStatus.valueOf(rs.getString("to_status")), rs.getString("actor"), rs.getString("reason"),
                        rs.getLong("lock_version"), instant(rs, "occurred_at"), rs.getString("canonical_hash"),
                        rs.getString("previous_canonical_hash")))
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

    private static RuleSetSummary summary(ResultSet rs, int row) throws SQLException {
        int published = rs.getInt("published_version");
        Integer publishedVersion = rs.wasNull() ? null : published;
        return new RuleSetSummary(rs.getString("name"), rs.getInt("latest_version"),
                VersionStatus.valueOf(rs.getString("latest_status")), publishedVersion, instant(rs, "last_changed_at"));
    }

    private static StoredVersion version(ResultSet rs, int row) throws SQLException {
        return new StoredVersion(rs.getString("tenant_id"), rs.getString("id"), rs.getString("rule_set_id"),
                rs.getString("name"), rs.getInt("version_num"), VersionStatus.valueOf(rs.getString("status")),
                rs.getString("hit_policy"), instant(rs, "effective_from"), canonical(rs.getString("canonical_json")),
                rs.getString("canonical_hash"), SourceFormat.valueOf(rs.getString("source_format")),
                rs.getString("source_document"), rs.getString("source_hash"), rs.getLong("lock_version"),
                rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("approved_by"),
                instant(rs, "approved_at"), rs.getString("approval_comment"), rs.getString("created_by"),
                instant(rs, "created_at"));
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
