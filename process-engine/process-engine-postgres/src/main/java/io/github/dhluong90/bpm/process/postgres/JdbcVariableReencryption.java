package io.github.dhluong90.bpm.process.postgres;

import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.internal.store.VariableVault;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Rewrites encrypted instance variables under the keyring's current key, one tenant and one bounded page at a time
 * (PRE-395). Run it after a key rotation, repeating with the returned {@code lastId} until {@code scanned} is below the
 * page size, then drop the old key once {@link Page#refused()} is zero and a later run rewrites nothing.
 *
 * <p>Each row is locked {@code FOR UPDATE SKIP LOCKED}, so a running instance is never waited for: it is skipped and
 * picked up by the next run, and it re-encrypts itself under the current key on its own next write. The rewrite changes
 * only the ciphertext; the instance state, {@code lock_version} and every ETag stay as they were. A row whose key the
 * keyring does not hold is counted as refused and left untouched, so one lost key never blocks the other rows. Call it
 * inside a transaction of the host; it writes the key id and the counts to the log, never a value.
 */
public final class JdbcVariableReencryption {

    private static final System.Logger LOG = System.getLogger(JdbcVariableReencryption.class.getName());
    /** The most rows one call scans. */
    public static final int MAX_PAGE = 500;

    private final JdbcClient jdbc;
    private final VariableVault vault;

    /**
     * @param dataSource the host's data source
     * @param vault the vault with the keyring to rewrite under
     */
    public JdbcVariableReencryption(DataSource dataSource, VariableVault vault) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.vault = Objects.requireNonNull(vault, "vault must not be null");
    }

    /**
     * The outcome of one page.
     *
     * @param scanned rows that hold at least one encrypted variable, in the page
     * @param rewritten rows rewritten under the current key
     * @param refused rows left untouched because a key is missing or a value does not authenticate
     * @param lastId the last instance id scanned; pass it as {@code afterId} to continue, null when the page is empty
     */
    public record Page(int scanned, int rewritten, int refused, String lastId) {
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
        record Row(String id, String variables) {
        }
        List<Row> rows = ProcessListQueries.REENCRYPT_INSTANCES.list(jdbc,
                List.of(tenant, afterId == null ? "" : afterId, limit),
                (rs, n) -> new Row(rs.getString("id"), rs.getString("variables")));
        int rewritten = 0;
        int refused = 0;
        for (Row row : rows) {
            try {
                String next = vault.reencrypt(tenant, row.id(), row.variables());
                if (next != null) {
                    jdbc.sql("UPDATE pre_instance SET variables = ?::jsonb WHERE tenant_id = ? AND id = ?")
                            .params(next, tenant, row.id()).update();
                    rewritten++;
                }
            } catch (ProcessException e) {
                refused++;
                LOG.log(System.Logger.Level.WARNING, "Variable re-encryption refused an instance of tenant {0}: {1}",
                        tenant, e.code());
            }
        }
        String lastId = rows.isEmpty() ? null : rows.get(rows.size() - 1).id();
        LOG.log(System.Logger.Level.INFO, "Variable re-encryption for tenant {0}: scanned {1}, rewritten {2}, refused {3}",
                tenant, rows.size(), rewritten, refused);
        return new Page(rows.size(), rewritten, refused, lastId);
    }
}
