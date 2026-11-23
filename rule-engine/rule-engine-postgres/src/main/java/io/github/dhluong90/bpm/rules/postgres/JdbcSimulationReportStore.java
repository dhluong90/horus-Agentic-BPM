package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.json.Ulid;
import io.github.dhluong90.bpm.rules.internal.store.SimulationReportStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code rle_simulation_report} (V37) and {@code rle_simulation_report_attachment} (V38): reports written once, and the
 * report a publication request carries. Both tables are append-only in the database. Every statement names its tenant
 * (TR-TEN-01); a report is found by tenant, rule set, version and id together, so another tenant's, rule set's or
 * version's report is simply not there.
 */
public final class JdbcSimulationReportStore implements SimulationReportStore {

    private static final String COLUMNS = """
            id, rule_set_name, version_num, canonical_hash, created_by, created_at, source, period_from, period_to,
            period_limit, truncated, total_count, changed_count, unchanged_count, failed_count, not_replayable_count,
            languages, report_rows::text AS report_rows, rows_truncated
            """;

    private final JdbcClient jdbc;

    /**
     * Creates the store.
     *
     * @param dataSource the host's data source
     */
    public JdbcSimulationReportStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void insert(Stored report) {
        jdbc.sql("""
                INSERT INTO rle_simulation_report (tenant_id, id, rule_set_name, version_num, canonical_hash,
                  created_by, created_at, source, period_from, period_to, period_limit, truncated, total_count,
                  changed_count, unchanged_count, failed_count, not_replayable_count, languages, report_rows,
                  rows_truncated)
                VALUES (:tenant, :id, :ruleSet, :version, :hash, :by, :at, :source, :from, :to, :limit, :truncated,
                  :total, :changed, :unchanged, :failed, :notReplayable, :languages, CAST(:rows AS jsonb),
                  :rowsTruncated)
                """)
                .param("tenant", report.tenantId()).param("id", report.id()).param("ruleSet", report.ruleSet())
                .param("version", report.version()).param("hash", report.canonicalHash())
                .param("by", report.createdBy()).param("at", Timestamp.from(report.createdAt()))
                .param("source", report.source()).param("from", timestamp(report.periodFrom()))
                .param("to", timestamp(report.periodTo())).param("limit", report.periodLimit(), java.sql.Types.INTEGER)
                .param("truncated", report.truncated()).param("total", report.total())
                .param("changed", report.changed()).param("unchanged", report.unchanged())
                .param("failed", report.failed()).param("notReplayable", report.notReplayable())
                .param("languages", report.languages()).param("rows", report.rowsJson())
                .param("rowsTruncated", report.rowsTruncated()).update();
    }

    @Override
    public Optional<Stored> find(String tenantId, String ruleSet, int version, String id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM rle_simulation_report
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND version_num = :version AND id = :id
                """).param("tenant", tenantId).param("ruleSet", ruleSet).param("version", version).param("id", id)
                .query((rs, row) -> stored(tenantId, rs)).optional();
    }

    @Override
    public List<Stored> list(String tenantId, String ruleSet, int version, int limit) {
        return RuleListQueries.SIMULATION_REPORTS.list(jdbc, ListQuery.params("tenant", tenantId, "ruleSet", ruleSet,
                "version", version, "limit", Math.min(limit, LIST_MAX)), (rs, row) -> stored(tenantId, rs));
    }

    @Override
    public void attach(String tenantId, String ruleSet, int version, String reportId, String attachedBy, Instant at) {
        jdbc.sql("""
                INSERT INTO rle_simulation_report_attachment (tenant_id, id, rule_set_name, version_num, report_id,
                  attached_by, attached_at)
                VALUES (:tenant, :id, :ruleSet, :version, :report, :by, :at)
                """).param("tenant", tenantId).param("id", Ulid.next(at)).param("ruleSet", ruleSet)
                .param("version", version).param("report", reportId).param("by", attachedBy)
                .param("at", Timestamp.from(at)).update();
    }

    @Override
    public Optional<String> attachedReport(String tenantId, String ruleSet, int version) {
        return jdbc.sql("""
                SELECT report_id FROM rle_simulation_report_attachment
                WHERE tenant_id = :tenant AND rule_set_name = :ruleSet AND version_num = :version
                ORDER BY id DESC LIMIT 1
                """).param("tenant", tenantId).param("ruleSet", ruleSet).param("version", version)
                .query(String.class).optional();
    }

    private static Stored stored(String tenantId, ResultSet rs) throws SQLException {
        int limit = rs.getInt("period_limit");
        Integer periodLimit = rs.wasNull() ? null : limit;
        return new Stored(tenantId, rs.getString("id"), rs.getString("rule_set_name"), rs.getInt("version_num"),
                rs.getString("canonical_hash").strip(), rs.getString("created_by"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("source"), instant(rs, "period_from"),
                instant(rs, "period_to"), periodLimit, rs.getBoolean("truncated"), rs.getInt("total_count"),
                rs.getInt("changed_count"), rs.getInt("unchanged_count"), rs.getInt("failed_count"),
                rs.getInt("not_replayable_count"), rs.getString("languages"), rs.getString("report_rows"),
                rs.getBoolean("rows_truncated"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
