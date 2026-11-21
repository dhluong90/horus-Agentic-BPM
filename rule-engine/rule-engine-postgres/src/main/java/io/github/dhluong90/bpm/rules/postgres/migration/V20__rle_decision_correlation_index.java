package io.github.dhluong90.bpm.rules.postgres.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Find decisions by the host's case reference (RLE-070, #94, #379): {@code GET /decisions?correlationId=...} reads one
 * tenant's rows with one correlation id inside a period of at most 31 days, newest first. {@code tenant_id} leads
 * (TR-TEN-01); the period is the partition key, so the plan reads at most two monthly partitions through this index.
 *
 * <p>Numbered V20: V18 and V19 are taken by migrations not yet on main, and main's last Rule Engine migration is V17.
 *
 * <p>Built without blocking writes (TR-OPS-02). {@code rle_decision} is the most written table, so a plain
 * {@code CREATE INDEX} would hold {@code SHARE} on every partition for the whole build. CONCURRENTLY is not allowed on
 * a partitioned parent or inside a transaction, so this migration runs outside one
 * ({@link #canExecuteInTransaction()} is false, each statement commits alone, as Process Engine V9 does) and:
 * <ol>
 *   <li>creates the parent index {@code ON ONLY rle_decision}: instant, and INVALID until every partition has an
 *       attached index;</li>
 *   <li>for each existing partition, including the default one, builds a matching index with
 *       {@code CREATE INDEX CONCURRENTLY} and attaches it with {@code ALTER INDEX ... ATTACH PARTITION}; the parent
 *       turns valid when the last one is attached.</li>
 * </ol>
 * Partitions created later by {@code DecisionLogPartitions} are attached to a parent that already has the index, and
 * PostgreSQL creates and attaches the matching index on them during {@code ATTACH PARTITION}.
 *
 * <p>Re-run safe: a partition that already has an index attached to the parent is skipped, and an INVALID index left by
 * a failed or interrupted build under this migration's per-partition name is dropped CONCURRENTLY and rebuilt.
 * While the parent index is invalid the planner does not use it; the correlation search still works on its other
 * indexes, only slower.
 *
 * <p>Locks: CONCURRENTLY waits for every open transaction, and Flyway's default PostgreSQL migration lock is taken
 * inside a transaction, so the build would wait on Flyway itself until {@code lock_timeout}. RuleEngineSchemaMigrator
 * therefore sets {@code flyway.postgresql.transactional.lock=false} (the same as the Process Engine migrator): a
 * session advisory lock, which still serialises replicas and which no index build waits for. This migration takes no
 * transaction-scoped lock, so there is nothing to deadlock with. It does not
 * take the partition maintenance lock either: a partition created while it runs gets its own index at {@code ATTACH}.
 * {@code lock_timeout} is 5 s for the short catalog locks; the long builds take only {@code SHARE UPDATE EXCLUSIVE},
 * which blocks no read or write.
 *
 * <p>Forward-only. Rollback, if ever needed, is a new forward migration: {@code DROP INDEX CONCURRENTLY IF EXISTS} for
 * each partition index, then {@code DROP INDEX idx_rle_decision_correlation}. It loses nothing: an index holds no data
 * of its own.
 */
public final class V20__rle_decision_correlation_index extends BaseJavaMigration {

    /** Name of the parent index. */
    public static final String PARENT_INDEX = "idx_rle_decision_correlation";

    private static final String COLUMNS = "(tenant_id, correlation_id, created_at DESC, id DESC)"
            + " WHERE correlation_id IS NOT NULL";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        build(context.getConnection());
    }

    /**
     * Creates the parent index and builds and attaches one index per partition. Idempotent.
     *
     * @param connection a connection in auto-commit mode; CONCURRENTLY cannot run inside a transaction
     * @return the number of partition indexes this call built
     * @throws SQLException when a statement fails; a half-built index stays INVALID and the next run replaces it
     */
    public static int build(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("SET lock_timeout = '5s'");
            st.execute("CREATE INDEX IF NOT EXISTS " + PARENT_INDEX + " ON ONLY rle_decision " + COLUMNS);
            int built = 0;
            for (String partition : partitions(connection)) {
                if (attached(connection, partition)) {
                    continue;
                }
                // The names come from pg_class (digits and letters of rle_decision_YYYY_MM or _default), never from a caller.
                String index = partition + "_correlation_idx";
                if (invalid(connection, index)) {
                    st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + index);
                }
                st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + index + " ON " + partition + " " + COLUMNS);
                st.execute("ALTER INDEX " + PARENT_INDEX + " ATTACH PARTITION " + index);
                built++;
            }
            return built;
        } finally {
            try (Statement reset = connection.createStatement()) {
                reset.execute("RESET lock_timeout");
            }
            connection.setAutoCommit(autoCommit);
        }
    }

    private static List<String> partitions(Connection c) throws SQLException {
        String sql = "SELECT child.relname FROM pg_inherits i JOIN pg_class child ON child.oid = i.inhrelid"
                + " WHERE i.inhparent = 'rle_decision'::regclass ORDER BY child.relname";
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /** True when the partition already has a valid index attached to the parent index. */
    private static boolean attached(Connection c, String partition) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
                + " WHERE i.inhparent = to_regclass(?) AND x.indrelid = to_regclass(?) AND x.indisvalid)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, PARENT_INDEX);
            ps.setString(2, partition);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static boolean invalid(Connection c, String index) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass(?) AND NOT indisvalid)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, index);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
