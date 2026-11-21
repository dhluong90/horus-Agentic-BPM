package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.internal.service.DurationHistogram;
import io.github.dhluong90.bpm.process.internal.store.NodeStatsStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link NodeStatsStore} on PostgreSQL (#110, migration V26). Every statement is registered in
 * {@link ProcessListQueries}. The write methods join the caller's transaction and never open one.
 */
public final class JdbcNodeStatsStore implements NodeStatsStore {

    private static final int FETCH_SIZE = 2_000;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    /** @param dataSource the host's data source */
    public JdbcNodeStatsStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = JdbcClient.create(dataSource);
        this.template = new JdbcTemplate(dataSource);
        this.template.setFetchSize(FETCH_SIZE);
    }

    @Override
    public void ensureWatermark(String job, Instant initial, String replicaId, Instant now) {
        jdbc.sql("INSERT INTO pre_engine_watermark (job_name, watermark, updated_by, updated_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (job_name) DO NOTHING")
                .params(job, ts(initial), replicaId, ts(now)).update();
    }

    @Override
    public Optional<Instant> lockWatermark(String job) {
        return jdbc.sql("SELECT watermark FROM pre_engine_watermark WHERE job_name = ? FOR UPDATE SKIP LOCKED")
                .param(job).query((rs, row) -> rs.getTimestamp("watermark").toInstant()).optional();
    }

    @Override
    public Collection<NodeHour> aggregate(Instant from, Instant to) {
        Map<String, Accumulator> byKey = new LinkedHashMap<>();
        template.query(ProcessListQueries.ROLLUP_SLICE.sql(), ps -> {
            ps.setObject(1, ts(from));
            ps.setObject(2, ts(to));
        }, rs -> {
            Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
            Instant hour = occurredAt.truncatedTo(ChronoUnit.HOURS);
            String tenant = rs.getString("tenant_id");
            String definition = rs.getString("definition_id");
            String node = rs.getString("node_id");
            Accumulator acc = byKey.computeIfAbsent(tenant + '\u0000' + definition + '\u0000' + hour + '\u0000' + node,
                    k -> new Accumulator(tenant, definition, hour, node));
            switch (rs.getString("event_type")) {
                case "NODE_ENTERED" -> acc.entered++;
                case "NODE_FAILED" -> acc.failed++;
                case "INCIDENT_RAISED" -> acc.incidents++;
                case "SLA_BREACHED" -> acc.slaBreaches++;
                case "NODE_COMPLETED" -> {
                    acc.completed++;
                    Timestamp entered = rs.getTimestamp("entered_at");
                    long ms = entered == null ? 0 : Math.max(0, occurredAt.toEpochMilli() - entered.getTime());
                    acc.durationSumMs += ms;
                    acc.histogram[DurationHistogram.bucket(ms)]++;
                }
                default -> {
                }
            }
        });
        List<NodeHour> hours = new ArrayList<>(byKey.size());
        byKey.values().forEach(a -> hours.add(a.toNodeHour()));
        return hours;
    }

    @Override
    public void add(Collection<NodeHour> hours, Instant now) {
        for (NodeHour h : hours) {
            int[] hist = new int[DurationHistogram.BUCKETS];
            for (int i = 0; i < hist.length; i++) {
                hist[i] = Math.toIntExact(h.histogram()[i]);
            }
            jdbc.sql("INSERT INTO pre_node_stats_hourly (tenant_id, definition_id, bucket_start, node_id, entered_count, "
                            + "completed_count, failed_count, incident_count, sla_breach_count, duration_sum_ms, "
                            + "duration_hist, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, definition_id, bucket_start, node_id) DO UPDATE SET "
                            + "entered_count = pre_node_stats_hourly.entered_count + EXCLUDED.entered_count, "
                            + "completed_count = pre_node_stats_hourly.completed_count + EXCLUDED.completed_count, "
                            + "failed_count = pre_node_stats_hourly.failed_count + EXCLUDED.failed_count, "
                            + "incident_count = pre_node_stats_hourly.incident_count + EXCLUDED.incident_count, "
                            + "sla_breach_count = pre_node_stats_hourly.sla_breach_count + EXCLUDED.sla_breach_count, "
                            + "duration_sum_ms = pre_node_stats_hourly.duration_sum_ms + EXCLUDED.duration_sum_ms, "
                            + "duration_hist = ARRAY(SELECT a + b FROM unnest(pre_node_stats_hourly.duration_hist, "
                            + "EXCLUDED.duration_hist) AS t(a, b)), updated_at = EXCLUDED.updated_at")
                    .params(h.tenantId(), h.definitionId(), ts(h.bucketStart()), h.nodeId(), h.entered(), h.completed(),
                            h.failed(), h.incidents(), h.slaBreaches(), h.durationSumMs(), hist, ts(now))
                    .update();
        }
    }

    @Override
    public void advance(String job, Instant to, String replicaId, Instant now) {
        int moved = jdbc.sql("UPDATE pre_engine_watermark SET watermark = ?, updated_by = ?, updated_at = ? "
                        + "WHERE job_name = ?").params(ts(to), replicaId, ts(now), job).update();
        if (moved != 1) {
            throw new IllegalStateException("The rollup watermark row is missing");
        }
    }

    @Override
    public Optional<Instant> watermark(String job) {
        return jdbc.sql("SELECT watermark FROM pre_engine_watermark WHERE job_name = ?").param(job)
                .query((rs, row) -> rs.getTimestamp("watermark").toInstant()).optional();
    }

    @Override
    public List<NodeHour> window(String tenant, String definitionId, Instant from, Instant to) {
        jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
        Map<String, Accumulator> byNode = new LinkedHashMap<>();
        List<Object> params = List.of(tenant, definitionId, ts(from), ts(to));
        ProcessListQueries.WINDOW_NODE_SUMS.list(jdbc, params, (rs, row) -> {
                    Accumulator a = new Accumulator(tenant, definitionId, from, rs.getString("node_id"));
                    a.entered = rs.getLong("entered");
                    a.completed = rs.getLong("completed");
                    a.failed = rs.getLong("failed");
                    a.incidents = rs.getLong("incidents");
                    a.slaBreaches = rs.getLong("sla_breaches");
                    a.durationSumMs = rs.getLong("duration_sum");
                    byNode.put(a.node, a);
                    return null;
                });
        Map<String, long[]> histograms = new HashMap<>();
        ProcessListQueries.WINDOW_NODE_HISTOGRAM.list(jdbc, params, (rs, row) -> {
                    long[] h = histograms.computeIfAbsent(rs.getString("node_id"), n -> new long[DurationHistogram.BUCKETS]);
                    h[rs.getInt("k") - 1] = rs.getLong("n");
                    return null;
                });
        List<NodeHour> result = new ArrayList<>();
        byNode.forEach((node, a) -> {
            long[] h = histograms.get(node);
            if (h != null) {
                System.arraycopy(h, 0, a.histogram, 0, h.length);
            }
            result.add(a.toNodeHour());
        });
        return result;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** Mutable sums of one node in one hour while a slice is read. */
    private static final class Accumulator {
        final String tenant;
        final String definition;
        final Instant hour;
        final String node;
        long entered;
        long completed;
        long failed;
        long incidents;
        long slaBreaches;
        long durationSumMs;
        final long[] histogram = new long[DurationHistogram.BUCKETS];

        Accumulator(String tenant, String definition, Instant hour, String node) {
            this.tenant = tenant;
            this.definition = definition;
            this.hour = hour;
            this.node = node;
        }

        NodeHour toNodeHour() {
            return new NodeHour(tenant, definition, hour, node, entered, completed, failed, incidents, slaBreaches,
                    durationSumMs, histogram.clone());
        }
    }
}
