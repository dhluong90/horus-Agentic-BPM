package com.horus.bpm.rules.postgres;

import com.horus.bpm.rules.internal.json.Hashes;
import com.horus.bpm.rules.internal.store.IdempotencyStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link IdempotencyStore} on {@code rle_idempotency} (ADR-0006).
 *
 * <p>A claim takes a transaction-scoped advisory lock on the hash of tenant, operation and key. A
 * second request with the same key while the first transaction runs does not get the lock and is
 * answered {@code IDEMPOTENCY_IN_FLIGHT} at once; after the first commits, its recorded result is
 * replayed. Must run inside a transaction, which the services guarantee.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    /**
     * Creates the store on the host's data source.
     *
     * @param dataSource the host's data source
     */
    public JdbcIdempotencyStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Claim claim(String tenantId, String operation, String key, String requestHash, Instant now) {
        boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(:lockKey)")
                .param("lockKey", lockKey(tenantId, operation, key))
                .query(Boolean.class).single();
        if (!locked) {
            return new InFlight();
        }
        var stored = jdbc.sql("""
                SELECT request_hash, status_code, response_body::text AS body, expires_at FROM rle_idempotency
                WHERE tenant_id = :tenant AND operation = :operation AND idempotency_key = :key
                """)
                .param("tenant", tenantId).param("operation", operation).param("key", key)
                .query((rs, row) -> new Stored(rs.getString("request_hash"), rs.getInt("status_code"),
                        rs.getString("body"), rs.getTimestamp("expires_at").toInstant()))
                .optional();
        if (stored.isEmpty()) {
            return new Fresh();
        }
        Stored record = stored.get();
        if (record.expiresAt().isBefore(now)) {
            jdbc.sql("DELETE FROM rle_idempotency WHERE tenant_id = :tenant AND operation = :operation AND idempotency_key = :key")
                    .param("tenant", tenantId).param("operation", operation).param("key", key).update();
            return new Fresh();
        }
        return record.requestHash().equals(requestHash) ? new Replay(record.status(), record.body()) : new Mismatch();
    }

    @Override
    public void record(String tenantId, String operation, String key, String requestHash, int statusCode,
            String responseJson, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO rle_idempotency (tenant_id, operation, idempotency_key, request_hash, status_code,
                  response_body, expires_at)
                VALUES (:tenant, :operation, :key, :hash, :status, CAST(:body AS jsonb), :expires)
                """)
                .param("tenant", tenantId).param("operation", operation).param("key", key).param("hash", requestHash)
                .param("status", statusCode).param("body", responseJson).param("expires", Timestamp.from(expiresAt))
                .update();
    }

    /** The first 64 bits of an unambiguous SHA-256 of tenant, operation and key. */
    static long lockKey(String tenantId, String operation, String key) {
        String hash = Hashes.sha256Parts("rle_idempotency", tenantId, operation, key);
        return Long.parseUnsignedLong(hash.substring(0, 16), 16);
    }

    private record Stored(String requestHash, int status, String body, Instant expiresAt) {
    }
}
