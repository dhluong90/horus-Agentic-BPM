package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rewrites encrypted instance variables under the keyring's current key, one tenant and one bounded page at a time
 * (PRE-526). Run it after a key rotation, repeating with the returned {@code lastId} until {@code scanned} is below the
 * page size, then drop the old key once {@link Page#refused()} is zero and a later run rewrites nothing.
 *
 * <p>Every page runs in a transaction of its own, or in the caller's when one is active, so the row locks live until
 * the page is written and a crash loses only that page: the next call with the same {@code afterId} repeats it, and a
 * second run rewrites nothing that is current. Each row is locked {@code FOR UPDATE SKIP LOCKED}, so a running instance
 * is never waited for: it is skipped and re-encrypts itself under the current key on its own next write. The rewrite is
 * also compare-and-set on the value that was read, so it can never overwrite a write that got in between. A per-tenant
 * transaction advisory lock keeps two sweepers of one tenant apart: the second gets {@link Page#busy()} and touches
 * nothing. The rewrite changes only the ciphertext; the instance state, {@code lock_version} and every ETag stay as
 * they were. A row whose key the keyring does not hold is counted as refused and left untouched, so one lost key never
 * blocks the other rows. It writes the tenant and the counts to the log, never a value.
 *
 * <p>The sweep covers {@code pre_instance.variables}. The other stores that seal a value (tokens, step results,
 * compensation snapshots, jobs, history members and so on) keep the key they were written under: keep an earlier key in
 * the keyring while any of them still holds a row written under it.
 */
public final class JdbcVariableReencryption {

    private static final System.Logger LOG = System.getLogger(JdbcVariableReencryption.class.getName());
    /** The most rows one call scans. */
    public static final int MAX_PAGE = 500;
    private static final String LOCK_NAMESPACE = "horus-pre-variable-reencryption/";

    private final JdbcClient jdbc;
    private final VariableVault vault;
    private final TransactionTemplate transaction;

    /**
     * @param dataSource the host's data source
     * @param vault the vault with the keyring to rewrite under
     */
    public JdbcVariableReencryption(DataSource dataSource, VariableVault vault) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.vault = Objects.requireNonNull(vault, "vault must not be null");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /**
     * The outcome of one page.
     *
     * @param scanned rows that hold at least one encrypted variable, in the page
     * @param rewritten rows rewritten under the current key
     * @param refused rows left untouched because a key is missing or a value does not authenticate
     * @param contended rows left for the next run because a writer changed them after they were read
     * @param lastId the last instance id scanned; pass it as {@code afterId} to continue, null when the page is empty
     * @param busy true when another sweeper of this tenant was running and nothing was done; try again later
     */
    public record Page(int scanned, int rewritten, int refused, int contended, String lastId, boolean busy) {
    }

    /**
     * Rewrites one page.
     *
     * @param tenant the tenant; never taken from a request body
     * @param afterId the last id of the previous page, or null for the first
     * @param limit the page size, 1 to {@value #MAX_PAGE}
     * @return the counts and the cursor
     */
    public Page reencrypt(String tenant, String afterId, int limit) {
        if (tenant == null || tenant.isBlank() || limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("A tenant and a page size of 1 to " + MAX_PAGE + " are required");
        }
        return transaction.execute(status -> page(tenant, afterId, limit));
    }

    private Page page(String tenant, String afterId, int limit) {
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")
                .params(LOCK_NAMESPACE + tenant).query(Boolean.class).single();
        if (!locked) {
            LOG.log(System.Logger.Level.INFO, "Variable re-encryption for tenant {0}: another sweeper is running",
                    tenant);
            return new Page(0, 0, 0, 0, afterId, true);
        }
        record Row(String id, String variables) {
        }
        List<Row> rows = ProcessListQueries.REENCRYPT_INSTANCES.list(jdbc,
                List.of(tenant, afterId == null ? "" : afterId, limit),
                (rs, n) -> new Row(rs.getString("id"), rs.getString("variables")));
        int rewritten = 0;
        int refused = 0;
        int contended = 0;
        for (Row row : rows) {
            try {
                String next = vault.reencrypt(tenant, row.id(), row.variables());
                if (next != null) {
                    int updated = jdbc.sql("UPDATE pre_instance SET variables = ?::jsonb WHERE tenant_id = ? AND id = ? "
                                    + "AND variables = ?::jsonb")
                            .params(next, tenant, row.id(), row.variables()).update();
                    if (updated == 1) {
                        rewritten++;
                    } else {
                        contended++;
                    }
                }
            } catch (ProcessException e) {
                refused++;
                LOG.log(System.Logger.Level.WARNING, "Variable re-encryption refused an instance of tenant {0}: {1}",
                        tenant, e.code());
            }
        }
        String lastId = rows.isEmpty() ? null : rows.get(rows.size() - 1).id();
        LOG.log(System.Logger.Level.INFO,
                "Variable re-encryption for tenant {0}: scanned {1}, rewritten {2}, refused {3}, contended {4}",
                tenant, rows.size(), rewritten, refused, contended);
        return new Page(rows.size(), rewritten, refused, contended, lastId, false);
    }
}
