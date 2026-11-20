package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The query-plan check of the registered bounded queries (PRE-036, DESIGN section 8.1): {@code EXPLAIN} of each with
 * representative parameters shows no sequential scan, so each is served by the index its entry declares. A statement
 * that is declared as a constant of {@link ProcessListQueries} but not registered fails here, so a query cannot be
 * added without a bound.
 */
class ProcessListQueriesPlanIT {

    private final DataSource ds = TestEngine.dataSource();

    /**
     * A seeded database as the check requires: 200,000 history rows of two tenants written in time order over two
     * days, as a running engine writes them, and analysed, so the planner sees a time column that follows the physical
     * order and chooses the BRIN index over a full scan.
     */
    @BeforeEach
    void seed() {
        TestEngine.reset();
        JdbcClient jdbc = JdbcClient.create(ds);
        jdbc.sql("INSERT INTO pre_history (tenant_id, occurred_at, id, instance_id, definition_id, event_type, node_id, "
                + "token_id, details) SELECT CASE WHEN g % 2 = 0 THEN 'bank-a' ELSE 'bank-b' END, "
                + "timestamptz '2026-11-19 00:00:00+00' + g * interval '1 second' * (172800.0 / 200000), "
                + "'H' || lpad(g::text, 25, '0'), 'I' || lpad((g / 10)::text, 25, '0'), 'D0000000000000000000000001', "
                + "CASE WHEN g % 2 = 0 THEN 'NODE_ENTERED' ELSE 'NODE_COMPLETED' END, 'approve', "
                + "'T' || lpad((g / 10)::text, 25, '0'), '{}'::jsonb FROM generate_series(1, 200000) g ORDER BY g").update();
        jdbc.sql("ANALYZE pre_history").update();
    }

    private String plan(ProcessListQueries.Registered query) {
        List<Object> params = new ArrayList<>();
        for (Object p : query.sampleParameters().get()) {
            params.add(p instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : p);
        }
        return new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status -> {
            JdbcClient tx = JdbcClient.create(ds);
            // The tables are small in a test, so the planner is told to avoid a sequential scan: a query with no
            // usable index then shows one.
            tx.sql("SET LOCAL enable_seqscan = off").update();
            return String.join("\n", tx.sql("EXPLAIN " + query.sql()).params(params).query(String.class).list());
        });
    }

    @Test
    void PRE_036_list_queries_pass_query_plan_check() {
        for (ProcessListQueries.Registered query : ProcessListQueries.all()) {
            assertThat(query.bound()).as("%s declares its bound", query.name()).isNotBlank();
            assertThat(plan(query)).as("plan of %s: %s", query.name(), query.bound()).doesNotContain("Seq Scan");
        }
    }

    @Test
    void PRE_036_the_rollup_slice_reads_history_by_its_time_index() {
        ProcessListQueries.Registered slice = ProcessListQueries.all().stream()
                .filter(q -> q.name().equals("rollup-slice")).findFirst().orElseThrow();

        assertThat(plan(slice)).contains("idx_pre_history_time_brin");
    }

    @Test
    void PRE_036_every_sql_constant_of_the_registry_is_registered_with_a_bound() throws IllegalAccessException {
        Set<String> registered = ProcessListQueries.all().stream().map(ProcessListQueries.Registered::sql)
                .collect(Collectors.toSet());
        for (Field field : ProcessListQueries.class.getDeclaredFields()) {
            if (field.getType() == String.class && Modifier.isStatic(field.getModifiers())
                    && field.getName().endsWith("_SQL")) {
                field.setAccessible(true);
                assertThat(registered).as("%s is registered", field.getName()).contains((String) field.get(null));
            }
        }
    }
}
