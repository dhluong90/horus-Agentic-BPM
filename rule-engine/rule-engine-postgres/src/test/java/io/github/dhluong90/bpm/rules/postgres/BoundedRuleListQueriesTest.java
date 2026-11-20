package io.github.dhluong90.bpm.rules.postgres;

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
import io.github.dhluong90.bpm.rules.postgres.ListQuery.Bound;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code RLE-085}, without a database: every multi-row read of the store is a registered {@link ListQuery} with a
 * declared bound, and the SQL of each keeps the bound it declares. The plans are checked by
 * {@link RuleListQueryPlanCheckTest}.
 */
class BoundedRuleListQueriesTest {

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
            .because("a list query must be registered in RuleListQueries with its bound, so the query-plan "
                    + "check covers it (RLE-085)");

    /** Only the registry builds list queries, so {@link RuleListQueries#all()} holds every one. */
    static final ArchRule ONLY_THE_REGISTRY_BUILDS_LIST_QUERIES = noClasses()
            .that().doNotHaveFullyQualifiedName(RuleListQueries.class.getName())
            .and().doNotHaveFullyQualifiedName(ListQuery.class.getName())
            .should().callConstructorWhere(new DescribedPredicate<JavaConstructorCall>("the ListQuery constructor") {
                @Override
                public boolean test(JavaConstructorCall call) {
                    return call.getTargetOwner().getName().equals(ListQuery.class.getName());
                }
            })
            .because("the query-plan check reads the registry; a query built elsewhere would escape it (RLE-085)");

    private static JavaClasses store() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dhluong90.bpm.rules.postgres");
    }

    @Test
    void RLE_085_every_multi_row_read_of_the_store_is_a_registered_list_query() {
        ONLY_REGISTERED_LIST_QUERIES.check(store());
        ONLY_THE_REGISTRY_BUILDS_LIST_QUERIES.check(store());
    }

    @Test
    void RLE_085_an_unregistered_group_by_over_the_decision_log_fails_the_build() {
        // Given a store class that reads a GROUP BY over rle_decision without the registry
        JavaClasses offending = new ClassFileImporter().importClasses(UnregisteredDecisionRead.class);

        // Then the rule fails and names the class and the call
        assertThatThrownBy(() -> ONLY_REGISTERED_LIST_QUERIES.check(offending))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("UnregisteredDecisionRead.outcomeCounts")
                .hasMessageContaining("JdbcClient$MappedQuerySpec.list()");
    }

    @Test
    void RLE_085_a_page_query_without_order_by_and_limit_does_not_load() {
        assertThatThrownBy(() -> new ListQuery("versions-unbounded", new Bound.Page("newest first"),
                "SELECT id FROM rle_version WHERE tenant_id = :tenant ORDER BY version_num DESC"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versions-unbounded")
                .hasMessageContaining("LIMIT :limit");
        assertThatThrownBy(() -> new ListQuery("versions-fixed-limit", new Bound.Page("newest first"),
                "SELECT id FROM rle_version WHERE tenant_id = :tenant ORDER BY version_num DESC LIMIT 100000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListQuery("versions-of-every-tenant", new Bound.Page("newest first"),
                "SELECT id FROM rle_version ORDER BY version_num DESC LIMIT :limit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id = :tenant");
    }

    @Test
    void RLE_085_a_decision_page_without_a_period_on_the_partition_key_does_not_load() {
        assertThatThrownBy(() -> new ListQuery("decisions-of-a-rule-set", new Bound.PartitionedPage("newest first"),
                "SELECT id FROM rle_decision WHERE tenant_id = :tenant AND rule_set_name = :ruleSet "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decisions-of-a-rule-set")
                .hasMessageContaining("created_at >= :from AND created_at < :to");
    }

    @Test
    void RLE_085_a_parent_key_or_key_set_query_must_filter_tenant_and_its_key() {
        assertThatThrownBy(() -> new ListQuery("history-of-tenant", new Bound.ParentKey("version_id", "one version"),
                "SELECT id FROM rle_version_history WHERE tenant_id = :tenant ORDER BY id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version_id = :y");
        assertThatThrownBy(() -> new ListQuery("history-of-everyone", new Bound.KeySet("version_id", "named versions"),
                "SELECT id FROM rle_version_history WHERE version_id IN (:ids)"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id = :tenant");
        assertThatThrownBy(() -> new Bound.ParentKey("tenant_id", "a tenant is not a parent"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void RLE_085_the_registry_holds_every_list_query_with_a_unique_name() {
        List<String> names = RuleListQueries.all().stream().map(ListQuery::name).toList();

        assertThat(names).doesNotHaveDuplicates().contains("cache-epochs", "rule-sets", "versions", "version-history",
                "history-versions", "versions-by-number", "history-of-versions", "rule-set-events", "declared-inputs",
                "published-masked-by-default", "retire-superseded", "masking-key-scopes", "live-version-inputs",
                "settings-seen", "decision-search", "decision-search-after", "decisions-by-logged-input",
                "decisions-by-correlation", "decisions-by-correlation-rule-set-after", "decisions-by-input",
                "decisions-by-input-after", "decision-count");
    }

    /** A store method as someone might write it: a GROUP BY over every decision of a tenant, read in full. */
    static final class UnregisteredDecisionRead {

        List<String> outcomeCounts(JdbcClient jdbc, String tenant) {
            return jdbc.sql("SELECT outcome || ':' || count(*) FROM rle_decision WHERE tenant_id = :tenant GROUP BY outcome")
                    .param("tenant", tenant).query(String.class).list();
        }
    }
}
