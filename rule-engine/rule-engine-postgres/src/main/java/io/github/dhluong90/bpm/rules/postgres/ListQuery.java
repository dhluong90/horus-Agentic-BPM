package io.github.dhluong90.bpm.rules.postgres;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A statement that returns more than one row, with the bound that keeps it small on a large bank database
 * (ADR-0015, {@code RLE-085}). Every multi-row read of this module is one of these, so it is registered in
 * {@link RuleListQueries} and checked in CI: an ArchUnit rule refuses a multi-row read anywhere else, and the
 * query-plan check proves each plan is served by an index.
 *
 * <p>The canonical constructor checks the SQL against its bound, so a query whose text cannot keep its promise
 * does not load. Statements use named parameters ({@code :name}).
 *
 * @param name a stable name, shown in a failing check
 * @param bound what limits the number of rows
 * @param sql the statement, with named parameters
 */
record ListQuery(String name, Bound bound, String sql) {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern LIMIT_PARAMETER = Pattern.compile("\\bORDER BY\\b.+\\bLIMIT :\\w+", FLAGS);
    private static final Pattern TENANT = Pattern.compile("\\btenant_id = :\\w+", FLAGS);
    private static final Pattern PERIOD = Pattern.compile("\\bcreated_at >= :\\w+.+\\bcreated_at < :\\w+", FLAGS);

    /** Checks the SQL against the bound. */
    ListQuery {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(sql, "sql");
        switch (bound) {
            case Bound.Page page -> {
                requireTenant(name, sql, "a page");
                requireLimit(name, sql);
            }
            case Bound.PartitionedPage page -> {
                requireTenant(name, sql, "a partitioned page");
                requireLimit(name, sql);
                if (!PERIOD.matcher(sql).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares a partitioned page but has no "
                            + "period on the partition key, created_at >= :from AND created_at < :to: " + sql);
                }
            }
            case Bound.ParentKey parent -> {
                requireTenant(name, sql, "the parent key " + parent.column());
                if (!Pattern.compile("\\b" + parent.column() + " = :\\w+", FLAGS).matcher(sql).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares the parent key "
                            + parent.column() + " but does not filter tenant_id = :x and " + parent.column()
                            + " = :y: " + sql);
                }
            }
            case Bound.KeySet keys -> {
                requireTenant(name, sql, "the key set " + keys.column());
                if (!Pattern.compile("\\b" + keys.column() + " IN \\(:\\w+\\)", FLAGS).matcher(sql).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares the key set "
                            + keys.column() + " but does not filter " + keys.column() + " IN (:keys): " + sql);
                }
            }
            case Bound.Sweep sweep -> {
                if (sweep.why().isBlank()) {
                    throw new IllegalArgumentException("List query " + name + " is a sweep and must say why");
                }
            }
        }
    }

    private static void requireTenant(String name, String sql, String bound) {
        if (!TENANT.matcher(sql).find()) {
            throw new IllegalArgumentException("List query " + name + " declares " + bound
                    + " but does not filter tenant_id = :tenant: " + sql);
        }
    }

    private static void requireLimit(String name, String sql) {
        if (!LIMIT_PARAMETER.matcher(sql).find()) {
            throw new IllegalArgumentException("List query " + name + " declares a page bound but has no "
                    + "ORDER BY ... LIMIT :limit: " + sql);
        }
    }

    /**
     * Named parameters from name and value pairs; a value may be {@code null}, which {@code Map.of} refuses.
     *
     * @param pairs name, value, name, value and so on
     * @return the parameters, in order
     */
    static Map<String, Object> params(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("Parameters are name and value pairs");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            params.put((String) pairs[i], pairs[i + 1]);
        }
        return params;
    }

    /**
     * Runs the query; the one place in this module that reads more than one row.
     *
     * @param jdbc the client on the host's data source
     * @param params the values of the named parameters
     * @param mapper maps one row
     * @param <T> the row type
     * @return the rows
     */
    <T> List<T> list(JdbcClient jdbc, Map<String, ?> params, RowMapper<T> mapper) {
        return jdbc.sql(sql).params(params).query(mapper).list();
    }

    /** What limits the rows of a {@link ListQuery}. */
    sealed interface Bound {

        /**
         * A page of one tenant: {@code ORDER BY} on an index, then {@code LIMIT :limit}.
         *
         * @param why which index orders the page, shown in a failing check
         */
        record Page(String why) implements Bound {
        }

        /**
         * A page of one tenant over a period of the partitioned decision log: the period is on the partition key, so
         * the plan reads no more monthly partitions than the period spans, and never the default one.
         *
         * @param why which index orders the page, shown in a failing check
         */
        record PartitionedPage(String why) implements Bound {
        }

        /**
         * The rows of one parent of one tenant, such as the history of one version, through an index on
         * {@code (tenant_id, column)}. The parent's own size limit bounds the rows.
         *
         * @param column the parent key column, which the plan's index condition must name
         * @param why what limits the rows of one parent, shown in a failing check
         */
        record ParentKey(String column, String why) implements Bound {

            /** Checks the column name. */
            public ParentKey {
                requireColumn(column);
            }
        }

        /**
         * The rows of a set of keys the caller names, of one tenant, found through an index that leads with the
         * tenant and holds the key. The caller bounds the set.
         *
         * @param column the key column, which the plan's index condition must name
         * @param why what limits the set, shown in a failing check
         */
        record KeySet(String column, String why) implements Bound {

            /** Checks the column name. */
            public KeySet {
                requireColumn(column);
            }
        }

        /**
         * A read across tenants that runs at start-up or from a maintenance sweep, never for a request: a small
         * configuration table, or the versions in service. It declares why it is bounded; the plan check only runs
         * {@code EXPLAIN} for it and allows a full scan.
         *
         * @param why what keeps the rows few, shown in a failing check
         */
        record Sweep(String why) implements Bound {
        }

        private static void requireColumn(String column) {
            if (!column.matches("[a-z_]+") || column.equals("tenant_id")) {
                throw new IllegalArgumentException("A key is a column other than tenant_id, was " + column);
            }
        }
    }

    @Override
    public String toString() {
        return name + " (" + bound.getClass().getSimpleName().toLowerCase(Locale.ROOT) + ")";
    }
}
