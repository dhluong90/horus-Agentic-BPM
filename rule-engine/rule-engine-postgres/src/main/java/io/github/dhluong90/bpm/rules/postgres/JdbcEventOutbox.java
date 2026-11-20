package io.github.dhluong90.bpm.rules.postgres;

import io.github.dhluong90.bpm.rules.internal.store.EventOutbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Writes {@code PENDING} rows to {@code rle_decision_outbox} in the caller's transaction. The payload
 * is the event with its full envelope (DESIGN section 3.3); its {@code eventId} is the row id. The relay
 * comes later.
 */
public final class JdbcEventOutbox implements EventOutbox {

    private final JdbcClient jdbc;

    /**
     * Creates the outbox on the host's data source.
     *
     * @param dataSource the host's data source
     */
    public JdbcEventOutbox(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public void append(String tenantId, String id, String payloadJson, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO rle_decision_outbox (tenant_id, id, payload, status, created_at)
                VALUES (:tenant, :id, CAST(:payload AS jsonb), 'PENDING', :at)
                """)
                .param("tenant", tenantId).param("id", id).param("payload", payloadJson)
                .param("at", Timestamp.from(createdAt)).update();
    }
}
