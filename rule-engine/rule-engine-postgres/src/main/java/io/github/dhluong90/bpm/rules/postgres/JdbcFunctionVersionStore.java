package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code rle_version_function} (V26): the host function versions each rule set version was approved with, written in
 * the approval's transaction. Every statement names its tenant (TR-TEN-01), except the start-up read of
 * {@link #publishedUses()}, which spans tenants because functions belong to the host.
 */
public final class JdbcFunctionVersionStore implements FunctionVersionStore {

    private final JdbcClient jdbc;

    /**
     * Creates the store.
     *
     * @param dataSource the host's data source
     */
    public JdbcFunctionVersionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void record(String tenantId, String ruleSet, int version, Map<String, String> functionVersions, Instant at) {
        jdbc.sql("""
                DELETE FROM rle_version_function f
                USING rle_version v, rle_rule_set r
                WHERE f.tenant_id = :tenant AND v.tenant_id = f.tenant_id AND v.id = f.version_id
                  AND r.tenant_id = v.tenant_id AND r.id = v.rule_set_id AND r.name = :ruleSet
                  AND v.version_num = :version
                """).param("tenant", tenantId).param("ruleSet", ruleSet).param("version", version).update();
        for (Map.Entry<String, String> entry : new TreeMap<>(functionVersions).entrySet()) {
            jdbc.sql("""
                    INSERT INTO rle_version_function (tenant_id, version_id, function_name, function_version, recorded_at)
                    SELECT v.tenant_id, v.id, :function, :label, :at
                    FROM rle_version v JOIN rle_rule_set r ON r.tenant_id = v.tenant_id AND r.id = v.rule_set_id
                    WHERE v.tenant_id = :tenant AND r.name = :ruleSet AND v.version_num = :version
                    """).param("tenant", tenantId).param("ruleSet", ruleSet).param("version", version)
                    .param("function", entry.getKey()).param("label", entry.getValue())
                    .param("at", Timestamp.from(at)).update();
        }
    }

    @Override
    public Map<String, String> approvedWith(String tenantId, String ruleSet, int version) {
        Map<String, String> versions = new TreeMap<>();
        jdbc.sql("""
                SELECT f.function_name, f.function_version
                FROM rle_version_function f
                JOIN rle_version v ON v.tenant_id = f.tenant_id AND v.id = f.version_id
                JOIN rle_rule_set r ON r.tenant_id = v.tenant_id AND r.id = v.rule_set_id
                WHERE f.tenant_id = :tenant AND r.name = :ruleSet AND v.version_num = :version
                """).param("tenant", tenantId).param("ruleSet", ruleSet).param("version", version)
                .query((rs, row) -> {
                    versions.put(rs.getString("function_name"), rs.getString("function_version"));
                    return null;
                }).list();
        return versions;
    }

    @Override
    public List<Use> publishedUses() {
        return jdbc.sql("""
                SELECT f.tenant_id, r.name, v.version_num, f.function_name, f.function_version
                FROM rle_version_function f
                JOIN rle_version v ON v.tenant_id = f.tenant_id AND v.id = f.version_id
                JOIN rle_rule_set r ON r.tenant_id = v.tenant_id AND r.id = v.rule_set_id
                WHERE v.status = 'PUBLISHED'
                ORDER BY f.function_name, f.tenant_id, r.name, v.version_num
                """).query((rs, row) -> new Use(rs.getString("tenant_id"), rs.getString("name"),
                rs.getInt("version_num"), rs.getString("function_name"), rs.getString("function_version"))).list();
    }
}
