package io.github.dhluong90.bpm.process.postgres;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A statement that returns more than one row, with the bound that keeps it small on a large bank database
 * (ADR-0015, {@code PRE-036}). Every multi-row read of this module is one of these, so it is registered in
 * {@link ProcessListQueries} and checked in CI: an ArchUnit rule refuses a multi-row read anywhere else, and the
 * query-plan check proves each plan is served by an index.
 *
 * <p>The canonical constructor checks the SQL against its bound, so a query whose text cannot keep its promise
 * does not load.
 *
 * @param name a stable name, shown in a failing check
 * @param bound what limits the number of rows
 * @param sql the statement, with {@code ?} placeholders
 */
record ListQuery(String name, Bound bound, String sql) {

    private static final Pattern LIMIT_PARAMETER = Pattern.compile("(?i)\\bORDER BY\\b.+\\bLIMIT \\?");
    private static final Pattern ANY_LIMIT = Pattern.compile("(?i)\\bORDER BY\\b.+\\bLIMIT (\\?|\\d+)");
    private static final Pattern SINGLE_COUNT = Pattern.compile("(?i)^SELECT count\\(\\*\\) FROM [a-z_]+ WHERE [^;]+$");

    /** Checks the SQL against the bound. */
    ListQuery {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(sql, "sql");
        switch (bound) {
            case Bound.Page page -> {
                if (!LIMIT_PARAMETER.matcher(sql).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares a page bound but has no "
                            + "ORDER BY ... LIMIT ?: " + sql);
                }
            }
            case Bound.ParentKey parent -> {
                String text = sql.toLowerCase(Locale.ROOT);
                if (!text.contains("tenant_id = ?") || !Pattern.compile("\\b" + parent.column() + " = \\?").matcher(text).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares the parent key "
                            + parent.column() + " but does not filter tenant_id = ? and " + parent.column() + " = ?: " + sql);
                }
            }
            case Bound.ParentKeys parents -> {
                String text = sql.toLowerCase(Locale.ROOT);
                if (!text.contains("tenant_id = ?")
                        || !Pattern.compile("\\b" + parents.column() + " = any \\(\\?\\)").matcher(text).find()) {
                    throw new IllegalArgumentException("List query " + name + " declares the parent keys "
                            + parents.column() + " but does not filter tenant_id = ? and " + parents.column()
                            + " = ANY (?): " + sql);
                }
            }
            case Bound.EngineSweep sweep -> {
                String text = sql.toLowerCase(Locale.ROOT);
                if (sweep.column() != null) {
                    String column = "\\b(?:[a-z]+\\.)?" + sweep.column();
                    if (!Pattern.compile(column + " >= \\?").matcher(text).find()
                            || !Pattern.compile(column + " < \\?").matcher(text).find()) {
                        throw new IllegalArgumentException("List query " + name + " declares an engine sweep over "
                                + sweep.column() + " but has no range " + sweep.column() + " >= ? AND "
                                + sweep.column() + " < ?: " + sql);
                    }
                } else if (!ANY_LIMIT.matcher(sql).find()
                        && !(SINGLE_COUNT.matcher(sql).find() && !text.contains("group by"))) {
                    throw new IllegalArgumentException("List query " + name + " declares an engine sweep without a "
                            + "time range, so it needs ORDER BY ... LIMIT or must be a single count: " + sql);
                }
            }
        }
    }

    /**
     * Runs the query; the one place in this module that reads more than one row.
     *
     * @param jdbc the client on the host's data source
     * @param params the values of the placeholders, in order
     * @param mapper maps one row
     * @param <T> the row type
     * @return the rows
     */
    <T> List<T> list(JdbcClient jdbc, List<?> params, RowMapper<T> mapper) {
        return jdbc.sql(sql).params(params).query(mapper).list();
    }

    /** What limits the rows of a {@link ListQuery}. */
    sealed interface Bound {

        /**
         * A page: {@code ORDER BY} on an index, then {@code LIMIT ?}, at most {@code query.max-page-size} rows.
         *
         * @param why which index orders the page, shown in a failing check
         */
        record Page(String why) implements Bound {
        }

        /**
         * The rows of one parent, such as the tokens of one instance, found through an index on
         * {@code (tenant_id, column)}. The parent's own size limit bounds the rows.
         *
         * @param column the parent key column, which the plan's index condition must name
         * @param why what limits the rows of one parent, shown in a failing check
         */
        record ParentKey(String column, String why) implements Bound {

            /** Checks the column name. */
            public ParentKey {
                if (!column.matches("[a-z_]+") || column.equals("tenant_id")) {
                    throw new IllegalArgumentException("A parent key is a column other than tenant_id, was " + column);
                }
            }
        }

        /**
         * The rows of a bounded set of parents, such as the history of the instances on one page, found through an
         * index on {@code (tenant_id, column)} with {@code column = ANY (?)}. The caller bounds the array, at most one
         * page; each parent's own size limit bounds its rows.
         *
         * @param column the parent key column, which the plan's index condition must name
         * @param why what bounds the parents and their rows, shown in a failing check
         */
        record ParentKeys(String column, String why) implements Bound {

            /** Checks the column name. */
            public ParentKeys {
                if (!column.matches("[a-z_]+") || column.equals("tenant_id")) {
                    throw new IllegalArgumentException("A parent key is a column other than tenant_id, was " + column);
                }
            }
        }

        /**
         * An engine-internal read across tenants, such as one slice of the statistics rollup or a metric gauge
         * ({@code PRE-036}, DESIGN section 8.1). It has no {@code tenant_id = ?}: it takes no tenant from a caller,
         * and its rows never go back to a caller. Use it only for reads the engine runs on its own schedule; a read on
         * a request path is a {@link Page} or a parent-key bound of one tenant. Its SQL either has a range on both ends of
         * {@code column}, or, when {@code column} is null, an {@code ORDER BY ... LIMIT} or a single {@code count(*)}
         * that the plan check requires to read a partial index or an index condition.
         *
         * @param column the time column whose range bounds the rows, or null for a limit or a single count
         * @param why what bounds the rows, such as the setting that caps the range, shown in a failing check
         */
        record EngineSweep(String column, String why) implements Bound {

            /** Checks the column name. */
            public EngineSweep {
                if (column != null && (!column.matches("[a-z_]+") || column.equals("tenant_id"))) {
                    throw new IllegalArgumentException("An engine sweep is bounded by a column other than tenant_id, "
                            + "was " + column);
                }
                Objects.requireNonNull(why, "why");
            }
        }
    }
}
