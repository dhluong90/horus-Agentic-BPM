package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.api.RuleSetExistsException;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.VersionConflictException;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.internal.json.CanonicalJson;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.json.Ulid;
import com.horus.bpm.rules.internal.store.RuleStore;
import com.horus.bpm.rules.internal.store.StoredVersion;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
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
        JsonNode rules = EngineValues.read(v.canonicalJson()).get("rules");
        int order = 0;
        for (JsonNode rule : rules) {
            jdbc.sql("""
                    INSERT INTO rle_rule (tenant_id, id, version_id, row_order, rule_id, when_expr_json, then_expr_json)
                    VALUES (:tenant, :id, :versionId, :order, :ruleId, CAST(:when AS jsonb), CAST(:then AS jsonb))
                    """)
                    .param("tenant", v.tenantId()).param("id", Ulid.next(v.createdAt())).param("versionId", v.id())
                    .param("order", order++).param("ruleId", rule.get("id").stringValue())
                    .param("when", CanonicalJson.write(rule.get("when")))
                    .param("then", CanonicalJson.write(rule.get("then")))
                    .update();
        }
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

    private static StoredVersion version(ResultSet rs, int row) throws SQLException {
        return new StoredVersion(rs.getString("tenant_id"), rs.getString("id"), rs.getString("rule_set_id"),
                rs.getString("name"), rs.getInt("version_num"), VersionStatus.valueOf(rs.getString("status")),
                rs.getString("hit_policy"), instant(rs, "effective_from"), rs.getString("canonical_json"),
                rs.getString("canonical_hash"), SourceFormat.valueOf(rs.getString("source_format")),
                rs.getString("source_document"), rs.getString("source_hash"), rs.getLong("lock_version"),
                rs.getString("requested_by"), instant(rs, "requested_at"), rs.getString("approved_by"),
                instant(rs, "approved_at"), rs.getString("approval_comment"), rs.getString("created_by"),
                instant(rs, "created_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
