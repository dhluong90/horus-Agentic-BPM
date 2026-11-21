package io.github.dhluong90.bpm.process.postgres.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Time index of {@code pre_history} for the rollup (#110, DESIGN section 7): the rollup reads history in slices of
 * one minute by {@code occurred_at} across tenants, and a BRIN index over the time column finds those blocks in every
 * partition without reading the partition in full. Additive only.
 *
 * <p>Built without blocking writes (TR-OPS-02). {@code pre_history} is the most written engine table, and
 * CONCURRENTLY is not allowed on a partitioned parent or inside a transaction, so this migration runs outside one
 * ({@link #canExecuteInTransaction()} is false, each statement commits alone) and, as Rule Engine V20 does:
 * <ol>
 *   <li>creates the parent index {@code ON ONLY pre_history}: instant, and INVALID until every partition has an
 *       attached index;</li>
 *   <li>for each existing partition, the default one and every monthly partition the host created, builds a matching
 *       index with {@code CREATE INDEX CONCURRENTLY} and attaches it with {@code ALTER INDEX ... ATTACH PARTITION};
 *       the parent turns valid when the last one is attached.</li>
 * </ol>
 * A partition created later, by {@code CREATE TABLE ... PARTITION OF} or {@code ATTACH PARTITION}, gets the matching
 * index from the parent: PostgreSQL creates and attaches it.
 *
 * <p>Re-run safe: a partition that already has a valid index attached to the parent is skipped, and an INVALID index
 * left by a failed or interrupted build under this migration's per-partition name is dropped CONCURRENTLY and built
 * again. While the parent index is invalid the planner does not use it, and the rollup reads by the primary key's
 * time column, only slower.
 *
 * <p>Locks: {@code ProcessEngineSchemaMigrator} takes Flyway's lock as a session advisory lock, which no index build
 * waits for. {@code lock_timeout} is 5 s for the short catalog locks; the builds take only
 * {@code SHARE UPDATE EXCLUSIVE}, which blocks no read or write.
 *
 * <p>Only plain partitions are supported: a partition that is itself partitioned stops the migration with a message,
 * so the parent index is never left invalid without a reason.
 *
 * <p>Forward-only. Rollback, if ever needed, is a new forward migration: {@code DROP INDEX CONCURRENTLY IF EXISTS} for
 * each partition index, then {@code DROP INDEX idx_pre_history_time_brin}. It loses nothing: an index holds no data.
 */
public final class V27__pre_history_time_brin extends BaseJavaMigration {

    /** Name of the parent index. */
    public static final String PARENT_INDEX = "idx_pre_history_time_brin";

    /** Suffix of each partition's index name. */
    static final String SUFFIX = "_time_brin";

    private static final String METHOD = " USING brin (occurred_at)";
    private static final int MAX_IDENTIFIER = 63;

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
     * @param connection a connection that may be put in auto-commit mode; CONCURRENTLY cannot run in a transaction
     * @return the number of partition indexes this call built or attached
     * @throws SQLException when a statement fails; a half-built index stays INVALID and the next run replaces it
     */
    public static int build(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute("SET lock_timeout = '5s'");
            st.execute("CREATE INDEX IF NOT EXISTS " + PARENT_INDEX + " ON ONLY pre_history" + METHOD);
            int built = 0;
            for (Partition partition : partitions(connection)) {
                if (attached(connection, partition)) {
                    continue;
                }
                if (partition.kind() != 'r') {
                    throw new IllegalStateException("pre_history partition " + partition.qualified()
                            + " is not a plain table (relkind " + partition.kind() + "); index it and attach it to "
                            + PARENT_INDEX + " by hand, then run the migration again");
                }
                String index = quote(partition.schema()) + "." + quote(indexName(partition.name()));
                if (invalid(connection, partition)) {
                    st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + index);
                }
                st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + quote(indexName(partition.name())) + " ON "
                        + partition.qualified() + METHOD);
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

    /**
     * The index name of one partition: the partition name with {@link #SUFFIX}, shortened with a hash when it would
     * pass PostgreSQL's 63-byte limit, so a long host partition name never collides after truncation.
     *
     * @param partition the partition's table name
     * @return the index name, at most 63 bytes
     */
    public static String indexName(String partition) {
        String name = partition + SUFFIX;
        if (name.getBytes(StandardCharsets.UTF_8).length <= MAX_IDENTIFIER) {
            return name;
        }
        String hash = HexFormat.of().formatHex(sha256(partition), 0, 4);
        String head = partition;
        while ((head + "_" + hash + SUFFIX).getBytes(StandardCharsets.UTF_8).length > MAX_IDENTIFIER) {
            head = head.substring(0, head.length() - 1);
        }
        return head + "_" + hash + SUFFIX;
    }

    private record Partition(long oid, String schema, String name, char kind) {
        String qualified() {
            return quote(schema) + "." + quote(name);
        }
    }

    private static List<Partition> partitions(Connection c) throws SQLException {
        String sql = "SELECT child.oid, n.nspname, child.relname, child.relkind FROM pg_inherits i "
                + "JOIN pg_class child ON child.oid = i.inhrelid JOIN pg_namespace n ON n.oid = child.relnamespace "
                + "WHERE i.inhparent = 'pre_history'::regclass ORDER BY child.relname";
        List<Partition> partitions = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                partitions.add(new Partition(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4).charAt(0)));
            }
        }
        return partitions;
    }

    /** True when the partition already has a valid index attached to the parent index. */
    private static boolean attached(Connection c, Partition partition) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
                + " WHERE i.inhparent = to_regclass(?) AND x.indrelid = ?::oid AND x.indisvalid)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, PARENT_INDEX);
            ps.setLong(2, partition.oid());
            return exists(ps);
        }
    }

    /** True when this migration's index of the partition exists and is INVALID. */
    private static boolean invalid(Connection c, Partition partition) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_class ic JOIN pg_index x ON x.indexrelid = ic.oid"
                + " WHERE ic.relname = ? AND x.indrelid = ?::oid AND NOT x.indisvalid)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, indexName(partition.name()));
            ps.setLong(2, partition.oid());
            return exists(ps);
        }
    }

    private static boolean exists(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    /** Quotes an identifier read from the catalog, so a host's partition name is never read as SQL. */
    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
