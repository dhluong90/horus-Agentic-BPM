package io.github.dhluong90.bpm.process.postgres;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.dhluong90.bpm.process.postgres.ListQuery.Bound;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code PRE-036}, without a database: every multi-row read of the store is a registered {@link ListQuery} with a
 * declared bound, and the SQL of each keeps the bound it declares. The plans are checked by
 * {@link ListQueryPlanCheckTest}.
 */
class BoundedListQueriesTest {

    /** The {@code JdbcClient} calls that return every row a statement finds. */
    private static final Set<String> MULTI_ROW = Set.of("list", "stream", "set", "listOfRows", "rowSet");

    /** Only {@link ListQuery#list} reads more than one row through {@code JdbcClient}. */
    static final ArchRule ONLY_REGISTERED_LIST_QUERIES = noClasses()
            .that().doNotHaveFullyQualifiedName(ListQuery.class.getName())
            .should().callMethodWhere(new DescribedPredicate<JavaMethodCall>("a multi-row read of JdbcClient") {
                @Override
                public boolean test(JavaMethodCall call) {
                    String owner = call.getTargetOwner().getName();
                    return (owner.equals(JdbcClient.MappedQuerySpec.class.getName())
                            || owner.equals(JdbcClient.ResultQuerySpec.class.getName()))
                            && MULTI_ROW.contains(call.getName());
                }
            })
            .because("a list query must be registered in ProcessListQueries with its bound, so the query-plan "
                    + "check covers it (PRE-036)");

    /** Only the registry builds list queries, so {@link ProcessListQueries#all()} holds every one. */
    static final ArchRule ONLY_THE_REGISTRY_BUILDS_LIST_QUERIES = noClasses()
            .that().doNotHaveFullyQualifiedName(ProcessListQueries.class.getName())
            .and().doNotHaveFullyQualifiedName(ListQuery.class.getName())
            .should().callConstructorWhere(new DescribedPredicate<JavaConstructorCall>("the ListQuery constructor") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    return call.getTargetOwner().getName().equals(ListQuery.class.getName());
                }
            })
            .because("the query-plan check reads the registry; a query built elsewhere would escape it (PRE-036)");

    private static JavaClasses store() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.process.postgres");
    }

    @Test
    void PRE_036_every_multi_row_read_of_the_store_is_a_registered_list_query() {
        ONLY_REGISTERED_LIST_QUERIES.check(store());
        ONLY_THE_REGISTRY_BUILDS_LIST_QUERIES.check(store());
    }

    @Test
    void PRE_036_an_unregistered_group_by_on_pre_history_fails_the_build() {
        // Given a store class that reads a GROUP BY over pre_history without the registry
        JavaClasses offending = new ClassFileImporter().importClasses(UnregisteredHistoryRead.class);

        // Then the rule fails and names the class and the call
        assertThatThrownBy(() -> ONLY_REGISTERED_LIST_QUERIES.check(offending))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnregisteredHistoryRead.eventTypeCounts")
                .hasMessageContaining("JdbcClient$MappedQuerySpec.list()");
    }

    @Test
    void PRE_036_a_page_query_without_order_by_and_limit_does_not_load() {
        assertThatThrownBy(() -> new ListQuery("instances-unbounded",
                new Bound.Page("newest first"), "SELECT id FROM pre_instance WHERE tenant_id = ? ORDER BY started_at DESC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instances-unbounded")
                .hasMessageContaining("LIMIT ?");
        assertThatThrownBy(() -> new ListQuery("instances-fixed-limit",
                new Bound.Page("newest first"), "SELECT id FROM pre_instance WHERE tenant_id = ? LIMIT 100000"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PRE_036_a_parent_key_query_must_filter_tenant_and_its_key() {
        assertThatThrownBy(() -> new ListQuery("tokens-of-tenant",
                new Bound.ParentKey("instance_id", "tokens of one instance"),
                "SELECT id FROM pre_token WHERE tenant_id = ? ORDER BY id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance_id = ?");
        assertThatThrownBy(() -> new Bound.ParentKey("tenant_id", "a tenant is not a parent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PRE_036_a_parent_keys_query_must_filter_tenant_and_its_keys_as_an_array() {
        assertThatThrownBy(() -> new ListQuery("history-of-instances",
                new Bound.ParentKeys("instance_id", "the instances of one page"),
                "SELECT id FROM pre_history WHERE tenant_id = ? AND instance_id IN (?, ?)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("instance_id = ANY (?)");
        assertThatThrownBy(() -> new Bound.ParentKeys("tenant_id", "a tenant is not a parent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PRE_036_an_engine_sweep_must_carry_a_time_range_a_limit_or_a_single_count() {
        assertThatThrownBy(() -> new ListQuery("history-of-everyone",
                new Bound.EngineSweep("occurred_at", "one slice"),
                "SELECT id FROM pre_history WHERE occurred_at >= ?"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurred_at < ?");
        assertThatThrownBy(() -> new ListQuery("instances-of-everyone",
                new Bound.EngineSweep(null, "claimed: a gauge"),
                "SELECT status, count(*) FROM pre_instance WHERE status = 'RUNNING' GROUP BY status"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single count");
        assertThatThrownBy(() -> new Bound.EngineSweep("tenant_id", "a tenant is not a time column"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void PRE_036_the_registry_holds_every_list_query_with_a_unique_name() {
        List<String> names = ProcessListQueries.all().stream().map(ListQuery::name).toList();

        assertThat(names).doesNotHaveDuplicates().contains("live-token-counts", "lock-definition-versions",
                "definition-history", "instance-tokens", "instance-tasks", "instance-open-incidents", "instance-step-results",
                "replay-overridden-attempts", "replay-recorded-answers", "replay-recorded-answers-after", "replay-cases",
                "definition-keys", "definition-keys-after",
                "claim-timers", "claim-async-steps", "claim-async-kind", "instance-history-actors", "calendar-holidays",
                "tasks-mine-dated", "tasks-any-undated", "migration-live-nodes", "migration-instances-at-node",
                "migration-movable-instances", "instance-token-entered-at", "rollup-slice", "window-node-sums",
                "window-node-histogram", "gauge-active-instances", "gauge-open-incidents", "gauge-oldest-due-job",
                "instances-1",
                "instances-3-key-range-after");
    }

    /** A store method as someone might write it: a GROUP BY over every history row of a tenant, read in full. */
    static final class UnregisteredHistoryRead {

        List<String> eventTypeCounts(JdbcClient jdbc, String tenant) {
            return jdbc.sql("SELECT event_type || ':' || count(*) FROM pre_history WHERE tenant_id = ? GROUP BY event_type")
                    .params(tenant).query(String.class).list();
        }
    }
}
