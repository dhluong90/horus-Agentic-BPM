package io.github.dhluong90.bpm.rules.postgres.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * The index of the pending approvals list (#31, RLE-190): the versions of a tenant waiting for a second person, oldest
 * request first, keyset on {@code (requested_at, id)}. The index is partial, so it holds only the versions that wait
 * and stays small however many versions a tenant has published.
 *
 * <p>The index is built CONCURRENTLY (TR-OPS-02), which cannot run inside a transaction, so this migration runs
 * outside one ({@link #canExecuteInTransaction()} is false) and each statement commits alone, as V20 and V24 do. It
 * only adds an index: no column, no data and no behaviour of a replica on the release before this one changes. An
 * interrupted build leaves an INVALID index under its name; a re-run drops it CONCURRENTLY and builds it again.
 *
 * <p>Forward-only. Rollback, if ever needed, is a new forward migration:
 * {@code DROP INDEX CONCURRENTLY IF EXISTS idx_rle_version_pending}.
 */
public final class V30__rle_version_pending_index extends BaseJavaMigration {

    /** Name of the index. */
    public static final String INDEX = "idx_rle_version_pending";

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        apply(context.getConnection());
    }

    /**
     * Applies the migration. Idempotent.
     *
     * @param connection a connection; it is switched to auto-commit, because CONCURRENTLY cannot run in a transaction
     * @throws SQLException when a statement fails; a half-built index stays INVALID and the next run replaces it
     */
    public static void apply(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("SET lock_timeout = '5s'");
            if (invalid(connection)) {
                st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX);
            }
            st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + INDEX + " ON rle_version"
                    + " (tenant_id, requested_at, id) WHERE status = 'PENDING_APPROVAL'");
        } finally {
            try (Statement reset = connection.createStatement()) {
                reset.execute("RESET lock_timeout");
            }
            connection.setAutoCommit(autoCommit);
        }
    }

    private static boolean invalid(Connection c) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_index WHERE indexrelid = to_regclass(?) AND NOT indisvalid)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, INDEX);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
