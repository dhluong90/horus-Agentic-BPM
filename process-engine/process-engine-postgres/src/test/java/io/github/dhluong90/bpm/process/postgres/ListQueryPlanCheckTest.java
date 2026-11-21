package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.postgres.ListQuery.Bound;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PRE-036}: {@code EXPLAIN (FORMAT JSON)} for every query of {@link ProcessListQueries} on a seeded database
 * with two tenants, with sequential scans disabled. The planner still falls back to a sequential scan when no
 * index can serve a query, so a small seed gives the verdict a large bank database would, in seconds. A plan fails
 * when it scans an engine table sequentially, when a page query has no {@code Limit}, or when a parent-key query
 * reaches its rows through an index condition that does not name its key.
 */
@Testcontainers
class ListQueryPlanCheckTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOW = Instant.parse("2026-11-20T07:00:00Z");
    static final Timestamp T = Timestamp.from(NOW);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Version 2 of loan-origination at bank-a, and one of its applications; ids as the seed writes them. */
    static final String DEFINITION = "ADEF" + "%022d".formatted(2);
    static final String INSTANCE = "AIN" + "%023d".formatted(42);

    static SingleConnectionDataSource connection;
    static JdbcClient jdbc;
    /** The partial indexes of the engine schema; an engine sweep may count every row of one. */
    static java.util.Set<String> partialIndexes = java.util.Set.of();

    @BeforeAll
    static void seed() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        new ProcessEngineSchemaMigrator(pg).migrate();
        JdbcClient seed = JdbcClient.create(pg);
        for (String tenant : List.of("bank-a", "bank-b")) {
            String p = tenant.equals("bank-a") ? "A" : "B";
            // Two keys with two versions each: loan-origination and card-dispute
            seed.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                            + "definition_hash, source_format, source_document, source_hash, node_count, source, "
                            + "effective_from, created_by, created_at, approved_by, approved_at) "
                            + "SELECT ?, ? || 'DEF' || lpad(g::text, 22, '0'), "
                            + "CASE WHEN g <= 2 THEN 'loan-origination' ELSE 'card-dispute' END, 2 - g % 2, 'ACTIVE', "
                            + "'Process', '{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 6, "
                            + "'IMPORT', ?, 'lan', ?, 'minh', ? FROM generate_series(1, 4) g")
                    .params(tenant, p, T, T, T).update();
            seed.sql("INSERT INTO pre_definition_history (tenant_id, id, definition_id, action, to_status, actor_id, "
                            + "occurred_at) SELECT ?, ? || 'DH' || lpad(g::text, 23, '0'), "
                            + "? || 'DEF' || lpad((g % 4 + 1)::text, 22, '0'), 'APPROVED', 'ACTIVE', 'minh', ? "
                            + "FROM generate_series(1, 400) g")
                    .params(tenant, p, p, T).update();
            // 5,000 loan applications, newest every minute, a quarter of them finished
            seed.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, "
                            + "business_key, status, variables, started_at) "
                            + "SELECT ?, ? || 'IN' || lpad(g::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), "
                            + "'loan-origination', 2, 'APP-' || g, CASE WHEN g % 4 = 0 THEN 'COMPLETED' ELSE 'RUNNING' END, "
                            + "'{}', ?::timestamptz - (g || ' minutes')::interval FROM generate_series(1, 5000) g")
                    .params(tenant, p, p, T).update();
            seed.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, current_node_id, "
                            + "status, entered_at, created_at) SELECT ?, ? || 'TS' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad(g::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), 'SCOPE', "
                            + "'loan-origination', 'WAITING_SCOPE', ?, ? FROM generate_series(1, 5000) g")
                    .params(tenant, p, p, p, T, T).update();
            seed.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                            + "current_node_id, status, entered_at, created_at) SELECT ?, ? || 'TF' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad(g::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), 'FLOW', "
                            + "? || 'TS' || lpad(g::text, 23, '0'), 'credit-review', "
                            + "CASE WHEN g % 4 = 0 THEN 'COMPLETED' ELSE 'WAITING_USER' END, ?, ? "
                            + "FROM generate_series(1, 5000) g")
                    .params(tenant, p, p, p, p, T, T).update();
            seed.sql("INSERT INTO pre_task (tenant_id, id, instance_id, token_id, definition_id, node_id, name, "
                            + "assignee_role, status, created_at) SELECT ?, ? || 'TK' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad(g::text, 23, '0'), ? || 'TF' || lpad(g::text, 23, '0'), "
                            + "? || 'DEF' || lpad('2', 22, '0'), 'credit-review', 'Credit review', 'CREDIT_OFFICER', 'OPEN', ? "
                            + "FROM generate_series(1, 5000) g")
                    .params(tenant, p, p, p, p, T).update();
            seed.sql("INSERT INTO pre_timer (tenant_id, id, instance_id, definition_id, token_id, job_kind, node_id, "
                            + "status, next_fire_at, lease_duration, created_at) SELECT ?, ? || 'JB' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad(g::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), "
                            + "? || 'TF' || lpad(g::text, 23, '0'), CASE WHEN g % 2 = 0 THEN 'TIMER' ELSE 'ASYNC_SERVICE' END, "
                            + "'credit-review', 'DUE', ?::timestamptz + (g || ' minutes')::interval, interval '30 seconds', ? "
                            + "FROM generate_series(1, 2000) g")
                    .params(tenant, p, p, p, p, T, T).update();
            // An open incident on every tenth application, the credit-review step failed for good
            seed.sql("INSERT INTO pre_incident (tenant_id, id, instance_id, definition_id, token_id, node_id, error_code, "
                            + "cause_summary, status, created_at) SELECT ?, ? || 'IC' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad(g::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), "
                            + "? || 'TF' || lpad(g::text, 23, '0'), 'credit-review', 'ADAPTER_FAILED', "
                            + "'ADAPTER_FAILED after 3 attempts', CASE WHEN g % 20 = 0 THEN 'RESOLVED' ELSE 'OPEN' END, ? "
                            + "FROM generate_series(10, 5000, 10) g")
                    .params(tenant, p, p, p, p, T).update();
            // The recorded result of the credit-review step of every other application (V14)
            seed.sql("INSERT INTO pre_step_result (tenant_id, instance_id, node_id, definition_id, result_json, source, "
                            + "activity_instance_id, recorded_at) SELECT ?, ? || 'IN' || lpad(g::text, 23, '0'), "
                            + "'credit-review', ? || 'DEF' || lpad('2', 22, '0'), '{\"score\": 712}', 'ANSWER', "
                            + "? || 'AI' || lpad(g::text, 23, '0'), ? FROM generate_series(2, 5000, 2) g")
                    .params(tenant, p, p, p, T).update();
            // Ten replay runs of the KYC agent step with 200 cases each (V17)
            seed.sql("INSERT INTO pre_agent_replay (tenant_id, id, status, definition_key, node_id, period_from, period_to, "
                            + "candidate_rule_set, candidate_version, created_by, created_at, expires_at) "
                            + "SELECT ?, ? || 'RP' || lpad(g::text, 23, '0'), 'COMPLETED', 'loan-origination', 'kyc-agent', "
                            + "DATE '2026-10-01', DATE '2026-10-31', 'kyc-guard', 2, 'lan', ?, ?::timestamptz + interval '30 days' "
                            + "FROM generate_series(1, 10) g")
                    .params(tenant, p, T, T).update();
            seed.sql("INSERT INTO pre_agent_replay_case (tenant_id, replay_id, seq, attempt_id, instance_id, recorded_at, "
                            + "recorded_outcome, candidate_outcome, change, recorded_rule_ids, candidate_rule_ids, overridden, "
                            + "masked_fields) SELECT ?, ? || 'RP' || lpad((g % 10 + 1)::text, 23, '0'), g / 10, "
                            + "? || 'AT' || lpad(g::text, 23, '0'), ? || 'IN' || lpad((g % 5000 + 1)::text, 23, '0'), ?, "
                            + "'ALLOWED', 'REFUSED', 'NEWLY_REFUSED', '[]', '[\"R4\"]', false, 0 "
                            + "FROM generate_series(0, 1999) g")
                    .params(tenant, p, p, p, T).update();
            // 20,000 history rows, the table a careless report would group over
            seed.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                            + "details) SELECT ?, ?::timestamptz - (g || ' seconds')::interval, ? || 'HI' || lpad(g::text, 23, '0'), "
                            + "? || 'IN' || lpad((g % 5000 + 1)::text, 23, '0'), ? || 'DEF' || lpad('2', 22, '0'), "
                            + "'NODE_ENTERED', 'credit-review', '{}' FROM generate_series(1, 20000) g")
                    .params(tenant, T, p, p, p).update();
        }
        seed.sql("ANALYZE").update();
        connection = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), true);
        jdbc = JdbcClient.create(connection);
        jdbc.sql("SET enable_seqscan = off").update();
        partialIndexes = java.util.Set.copyOf(jdbc.sql("SELECT c.relname FROM pg_index i JOIN pg_class c "
                + "ON c.oid = i.indexrelid WHERE i.indpred IS NOT NULL").query(String.class).list());
    }

    @AfterAll
    static void close() {
        if (connection != null) {
            connection.destroy();
        }
    }

    /** Representative parameters per registered query: bank-a, its loan-origination key, a 31-day range. */
    static Map<String, List<Object>> samples() {
        Timestamp from = Timestamp.from(NOW.minusSeconds(31L * 24 * 3600));
        Map<String, List<Object>> samples = new LinkedHashMap<>();
        samples.put("live-token-counts", List.of("bank-a", DEFINITION));
        samples.put("lock-definition-versions", List.of("bank-a", "loan-origination"));
        samples.put("definition-history", List.of("bank-a", DEFINITION));
        samples.put("instance-tokens", List.of("bank-a", INSTANCE));
        samples.put("instance-tasks", List.of("bank-a", INSTANCE));
        samples.put("instance-open-incidents", List.of("bank-a", INSTANCE));
        samples.put("instance-compensation-stack", List.of("bank-a", INSTANCE));
        samples.put("incidents", List.of("bank-a", "OPEN", 50));
        samples.put("incidents-after", List.of("bank-a", "OPEN", T, "AIC" + "%023d".formatted(500), 50));
        samples.put("instance-step-results", List.of("bank-a", INSTANCE, new String[] {"credit-review", "kyc-agent"}));
        samples.put("replay-overridden-attempts", List.of("bank-a", new String[] {INSTANCE, "AIN" + "%023d".formatted(43)},
                new String[] {"AAT" + "%023d".formatted(42)}));
        samples.put("replay-recorded-answers", List.of("bank-a", "loan-origination", "kyc-agent", from, T, 100));
        samples.put("replay-recorded-answers-after", List.of("bank-a", "loan-origination", "kyc-agent", from, T, from,
                "AHI" + "%023d".formatted(7), 100));
        samples.put("replay-cases", List.of("bank-a", "ARP" + "%023d".formatted(3), 50, 100));
        samples.put("definition-keys", List.of(T, "bank-a", 50));
        samples.put("definition-keys-after", List.of(T, "bank-a", "card-dispute", 50));
        samples.put("claim-timers", List.of(T, T, 20, "replica-1", T));
        samples.put("claim-async-steps", List.of(T, T, 20, "replica-1", T));
        samples.put("claim-async-kind", List.of(T, T, "ASYNC_SERVICE", 20, "replica-1", T));
        samples.put("instance-history-actors", List.of("bank-a", INSTANCE, "NODE_ENTERED"));
        samples.put("migration-live-nodes", List.of("bank-a", DEFINITION));
        samples.put("migration-instances-at-node", List.of("bank-a", DEFINITION, "credit-review", 100));
        samples.put("migration-movable-instances", List.of("bank-a", DEFINITION, new String[] {"legacy-review"}, 100));
        Timestamp sliceFrom = Timestamp.from(NOW.minusSeconds(3600));
        Timestamp sliceTo = Timestamp.from(NOW.minusSeconds(3540));
        samples.put("instance-token-entered-at", List.of("bank-a", INSTANCE));
        samples.put("rollup-slice", List.of(sliceFrom, sliceTo));
        samples.put("window-node-sums", List.of("bank-a", DEFINITION, from, T));
        samples.put("window-node-histogram", List.of("bank-a", DEFINITION, from, T));
        samples.put("gauge-active-instances", List.of());
        samples.put("gauge-open-incidents", List.of());
        samples.put("gauge-oldest-due-job", List.of("TIMER"));
        samples.put("calendar-holidays", List.of("bank-a", "ACAL1"));
        String[] both = {"CLAIMED", "OPEN"};
        Timestamp end = Timestamp.from(Instant.parse("9999-12-31T00:00:00Z"));
        Timestamp epoch = Timestamp.from(Instant.EPOCH);
        samples.put("tasks-mine-dated", List.of("bank-a", "minh", both, epoch, "", end, 51));
        samples.put("tasks-claimed-dated", List.of("bank-a", "minh", both, epoch, "", end, 51));
        samples.put("tasks-role-dated", List.of("bank-a", "CREDIT_OFFICER", both, T, INSTANCE, end, 51));
        samples.put("tasks-group-dated", List.of("bank-a", "branch-hcm-01", both, epoch, "", T, 51));
        samples.put("tasks-any-dated", List.of("bank-a", both, epoch, "", end, 51));
        samples.put("tasks-mine-undated", List.of("bank-a", "minh", both, "", 51));
        samples.put("tasks-claimed-undated", List.of("bank-a", "minh", both, "", 51));
        samples.put("tasks-role-undated", List.of("bank-a", "CREDIT_OFFICER", both, "", 51));
        samples.put("tasks-group-undated", List.of("bank-a", "branch-hcm-01", both, "", 51));
        samples.put("tasks-any-undated", List.of("bank-a", both, INSTANCE, 51));
        samples.put("instances-1", List.of("bank-a", "RUNNING", 50));
        samples.put("instances-3-key-range-after", List.of("bank-a", "RUNNING", "COMPLETED", "FAILED", "loan-origination",
                from, T, T, INSTANCE, 50));
        return samples;
    }

    @Test
    void PRE_036_list_queries_pass_query_plan_check() {
        Map<String, List<Object>> samples = samples();
        List<String> violations = new ArrayList<>();
        for (ListQuery query : ProcessListQueries.all()) {
            List<Object> params = samples.get(query.name());
            if (params == null) {
                violations.add(query.name() + ": no sample parameters; add them to ListQueryPlanCheckTest.samples()");
                continue;
            }
            violations.addAll(violations(query, plan(query, params)));
        }

        assertThat(violations).as("list queries whose plan is not bounded").isEmpty();
        assertThat(samples.keySet()).as("sample parameters of queries no longer registered")
                .containsExactlyInAnyOrderElementsOf(ProcessListQueries.all().stream().map(ListQuery::name).toList());
    }

    @Test
    void PRE_036_the_claim_of_one_async_kind_reads_the_V9_kind_index() {
        // Given 1,000 due ASYNC_SERVICE steps of bank-a among 2,000 due jobs, and the async-service runner's poll
        List<Object> params = ProcessListQueries.claimParams(ProcessListQueries.ClaimKinds.ONE_KIND, "ASYNC_SERVICE",
                T, 20, "replica-1");

        // When the check reads its plan
        JsonNode plan = plan(ProcessListQueries.CLAIM_ASYNC_KIND, params);
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan, nodes);

        // Then the kind is an index condition of idx_pre_timer_poll_kind (V9), so another kind's backlog is not read
        assertThat(violations(ProcessListQueries.CLAIM_ASYNC_KIND, plan)).isEmpty();
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.path("Index Name").asString("")).isEqualTo("idx_pre_timer_poll_kind");
            assertThat(n.path("Index Cond").asString("")).contains("job_kind");
        });
    }

    /**
     * The rollup slice is bounded by its time range through an index, whichever index the planner picks. PostgreSQL
     * 14 and 16 read it through {@code idx_pre_history_time_brin} on each partition (V27). PostgreSQL 18 adds B-tree
     * skip scan, so with few tenants it may read the same range through the primary key
     * {@code (tenant_id, occurred_at, id)}; both are an index condition on both ends of {@code occurred_at}. That
     * the BRIN exists on every partition is checked by {@link RollupMigrationTest}.
     */
    @Test
    void PRE_036_the_rollup_slice_reads_history_by_its_time_index() {
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan(ProcessListQueries.ROLLUP_SLICE, samples().get("rollup-slice")), nodes);

        assertThat(nodes).noneSatisfy(n -> assertThat(n.path("Node Type").asString("")).isEqualTo("Seq Scan"));
        assertThat(nodes).as("an index condition on both ends of the slice").anySatisfy(n -> assertThat(
                n.path("Index Cond").asString("")).contains("occurred_at >=").contains("occurred_at <"));
    }

    @Test
    void PRE_036_a_group_by_over_pre_history_fails_the_plan_check() {
        // Given a report registered as if one event type bounded it: every history row of bank-a, grouped
        ListQuery report = new ListQuery("history-event-counts",
                new Bound.ParentKey("event_type", "claimed: one event type"),
                "SELECT event_type, count(*) FROM pre_history WHERE tenant_id = ? AND event_type = ? GROUP BY event_type");

        // When the check reads its plan
        List<String> violations = violations(report, plan(report, List.of("bank-a", "NODE_ENTERED")));

        // Then it fails: no index on event_type, so the plan reads the tenant's whole history
        assertThat(violations).singleElement().asString()
                .startsWith("history-event-counts: no index condition on event_type");
    }

    @Test
    void PRE_036_a_page_without_a_usable_index_fails_the_plan_check() {
        // Given a page of tasks ordered by a column no index leads with
        ListQuery page = new ListQuery("tasks-by-name", new Bound.Page("claimed: by name"),
                "SELECT id FROM pre_task WHERE name = ? ORDER BY name LIMIT ?");

        // Then the plan scans pre_task sequentially and the check names it
        assertThat(violations(page, plan(page, List.of("Credit review", 50))))
                .anySatisfy(v -> assertThat(v).startsWith("tasks-by-name: ").contains(" scan of pre_task"));
    }

    private static JsonNode plan(ListQuery query, List<Object> params) {
        String json = jdbc.sql("EXPLAIN (FORMAT JSON) " + query.sql()).params(params).query(String.class).single();
        return JSON.readTree(json).get(0).get("Plan");
    }

    /** What makes a plan unbounded, one line per finding. */
    static List<String> violations(ListQuery query, JsonNode plan) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan, nodes);
        List<String> found = new ArrayList<>();
        for (JsonNode node : nodes) {
            String type = node.path("Node Type").asString("");
            String relation = node.path("Relation Name").asString(node.path("Index Name").asString(""));
            if (!relation.startsWith("pre_") && !relation.startsWith("idx_pre_") && !relation.startsWith("uq_pre_")) {
                continue;
            }
            if (type.equals("Seq Scan")) {
                found.add(query.name() + ": sequential scan of " + relation + " (" + query.bound() + ")");
            } else if (type.endsWith("Index Scan") || type.equals("Index Only Scan")) {
                boolean sweptPartial = query.bound() instanceof Bound.EngineSweep sweep && sweep.column() == null
                        && partialIndexes.contains(node.path("Index Name").asString(""));
                if (node.path("Index Cond").asString("").isEmpty() && !sweptPartial) {
                    // With sequential scans off, a whole-index walk is how the planner reads every row
                    found.add(query.name() + ": full scan of " + relation + " through " + node.path("Index Name").asString()
                            + " (" + query.bound() + ")");
                }
            }
        }
        switch (query.bound()) {
            case Bound.Page page -> {
                if (nodes.stream().noneMatch(n -> n.path("Node Type").asString("").equals("Limit"))) {
                    found.add(query.name() + ": no Limit in the plan (" + page.why() + ")");
                }
            }
            case Bound.ParentKey parent -> {
                boolean keyed = nodes.stream().anyMatch(n -> n.path("Index Cond").asString("").contains(parent.column()));
                if (!keyed) {
                    found.add(query.name() + ": no index condition on " + parent.column() + " (" + parent.why() + ")");
                }
            }
            case Bound.ParentKeys parents -> {
                boolean keyed = nodes.stream().anyMatch(n -> n.path("Index Cond").asString("").contains(parents.column()));
                if (!keyed) {
                    found.add(query.name() + ": no index condition on " + parents.column() + " (" + parents.why() + ")");
                }
            }
            case Bound.EngineSweep sweep -> {
                if (sweep.column() != null) {
                    boolean ranged = nodes.stream().map(n -> n.path("Index Cond").asString(""))
                            .anyMatch(c -> c.contains(sweep.column() + " >=") && c.contains(sweep.column() + " <"));
                    if (!ranged) {
                        found.add(query.name() + ": no index condition on both ends of " + sweep.column() + " ("
                                + sweep.why() + ")");
                    }
                } else if (nodes.stream().noneMatch(n -> n.path("Node Type").asString("").equals("Limit")
                        || n.path("Node Type").asString("").equals("Aggregate"))) {
                    found.add(query.name() + ": neither a Limit nor a single count in the plan (" + sweep.why() + ")");
                }
            }
        }
        return found;
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        into.add(node);
        for (JsonNode child : node.path("Plans")) {
            collect(child, into);
        }
    }
}
