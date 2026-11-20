package io.github.dhluong90.bpm.rules.postgres.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Rule set events in the lifecycle history (PD-116, #118): an evidence export is recorded once for the rule set, not
 * for one version, so {@code rle_version_history} gains rows without a version. Such a row carries the rule set id,
 * the action {@code EVIDENCE_EXPORTED}, the actor, the time and a JSON object {@code evidence} with the format, the
 * versions, the decision ids, the content's SHA-256 and the size of the file.
 *
 * <p>Changes, all additive:
 * <ul>
 *   <li>two nullable columns, {@code rule_set_id} (with a foreign key on tenant and rule set) and {@code evidence};</li>
 *   <li>{@code version_id}, {@code to_status} and {@code lock_version} lose NOT NULL; two checks put it back for
 *       every version row, so a version row is exactly as before;</li>
 *   <li>the action check (V3, widened by V6) gains {@code EVIDENCE_EXPORTED}, and a check ties that action to rows
 *       without a version and with the rule set and the evidence;</li>
 *   <li>the index {@code idx_rle_version_history_rule_set} on tenant, rule set and time, newest first, over rule set
 *       rows only, which serves {@code GET /rule-sets/{name}/history}.</li>
 * </ul>
 *
 * <p>Numbered V24: V21 to V23 are taken by migrations not yet on main, and main's last Rule Engine migration is V20.
 *
 * <p>Locks and order. The index is built CONCURRENTLY (TR-OPS-02), which cannot run inside a transaction, so this
 * migration runs outside one ({@link #canExecuteInTransaction()} is false) and each statement commits alone, as V20
 * does. The catalogue changes come first, each one {@code ALTER TABLE} statement under {@code lock_timeout}: adding
 * nullable columns and dropping NOT NULL change only the catalogue. The checks and the foreign key are added
 * {@code NOT VALID} in that statement and validated by later statements, which take {@code SHARE UPDATE EXCLUSIVE}
 * and so block no read or write; the table holds a few rows per version. Every existing row satisfies every new check.
 * The append-only triggers of V4 fire on row UPDATE, DELETE and TRUNCATE only, so this DDL is unaffected and they
 * stay in force for rule set rows too.
 *
 * <p>Re-run safe: every statement is idempotent ({@code IF NOT EXISTS}, constraints dropped and added in the same
 * statement), and an INVALID index left by an interrupted build is dropped CONCURRENTLY and rebuilt.
 *
 * <p>Rolling upgrade: a replica still on the release before this one writes version rows only, with every column it
 * knew, so the checks accept them; its version history reads filter by version and never see rule set rows.
 *
 * <p>Forward-only. Rollback, if ever needed, is a new forward migration. The append-only triggers refuse to delete
 * recorded exports, so it keeps them, or first exports them and disables the trigger under a documented change:
 * <pre>
 *   DROP INDEX CONCURRENTLY IF EXISTS idx_rle_version_history_rule_set;
 *   ALTER TABLE rle_version_history DROP CONSTRAINT rle_version_history_rule_set_event,
 *     DROP CONSTRAINT rle_version_history_version_event, DROP CONSTRAINT rle_version_history_rule_set_fk;
 *   -- only once no row has a null version_id:
 *   ALTER TABLE rle_version_history ALTER COLUMN version_id SET NOT NULL, ALTER COLUMN to_status SET NOT NULL,
 *     ALTER COLUMN lock_version SET NOT NULL, DROP COLUMN evidence, DROP COLUMN rule_set_id;
 * </pre>
 * The action check can stay wider; nothing writes the new action after a rollback.
 */
public final class V24__rle_version_history_rule_set_events extends BaseJavaMigration {

    /** Name of the index of rule set rows. */
    public static final String INDEX = "idx_rle_version_history_rule_set";

    static final String ACTIONS = "'CREATED','REQUESTED','APPROVED','REJECTED','WITHDRAWN','DRAFT_UPDATED',"
            + "'RETIRE_REQUESTED','RETIRE_CANCELLED','RETIRED','EVIDENCE_EXPORTED'";

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
            st.execute("""
                    ALTER TABLE rle_version_history
                      ADD COLUMN IF NOT EXISTS rule_set_id VARCHAR(26),
                      ADD COLUMN IF NOT EXISTS evidence JSONB,
                      ALTER COLUMN version_id DROP NOT NULL,
                      ALTER COLUMN to_status DROP NOT NULL,
                      ALTER COLUMN lock_version DROP NOT NULL,
                      DROP CONSTRAINT IF EXISTS rle_version_history_rule_set_fk,
                      ADD CONSTRAINT rle_version_history_rule_set_fk FOREIGN KEY (tenant_id, rule_set_id)
                        REFERENCES rle_rule_set (tenant_id, id) NOT VALID,
                      DROP CONSTRAINT rle_version_history_action_check,
                      ADD CONSTRAINT rle_version_history_action_check CHECK (action IN (%s)) NOT VALID,
                      DROP CONSTRAINT IF EXISTS rle_version_history_version_event,
                      ADD CONSTRAINT rle_version_history_version_event CHECK (version_id IS NULL OR (
                        to_status IS NOT NULL AND lock_version IS NOT NULL AND evidence IS NULL
                        AND action <> 'EVIDENCE_EXPORTED')) NOT VALID,
                      DROP CONSTRAINT IF EXISTS rle_version_history_rule_set_event,
                      ADD CONSTRAINT rle_version_history_rule_set_event CHECK (version_id IS NOT NULL OR (
                        action = 'EVIDENCE_EXPORTED' AND rule_set_id IS NOT NULL AND evidence IS NOT NULL
                        AND jsonb_typeof(evidence) = 'object' AND from_status IS NULL AND to_status IS NULL
                        AND lock_version IS NULL)) NOT VALID
                    """.formatted(ACTIONS));
            st.execute("ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_rule_set_fk");
            st.execute("ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_action_check");
            st.execute("ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_version_event");
            st.execute("ALTER TABLE rle_version_history VALIDATE CONSTRAINT rle_version_history_rule_set_event");
            if (invalid(connection)) {
                st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX);
            }
            st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + INDEX + " ON rle_version_history"
                    + " (tenant_id, rule_set_id, occurred_at DESC, id DESC) WHERE rule_set_id IS NOT NULL");
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
