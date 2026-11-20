package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.internal.service.LiveNodeCountService;
import io.github.dhluong90.bpm.process.internal.service.LiveStatsSettings;
import io.github.dhluong90.bpm.process.spi.CallerResolver;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live counts per node on PostgreSQL (PRE-028): read from {@code idx_pre_token_live} only, never from
 * {@code pre_history}, cached per replica, and never across tenants.
 */
@Testcontainers
class LiveNodeCountsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    static final Instant T0 = Instant.parse("2026-11-20T09:30:00Z");
    static final String KEY = "kyc-onboarding";

    static DataSource dataSource;
    static JdbcClient jdbc;
    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    final AtomicReference<String> tenant = new AtomicReference<>("bank-a");
    final AtomicReference<Set<String>> permissions = new AtomicReference<>(Set.of("processes:read"));
    final MutableClock clock = new MutableClock(T0);
    LiveNodeCountService counts;

    @BeforeAll
    static void schemaAndSeed() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        dataSource = recording(pg);
        new ProcessEngineSchemaMigrator(pg).migrate();
        jdbc = JdbcClient.create(pg);

        // bank-a: kyc-onboarding v1 (older instances) and v2 (the version asked for), and v3 that only bank-a has.
        definition("bank-a", "DEFA0000000000000000000001", 1);
        definition("bank-a", "DEFA0000000000000000000002", 2);
        definition("bank-a", "DEFA0000000000000000000003", 3);
        tokens("bank-a", "DEFA0000000000000000000002", "A2APP", 40, "approve", "WAITING_USER");
        tokens("bank-a", "DEFA0000000000000000000002", "A2ASD", 7, "assess-documents", "WAITING_ASYNC");
        tokens("bank-a", "DEFA0000000000000000000002", "A2DON", 500, "approve", "COMPLETED");
        tokens("bank-a", "DEFA0000000000000000000001", "A1APP", 9, "approve", "WAITING_USER");
        tokens("bank-a", "DEFA0000000000000000000003", "A3APP", 4, "approve", "WAITING_USER");
        // bank-b: the same key and version, its own definition row and its own cases.
        definition("bank-b", "DEFB0000000000000000000002", 2);
        tokens("bank-b", "DEFB0000000000000000000002", "B2APP", 3, "approve", "WAITING_USER");
        tokens("bank-b", "DEFB0000000000000000000002", "B2TMR", 2, "await-documents", "WAITING_TIMER");
        // 10,000 history rows that a history-based count would read.
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                + "details) SELECT 'bank-a', now() - (g || ' seconds')::interval, 'H' || lpad(g::text, 25, '0'), "
                + "'A2APP' || lpad((g % 40 + 1)::text, 21, '0'), 'DEFA0000000000000000000002', 'NODE_ENTERED', "
                + "'approve', '{}' FROM generate_series(1, 10000) g").update();
        jdbc.sql("ANALYZE").update();
    }

    @BeforeEach
    void service() {
        STATEMENTS.clear();
        CallerResolver callers = new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "supervisor-" + tenant.get();
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions.get();
            }
        };
        counts = new LiveNodeCountService(tenant::get, callers, clock,
                new ProcessEngineTransactions(new DataSourceTransactionManager(dataSource)), new JdbcProcessStore(dataSource),
                LiveStatsSettings.defaults());
    }

    @Test
    void PRE_028_live_node_counts_from_token_index() {
        NodeCounts first = counts.liveNodeCounts(KEY, 2);

        assertThat(first.nodes()).isEqualTo(Map.of(
                "approve", Map.of("WAITING_USER", 40L),
                "assess-documents", Map.of("WAITING_ASYNC", 7L)));
        assertThat(first.asOf()).isEqualTo(T0);
        assertThat(first.definitionKey()).isEqualTo(KEY);
        assertThat(first.version()).isEqualTo(2);
        assertThat(STATEMENTS).as("captured SQL").anyMatch(sql -> sql.contains("FROM pre_token"))
                .noneMatch(sql -> sql.contains("pre_history"))
                .allMatch(sql -> sql.startsWith("SELECT"));

        STATEMENTS.clear();
        clock.advance(Duration.ofSeconds(3));
        NodeCounts second = counts.liveNodeCounts(KEY, 2);
        assertThat(second).as("served from the cache within 5 s").isEqualTo(first);
        assertThat(second.asOf()).isEqualTo(T0);
        assertThat(STATEMENTS).as("no token read for a cached answer").noneMatch(sql -> sql.contains("pre_token"));

        clock.advance(Duration.ofSeconds(3));
        assertThat(counts.liveNodeCounts(KEY, 2).asOf()).as("read again after the TTL").isEqualTo(T0.plusSeconds(6));
    }

    @Test
    void PRE_028_the_count_query_is_answered_by_the_live_token_index_and_never_reads_history() {
        // enable_seqscan off makes the planner pick an index whenever one can answer the query; the plan then names it.
        String plan = explain();

        assertThat(plan).contains("idx_pre_token_live").doesNotContain("pre_history").doesNotContain("Seq Scan");
    }

    @Test
    void PRE_028_another_tenants_counts_are_never_visible() {
        Map<String, Long> before = rowCounts();

        NodeCounts bankA = counts.liveNodeCounts(KEY, 2);
        tenant.set("bank-b");
        NodeCounts bankB = counts.liveNodeCounts(KEY, 2);

        assertThat(bankA.nodes()).containsOnlyKeys("approve", "assess-documents");
        assertThat(bankB.nodes()).as("bank-b sees its own 3 + 2 cases, not bank-a's 47, even with bank-a's answer cached")
                .isEqualTo(Map.of("approve", Map.of("WAITING_USER", 3L), "await-documents", Map.of("WAITING_TIMER", 2L)));

        assertThatThrownBy(() -> counts.liveNodeCounts(KEY, 3))
                .as("a version that exists only in bank-a is refused for bank-b and reveals nothing")
                .isInstanceOf(DefinitionNotFoundException.class)
                .hasMessage("No definition kyc-onboarding version 3")
                .extracting("code").isEqualTo("DEFINITION_NOT_FOUND");

        assertThat(STATEMENTS).allMatch(sql -> sql.startsWith("SELECT")).allMatch(sql -> sql.contains("tenant_id = ?"));
        assertThat(rowCounts()).as("nothing is written").isEqualTo(before);
    }

    @Test
    void live_counts_need_the_read_permission() {
        permissions.set(Set.of("processes:start"));

        assertThatThrownBy(() -> counts.liveNodeCounts(KEY, 2)).isInstanceOf(PermissionDeniedException.class);
        assertThat(STATEMENTS).isEmpty();
    }

    @Test
    void a_version_above_the_large_threshold_is_cached_for_the_large_ttl() {
        counts = new LiveNodeCountService(tenant::get, new CallerResolver() {
            @Override
            public String currentCallerId() {
                return "supervisor";
            }

            @Override
            public Set<String> currentPermissions() {
                return permissions.get();
            }
        }, clock, new ProcessEngineTransactions(new DataSourceTransactionManager(dataSource)),
                new JdbcProcessStore(dataSource), new LiveStatsSettings(Duration.ofSeconds(5), Duration.ofSeconds(15), 46));

        NodeCounts first = counts.liveNodeCounts(KEY, 2);
        clock.advance(Duration.ofSeconds(10));
        assertThat(counts.liveNodeCounts(KEY, 2).asOf()).as("47 live tokens > 46: 15 s TTL").isEqualTo(first.asOf());
        clock.advance(Duration.ofSeconds(6));
        assertThat(counts.liveNodeCounts(KEY, 2).asOf()).isEqualTo(T0.plusSeconds(16));
    }

    private static String explain() {
        DataSourceTransactionManager tm = new DataSourceTransactionManager(dataSource);
        return new org.springframework.transaction.support.TransactionTemplate(tm).execute(status -> {
            JdbcClient tx = JdbcClient.create(dataSource);
            tx.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", tx.sql("EXPLAIN " + JdbcProcessStore.LIVE_COUNTS_SQL)
                    .params("bank-a", "DEFA0000000000000000000002").query(String.class).list());
        });
    }

    private static Map<String, Long> rowCounts() {
        return Map.of(
                "pre_definition", count("pre_definition"), "pre_instance", count("pre_instance"),
                "pre_token", count("pre_token"), "pre_task", count("pre_task"), "pre_history", count("pre_history"),
                "pre_idempotency", count("pre_idempotency"));
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static void definition(String tenant, String id, int version) {
        jdbc.sql("INSERT INTO pre_definition (tenant_id, id, key_name, version_num, status, name, definition_json, "
                        + "definition_hash, source_format, source_document, source_hash, node_count, source, effective_from, "
                        + "created_by, created_at, approved_by, approved_at) VALUES (?, ?, ?, ?, 'ACTIVE', 'KYC onboarding', "
                        + "'{}'::jsonb, repeat('a', 64), 'BPMN_XML', '<definitions/>', repeat('b', 64), 4, 'IMPORT', ?, "
                        + "'lan', ?, 'minh', ?)")
                .params(tenant, id, KEY, version, java.sql.Timestamp.from(T0), java.sql.Timestamp.from(T0),
                        java.sql.Timestamp.from(T0))
                .update();
    }

    /** {@code n} instances, each with a root scope token and one flow token at {@code node} in {@code status}. */
    private static void tokens(String tenant, String definitionId, String prefix, int n, String node, String status) {
        jdbc.sql("INSERT INTO pre_instance (tenant_id, id, definition_id, definition_key, definition_ver, status, "
                        + "variables, started_at) SELECT ?, ? || lpad(g::text, 21, '0'), ?, ?, "
                        + "(SELECT version_num FROM pre_definition WHERE tenant_id = ? AND id = ?), 'RUNNING', '{}', now() "
                        + "FROM generate_series(1, ?) g")
                .params(tenant, prefix, definitionId, KEY, tenant, definitionId, n).update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, current_node_id, status, "
                        + "entered_at, created_at) SELECT ?, 'S' || ? || lpad(g::text, 20, '0'), ? || lpad(g::text, 21, '0'), "
                        + "?, 'SCOPE', ?, 'WAITING_SCOPE', now(), now() FROM generate_series(1, ?) g")
                .params(tenant, prefix, prefix, definitionId, KEY, n).update();
        jdbc.sql("INSERT INTO pre_token (tenant_id, id, instance_id, definition_id, token_kind, scope_token_id, "
                        + "current_node_id, status, entered_at, created_at) SELECT ?, 'F' || ? || lpad(g::text, 20, '0'), "
                        + "? || lpad(g::text, 21, '0'), ?, 'FLOW', 'S' || ? || lpad(g::text, 20, '0'), ?, ?, now(), now() "
                        + "FROM generate_series(1, ?) g")
                .params(tenant, prefix, prefix, definitionId, prefix, node, status, n).update();
    }

    /** Records the SQL of every statement prepared through the data source. */
    private static DataSource recording(DataSource target) {
        InvocationHandler connections = (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                Connection connection = (Connection) method.invoke(target, args);
                return Proxy.newProxyInstance(LiveNodeCountsPostgresIT.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, (p, m, a) -> {
                            if ((m.getName().equals("prepareStatement") || m.getName().equals("prepareCall"))
                                    && a != null && a.length > 0 && a[0] instanceof String sql) {
                                STATEMENTS.add(sql);
                            }
                            try {
                                return m.invoke(connection, a);
                            } catch (java.lang.reflect.InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
            }
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (DataSource) Proxy.newProxyInstance(LiveNodeCountsPostgresIT.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, connections);
    }

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration duration) {
            now.updateAndGet(i -> i.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
