package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.postgres.ListQuery.Bound;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code RLE-085}: {@code EXPLAIN (FORMAT JSON)} for every query of {@link RuleListQueries} on a seeded database with
 * two tenants and three monthly decision partitions, with sequential scans disabled. The planner still falls back to
 * a sequential scan when no index can serve a query, so a small seed gives the verdict a large bank database would,
 * in seconds. A plan fails when it scans an engine table sequentially, when a page has no {@code Limit}, when a
 * parent-key or key-set query reaches its rows through an index condition that does not name its key, or when a
 * decision search reads more monthly partitions than its period spans. A sweep across tenants, which runs at start-up
 * and says so, may read its small table in full.
 */
@Testcontainers
class RuleListQueryPlanCheckTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOW = Instant.parse("2027-01-26T00:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Rule set 7 of bank-a and its version 2, as the seed writes the ids. */
    static final String RULE_SET = "A" + "RS" + "%022d".formatted(7);
    static final String VERSION = "A" + "VR" + "%022d".formatted(7 * 10 + 2);

    static SingleConnectionDataSource connection;
    static JdbcClient jdbc;

    @BeforeAll
    static void seed() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        new RuleEngineSchemaMigrator(pg).migrate();
        new DecisionLogPartitions(pg, new RuleEngineTransactions(new DataSourceTransactionManager(pg)))
                .ensureAhead(NOW, Duration.ofDays(40));
        JdbcClient seed = JdbcClient.create(pg);
        Timestamp at = Timestamp.from(NOW);
        for (String tenant : List.of("bank-a", "bank-b")) {
            String p = tenant.equals("bank-a") ? "A" : "B";
            // 3,000 rule sets, the first 40 with 4 versions each, the last one published and approved by a second person
            seed.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) SELECT ?, ? || 'RS' || lpad(g::text, 22, '0'), "
                            + "'rule-set-' || lpad(g::text, 4, '0'), ? FROM generate_series(1, 3000) g")
                    .params(tenant, p, at).update();
            seed.sql("INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, effective_from, "
                            + "canonical_json, canonical_hash, source_format, source_document, source_hash, lock_version, "
                            + "requested_by, requested_at, approved_by, approved_at, created_by, created_at) "
                            + "SELECT ?, ? || 'VR' || lpad((s * 10 + v)::text, 22, '0'), ? || 'RS' || lpad(s::text, 22, '0'), v, "
                            + "CASE WHEN v = 4 THEN 'PUBLISHED' ELSE 'DRAFT' END, 'FIRST', ?, "
                            + "'{\"inputs\":[{\"name\":\"tier\"}]}'::jsonb, repeat('a', 64), 'JSON', '{}', repeat('b', 64), 1, "
                            + "CASE WHEN v = 4 THEN 'ana' END, CASE WHEN v = 4 THEN ?::timestamptz END, "
                            + "CASE WHEN v = 4 THEN 'minh' END, CASE WHEN v = 4 THEN ?::timestamptz END, 'ana', ? "
                            + "FROM generate_series(1, 40) s, generate_series(1, 4) v")
                    .params(tenant, p, p, at, at, at, at).update();
            seed.sql("INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor, "
                            + "lock_version, occurred_at) SELECT ?, ? || 'HI' || lpad(((s * 10 + v) * 10 + h)::text, 22, '0'), "
                            + "? || 'VR' || lpad((s * 10 + v)::text, 22, '0'), 'CREATED', NULL, 'DRAFT', 'ana', h, ? "
                            + "FROM generate_series(1, 40) s, generate_series(1, 4) v, generate_series(1, 3) h")
                    .params(tenant, p, p, at).update();
            // 6,000 decisions of two rule sets from 1 January to 28 February 2027, one every 12 minutes
            seed.sql("INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome, "
                            + "masked_inputs, outputs, matched_rule_ids, correlation_id, eval_clock, duration_micros, log_mode) "
                            + "SELECT ?, ?::timestamptz - interval '25 days' + (g * 12 || ' minutes')::interval, "
                            + "'D' || lpad(g::text, 25, '0'), 'rule-set-000' || (g % 2 + 1), 1, 'FIRST', 'OK', "
                            + "'{\"tier\":\"GOLD\"}'::jsonb, '{}'::jsonb, '[\"R1\"]'::jsonb, 'case-' || (g % 500), "
                            + "?, 31, 'TRANSACTIONAL' FROM generate_series(1, 6000) g")
                    .params(tenant, at, at).update();
        }
        seed.sql("INSERT INTO rle_setting_seen (tenant_id, setting, value, recorded_at) VALUES ('bank-a', 'x', 'y', ?)")
                .params(at).update();
        seed.sql("ANALYZE").update();
        connection = new SingleConnectionDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), true);
        jdbc = JdbcClient.create(connection);
        jdbc.sql("SET enable_seqscan = off").update();
    }

    @AfterAll
    static void close() {
        if (connection != null) {
            connection.destroy();
        }
    }

    /** A 31-day range across January and February, and the pair of an exact month. */
    static final Timestamp FROM = Timestamp.from(Instant.parse("2027-01-10T00:00:00Z"));
    static final Timestamp TO = Timestamp.from(Instant.parse("2027-02-10T00:00:00Z"));

    /** Representative parameters per registered query: bank-a, its rule set 7 and a 31-day period. */
    static Map<String, Map<String, Object>> samples() {
        Timestamp now = Timestamp.from(NOW);
        Map<String, Object> period = ListQuery.params("tenant", "bank-a", "ruleSet", "rule-set-0001", "from", FROM, "to", TO,
                "limit", 201);
        Map<String, Map<String, Object>> samples = new LinkedHashMap<>();
        samples.put("cache-epochs", ListQuery.params("tenant", "bank-a", "names", List.of("rule-set-0001", "rule-set-0002")));
        samples.put("rule-sets", ListQuery.params("tenant", "bank-a", "after", "rule-set-0010", "limit", 50, "now", now));
        samples.put("versions", ListQuery.params("tenant", "bank-a", "ruleSetId", RULE_SET, "before", 4, "limit", 50));
        samples.put("version-history", ListQuery.params("tenant", "bank-a", "versionId", VERSION));
        samples.put("history-versions", ListQuery.params("tenant", "bank-a", "ruleSetId", RULE_SET, "before", 4, "limit", 50));
        samples.put("versions-by-number", ListQuery.params("tenant", "bank-a", "ruleSetId", RULE_SET, "numbers", List.of(1, 2)));
        samples.put("history-of-versions", ListQuery.params("tenant", "bank-a", "ids", List.of(VERSION), "perVersion", 20));
        samples.put("rule-set-events", ListQuery.params("tenant", "bank-a", "ruleSetId", RULE_SET, "limit", 20));
        samples.put("declared-inputs", ListQuery.params("tenant", "bank-a", "ruleSet", "rule-set-0007", "limit", 1000));
        samples.put("published-masked-by-default", ListQuery.params("tenant", "bank-a", "limit", 100));
        samples.put("retire-superseded", ListQuery.params("tenant", "bank-a", "ruleSet", RULE_SET, "now", now));
        samples.put("masking-key-scopes", ListQuery.params("keyId", "k1"));
        samples.put("live-version-inputs", ListQuery.params());
        samples.put("settings-seen", ListQuery.params("setting", "x"));
        samples.put("decision-partitions", ListQuery.params());
        samples.put("decision-search", period);
        samples.put("decision-search-after", with(period, "afterAt", TO, "afterId", "D" + "9".repeat(25)));
        samples.put("decisions-by-logged-input", with(period, "input", "tier", "texts", List.of("GOLD")));
        samples.put("decision-count", ListQuery.params("tenant", "bank-a", "ruleSet", "rule-set-0001", "from", FROM, "to", TO,
                "limit", 200_001));
        samples.put("decisions-by-correlation", ListQuery.params("tenant", "bank-a", "correlation", "case-7", "from", FROM,
                "to", TO, "limit", 201));
        samples.put("decisions-by-correlation-rule-set-after", ListQuery.params("tenant", "bank-a", "correlation", "case-7",
                "from", FROM, "to", TO, "ruleSet", "rule-set-0001", "afterAt", TO, "afterId", "D" + "9".repeat(25),
                "limit", 201));
        samples.put("decisions-by-input", with(period, "input", "tier", "value", "\"GOLD\""));
        samples.put("decisions-by-input-after", with(period, "input", "tier", "value", "\"GOLD\"", "afterAt", TO,
                "afterId", "D" + "9".repeat(25)));
        return samples;
    }

    private static Map<String, Object> with(Map<String, Object> base, Object... more) {
        Map<String, Object> params = new LinkedHashMap<>(base);
        params.putAll(ListQuery.params(more));
        return params;
    }

    @Test
    void RLE_085_list_queries_pass_query_plan_check() {
        Map<String, Map<String, Object>> samples = samples();
        List<String> violations = new ArrayList<>();
        for (ListQuery query : RuleListQueries.all()) {
            Map<String, Object> params = samples.get(query.name());
            if (params == null) {
                violations.add(query.name() + ": no sample parameters; add them to RuleListQueryPlanCheckTest.samples()");
                continue;
            }
            JsonNode plan = plan(query, params);
            if (System.getProperty("bpm.test.printPlans") != null) {
                System.out.println("PLAN " + query.name() + " " + plan);
            }
            violations.addAll(violations(query, plan));
        }

        assertThat(violations).as("list queries whose plan is not bounded").isEmpty();
        assertThat(samples.keySet()).as("sample parameters of queries no longer registered")
                .containsExactlyInAnyOrderElementsOf(RuleListQueries.all().stream().map(ListQuery::name).toList());
    }

    @Test
    void RLE_085_a_31_day_decision_search_reads_two_monthly_partitions_and_a_month_reads_one() {
        // Given the January and February partitions, each holding decisions of bank-a
        Map<String, Object> across = samples().get("decision-search");
        Map<String, Object> january = with(across, "from", Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                "to", Timestamp.from(Instant.parse("2027-02-01T00:00:00Z")));

        // Then 10 January to 10 February reads the two of them and January alone reads one; the default is never read
        assertThat(partitions(plan(RuleListQueries.DECISION_SEARCH, across)))
                .containsExactly("rle_decision_2027_01", "rle_decision_2027_02");
        assertThat(partitions(plan(RuleListQueries.DECISION_SEARCH, january))).containsExactly("rle_decision_2027_01");
    }

    @Test
    void RLE_085_a_group_by_over_the_decision_log_fails_the_plan_check() {
        // Given a report registered as if a tenant's outcome bounded it: every decision of bank-a, grouped
        ListQuery report = new ListQuery("decision-caller-counts", new Bound.ParentKey("caller_id", "claimed: one caller"),
                "SELECT caller_id, count(*) FROM rle_decision WHERE tenant_id = :tenant AND caller_id = :caller "
                        + "GROUP BY caller_id");

        // When the check reads its plan
        List<String> violations = violations(report, plan(report, ListQuery.params("tenant", "bank-a", "caller", "teller-01")));

        // Then it fails: the plan reads the tenant's decisions in every partition, with no index condition on outcome
        assertThat(violations).anySatisfy(v -> assertThat(v).startsWith("decision-caller-counts: no index condition on caller_id"));
    }

    @Test
    void RLE_085_a_parent_key_without_an_index_fails_the_plan_check() {
        // Given a lookup of the tenant's versions by a hash no index holds, registered as if it were a parent key
        ListQuery byHash = new ListQuery("versions-by-hash", new Bound.ParentKey("source_hash", "claimed: one hash"),
                "SELECT id FROM rle_version WHERE tenant_id = :tenant AND source_hash = :hash");

        // Then the plan reads the tenant's versions through an index that holds no hash condition, and the check names it
        assertThat(violations(byHash, plan(byHash, ListQuery.params("tenant", "bank-a", "hash", "b".repeat(64)))))
                .singleElement().asString().startsWith("versions-by-hash: no index condition on source_hash");
    }

    private static JsonNode plan(ListQuery query, Map<String, Object> params) {
        String json = jdbc.sql("EXPLAIN (FORMAT JSON) " + query.sql()).params(params).query(String.class).single();
        return JSON.readTree(json).get(0).get("Plan");
    }

    /** The monthly partitions of the decision log the plan reads, by name, and never the default one. */
    static Set<String> partitions(JsonNode plan) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan, nodes);
        Set<String> names = new java.util.TreeSet<>();
        for (JsonNode node : nodes) {
            String relation = node.path("Relation Name").asString("");
            if (relation.startsWith("rle_decision_")) {
                names.add(relation);
            }
        }
        return names;
    }

    /** What makes a plan unbounded, one line per finding. */
    static List<String> violations(ListQuery query, JsonNode plan) {
        List<JsonNode> nodes = new ArrayList<>();
        collect(plan, nodes);
        List<String> found = new ArrayList<>();
        boolean sweep = query.bound() instanceof Bound.Sweep;
        for (JsonNode node : nodes) {
            String type = node.path("Node Type").asString("");
            String relation = node.path("Relation Name").asString(node.path("Index Name").asString(""));
            if (sweep || !(relation.startsWith("rle_") || relation.startsWith("idx_rle_"))) {
                continue;
            }
            if (type.equals("Seq Scan")) {
                found.add(query.name() + ": sequential scan of " + relation + " (" + query.bound() + ")");
            } else if (type.endsWith("Index Scan") || type.equals("Index Only Scan")) {
                if (node.path("Index Cond").asString("").isEmpty()) {
                    // With sequential scans off, a whole-index walk is how the planner reads every row
                    found.add(query.name() + ": full scan of " + relation + " through " + node.path("Index Name").asString()
                            + " (" + query.bound() + ")");
                }
            }
        }
        switch (query.bound()) {
            case Bound.Page page -> requireLimit(query, nodes, page.why(), found);
            case Bound.PartitionedPage page -> {
                requireLimit(query, nodes, page.why(), found);
                Set<String> partitions = partitions(plan);
                if (partitions.contains("rle_decision_default") || partitions.size() > 2) {
                    found.add(query.name() + ": reads " + partitions + ", more than a 31-day period spans, and never the default ("
                            + page.why() + ")");
                }
            }
            case Bound.ParentKey parent -> requireKey(query, nodes, parent.column(), parent.why(), found);
            case Bound.KeySet keys -> requireKey(query, nodes, keys.column(), keys.why(), found);
            case Bound.Sweep sweepBound -> {
            }
        }
        return found;
    }

    private static void requireLimit(ListQuery query, List<JsonNode> nodes, String why, List<String> found) {
        if (nodes.stream().noneMatch(n -> n.path("Node Type").asString("").equals("Limit"))) {
            found.add(query.name() + ": no Limit in the plan (" + why + ")");
        }
    }

    private static void requireKey(ListQuery query, List<JsonNode> nodes, String column, String why, List<String> found) {
        boolean keyed = nodes.stream().anyMatch(n -> n.path("Index Cond").asString("").contains(column));
        if (!keyed) {
            found.add(query.name() + ": no index condition on " + column + " (" + why + ")");
        }
    }

    private static void collect(JsonNode node, List<JsonNode> into) {
        into.add(node);
        for (JsonNode child : node.path("Plans")) {
            collect(child, into);
        }
    }
}
