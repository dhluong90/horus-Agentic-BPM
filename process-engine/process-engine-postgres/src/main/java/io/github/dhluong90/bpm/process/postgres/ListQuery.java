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
    }
}
