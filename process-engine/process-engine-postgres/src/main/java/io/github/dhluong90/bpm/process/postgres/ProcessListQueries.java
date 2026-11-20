package io.github.dhluong90.bpm.process.postgres;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * The registry of the engine's bounded read queries (PRE-036, DESIGN section 8.1). A query that reads more than a
 * primary-key lookup is declared here with the bound that keeps it cheap: a leading-index equality, a time range, or
 * a partial index over live rows. Stores run their bounded reads through these statements, and the query-plan check
 * runs {@code EXPLAIN} on every registered one with representative parameters and fails on a sequential scan, so a
 * query added without a declared bound has no entry to pass the check with.
 *
 * <p>The tenant is the first parameter of every request-path query. Three queries read across tenants because they are
 * engine-internal sweeps that take no tenant from a caller (the rollup slice and the metric gauges); their results
 * carry no tenant to a caller, and the rollup copies each row's own tenant to its own rows.
 */
public final class ProcessListQueries {

    /**
     * One registered query.
     *
     * @param name stable name, shown in a failed check
     * @param sql the statement, with {@code ?} parameters
     * @param bound how the statement is bounded: the index and the range
     * @param sampleParameters representative parameters for the plan check
     */
    public record Registered(String name, String sql, String bound, Supplier<List<Object>> sampleParameters) {
    }

    /** Events the rollup counts; the tokens' entry time is looked up through the instance's history index. */
    static final String ROLLUP_SLICE_SQL = "SELECT h.tenant_id, h.definition_id, h.node_id, h.event_type, "
            + "h.occurred_at, CASE WHEN h.event_type = 'NODE_COMPLETED' THEN (SELECT max(e.occurred_at) "
            + "FROM pre_history e WHERE e.tenant_id = h.tenant_id AND e.instance_id = h.instance_id "
            + "AND e.occurred_at <= h.occurred_at AND e.event_type = 'NODE_ENTERED' AND e.token_id = h.token_id "
            + "AND e.node_id = h.node_id) END AS entered_at "
            + "FROM pre_history h WHERE h.occurred_at >= ? AND h.occurred_at < ? AND h.node_id IS NOT NULL "
            + "AND h.event_type IN ('NODE_ENTERED','NODE_COMPLETED','NODE_FAILED','INCIDENT_RAISED','SLA_BREACHED')";

    static final String WINDOW_SUMS_SQL = "SELECT node_id, sum(entered_count) AS entered, sum(completed_count) AS "
            + "completed, sum(failed_count) AS failed, sum(incident_count) AS incidents, sum(sla_breach_count) AS "
            + "sla_breaches, sum(duration_sum_ms) AS duration_sum FROM pre_node_stats_hourly "
            + "WHERE tenant_id = ? AND definition_id = ? AND bucket_start >= ? AND bucket_start < ? GROUP BY node_id";

    static final String WINDOW_HISTOGRAM_SQL = "SELECT s.node_id, u.k, sum(u.h)::bigint AS n "
            + "FROM pre_node_stats_hourly s CROSS JOIN LATERAL unnest(s.duration_hist) WITH ORDINALITY AS u(h, k) "
            + "WHERE s.tenant_id = ? AND s.definition_id = ? AND s.bucket_start >= ? AND s.bucket_start < ? "
            + "GROUP BY s.node_id, u.k";

    static final String ACTIVE_INSTANCES_SQL = "SELECT count(*) FROM pre_instance WHERE status = 'RUNNING'";

    static final String OPEN_INCIDENTS_SQL = "SELECT count(*) FROM pre_incident WHERE status = 'OPEN'";

    static final String OLDEST_DUE_SQL = "SELECT next_fire_at FROM pre_timer WHERE job_kind = ? AND status = 'DUE' "
            + "ORDER BY next_fire_at, id LIMIT 1";

    private static final Instant SAMPLE_FROM = Instant.parse("2026-11-20T10:00:00Z");
    private static final Instant SAMPLE_TO = Instant.parse("2026-11-20T10:01:00Z");
    private static final String SAMPLE_DEFINITION = "01JDEFINITION00000000000001";

    private static final List<Registered> ALL = List.of(
            new Registered("live-node-counts", JdbcProcessStore.LIVE_COUNTS_SQL,
                    "tenant and definition equality on idx_pre_token_live, live statuses only",
                    () -> List.of("bank-a", SAMPLE_DEFINITION)),
            new Registered("rollup-slice", ROLLUP_SLICE_SQL,
                    "one slice of at most stats.rollup-max-slice by occurred_at (idx_pre_history_time_brin); entry time "
                            + "through idx_pre_history_instance",
                    () -> List.of(SAMPLE_FROM, SAMPLE_TO)),
            new Registered("window-node-sums", WINDOW_SUMS_SQL,
                    "primary key range (tenant, definition, bucket_start) of at most query.max-range",
                    () -> List.of("bank-a", SAMPLE_DEFINITION, SAMPLE_FROM, SAMPLE_TO)),
            new Registered("window-node-histogram", WINDOW_HISTOGRAM_SQL,
                    "primary key range (tenant, definition, bucket_start) of at most query.max-range",
                    () -> List.of("bank-a", SAMPLE_DEFINITION, SAMPLE_FROM, SAMPLE_TO)),
            new Registered("gauge-active-instances", ACTIVE_INSTANCES_SQL,
                    "index-only scan of the partial index idx_pre_instance_running", List::of),
            new Registered("gauge-open-incidents", OPEN_INCIDENTS_SQL,
                    "index-only scan of the partial index idx_pre_incident_open", List::of),
            new Registered("gauge-oldest-due-job", OLDEST_DUE_SQL,
                    "first entry of idx_pre_timer_poll_kind for one kind, LIMIT 1", () -> List.of("TIMER")));

    private ProcessListQueries() {
    }

    /** @return every registered bounded query */
    public static List<Registered> all() {
        return ALL;
    }
}
