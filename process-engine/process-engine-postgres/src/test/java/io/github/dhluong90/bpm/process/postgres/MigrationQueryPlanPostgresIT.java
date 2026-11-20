package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Query-plan check of the migration dry run's reads (PRE-014, PRE-036, TR-PERF-07): on a seeded database every
 * registered list query of {@link ProcessListQueries} and every count of {@link JdbcMigrationStore} is answered by
 * an index that leads with the tenant, never by a sequential scan of {@code pre_token} or {@code pre_instance}.
 */
@Testcontainers
class MigrationQueryPlanPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final Instant T0 = Instant.parse("2027-03-01T02:00:00Z");
    private static final String V1 = "DEFPLAN000000000000000001";
    private static final String V2 = "DEFPLAN000000000000000002";
    private static final String B1 = "DEFPLAN00000000000000000B";
    private static final String[] STEPS = {"legacy-review"};

    /** Sample parameters per registered query; a query added without an entry here fails the check. */
    private static final Map<String, List<Object>> PARAMS = Map.of(
            "migration-live-nodes", List.of("bank-a", V1),
            "migration-instances-at-node", List.of("bank-a", V1, "legacy-review", 100),
            "migration-movable-instances", List.of("bank-a", V1, STEPS, 100));

    /** The indexes that may answer each statement. */
    private static final Map<String, Set<String>> INDEXES = Map.of(
            "migration-live-nodes", Set.of("idx_pre_token_live", "idx_pre_token_live_node_instance"),
            "migration-instances-at-node", Set.of("idx_pre_token_live_node_instance"),
            "migration-movable-instances", Set.of("idx_pre_instance_running_definition"),
            "running", Set.of("idx_pre_instance_running_definition"),
            "instances-at", Set.of("idx_pre_token_live", "idx_pre_token_live_node_instance"));

    static JdbcClient jdbc;

    @BeforeAll
    static void seed() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        new ProcessEngineSchemaMigrator(ds).migrate();
        jdbc = JdbcClient.create(ds);
        definition("bank-a", V1, 1, "RETIRED");
        definition("bank-a", V2, 2, "ACTIVE");
        definition("bank-b", B1, 1, "ACTIVE");
        // bank-a v1: 20,000 loans at review and 2,000 at legacy-review; v2: 30,000 finished loans; bank-b: 10,000.
        loans("bank-a", V1, "AR", 20_000, "RUNNING", "review", "WAITING_USER");
        loans("bank-a", V1, "AL", 2_000, "RUNNING", "legacy-review", "WAITING_USER");
        loans("bank-a", V2, "AD", 30_000, "COMPLETED", "end", "COMPLETED");
        loans("bank-b", B1, "BR", 10_000, "RUNNING", "review", "WAITING_USER");
        jdbc.sql("ANALYZE").update();
    }

    private static void definition(String tenant, String id, int version, String status) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                        + "definition_hash, source_format, source_document, source_hash, node_count, source, "
                        + "effective_from, created_by, created_at, approved_by, approved_at) VALUES (?, ?, 'loan-review', "
                        + "?, ?, 'Loan review', '{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<definitions/>', "
                        + "repeat('b', 64), 5, 'IMPORT', ?, 'lan', ?, 'minh', ?)")
                .params(tenant, id, version, status, Timestamp.from(T0), Timestamp.from(T0), Timestamp.from(T0))
                .update();
    }

    /** {@code n} instances, each with a root scope token and one flow token at {@code node} in {@code status}. */
    private static void loans(String tenant, String definitionId, String prefix, int n, String instanceStatus,
            String node, String tokenStatus) {
        jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                        + "variables, started_at) SELECT ?, ? || lpad(g::text, 24, '0'), ?, 'loan-review', "
                        + "(SELECT version_num FROM pre_definition WHERE tenant_id = ? AND id = ?), ?, '{}', now() "
                        + "FROM generate_series(1, ?) g")
                .params(tenant, prefix, definitionId, tenant, definitionId, instanceStatus, n).update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, current_node_id, status, "
                        + "entered_at, created_at) SELECT ?, 'S' || ? || lpad(g::text, 23, '0'), ? || lpad(g::text, 24, '0'), "
                        + "?, 'SCOPE', 'loan-review', 'WAITING_SCOPE', now(), now() FROM generate_series(1, ?) g")
                .params(tenant, prefix, prefix, definitionId, n).update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                        + "current_node_id, status, entered_at, created_at) SELECT ?, 'F' || ? || lpad(g::text, 23, '0'), "
                        + "? || lpad(g::text, 24, '0'), ?, 'FLOW', 'S' || ? || lpad(g::text, 23, '0'), ?, ?, now(), now() "
                        + "FROM generate_series(1, ?) g")
                .params(tenant, prefix, prefix, definitionId, prefix, node, tokenStatus, n).update();
    }

    /** Every node of a JSON plan with its type, relation and index. */
    private static List<String> nodes(String sql, List<?> params) {
        String json = jdbc.sql("EXPLAIN (FORMAT JSON) " + sql).params(params).query(String.class).single();
        List<String> out = new ArrayList<>();
        walk(EngineJson.JSON.readTree(json).get(0).get("Plan"), out);
        return out;
    }

    private static void walk(JsonNode plan, List<String> out) {
        out.add(plan.path("Node Type").asString() + " " + plan.path("Relation Name").asString("") + " "
                + plan.path("Index Name").asString(""));
        for (JsonNode child : plan.path("Plans")) {
            walk(child, out);
        }
    }

    private static void assertIndexed(String name, String sql, List<?> params) {
        List<String> plan = nodes(sql, params);
        assertThat(plan).as(name + " plan " + plan).noneMatch(n -> n.startsWith("Seq Scan pre_token")
                || n.startsWith("Seq Scan pre_instance"));
        assertThat(plan).as(name + " plan " + plan).anyMatch(n -> INDEXES.get(name).stream().anyMatch(n::endsWith));
    }

    @Test
    void PRE_014_every_registered_dry_run_query_is_answered_by_an_index_on_a_seeded_database() {
        assertThat(ProcessListQueries.all().stream().map(ListQuery::name).collect(Collectors.toSet()))
                .as("every registered query has sample parameters here").isEqualTo(PARAMS.keySet());
        for (ListQuery query : ProcessListQueries.all()) {
            assertIndexed(query.name(), query.sql(), PARAMS.get(query.name()));
        }
    }

    @Test
    void PRE_014_the_dry_run_counts_are_answered_by_an_index_on_a_seeded_database() {
        assertIndexed("running", JdbcMigrationStore.RUNNING_SQL, List.of("bank-a", V1));
        assertIndexed("instances-at", JdbcMigrationStore.INSTANCES_AT_SQL, List.of("bank-a", V1, STEPS));
    }

    @Test
    void PRE_014_the_dry_run_reads_return_the_seeded_rows_of_one_tenant_and_version() {
        JdbcMigrationStore store = new JdbcMigrationStore(dataSource());

        assertThat(store.liveTokensByNode("bank-a", V1)).isEqualTo(Map.of("review", 20_000L, "legacy-review", 2_000L));
        assertThat(store.runningInstances("bank-a", V1)).isEqualTo(22_000);
        assertThat(store.runningInstances("bank-a", V2)).isZero();
        assertThat(store.instancesAt("bank-a", V1, List.of("legacy-review"))).isEqualTo(2_000);
        assertThat(store.instanceIdsAt("bank-a", V1, "legacy-review", 3)).containsExactly(
                "AL000000000000000000000001", "AL000000000000000000000002", "AL000000000000000000000003");
        assertThat(store.movableInstanceIds("bank-a", V1, List.of("legacy-review"), 2)).containsExactly(
                "AR000000000000000000000001", "AR000000000000000000000002");
        assertThat(store.liveTokensByNode("bank-b", V1)).as("another tenant's version").isEmpty();
    }

    private static javax.sql.DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
