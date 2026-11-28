package io.github.dhluong90.bpm.rules.postgres;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads how far the host's relay is behind the event outbox {@code rle_decision_outbox}: the rows still
 * {@code PENDING} or {@code CLAIMED}, and the age of the oldest one. The engine writes the rows in the transaction of
 * the state change and keeps no relay of its own, so a growing age means the host's relay is down, stuck or absent.
 * Feeds {@code horus_rle_outbox_pending} and {@code horus_rle_outbox_oldest_age_seconds}. Read-only, in a transaction
 * of its own, over the partial index {@code idx_rle_outbox_poll}, bounded by a 2 s statement timeout. The result
 * names no tenant, event or payload.
 */
public final class EventOutboxLag {

    /** The count stops here, so a long backlog never makes the meter itself slow. */
    public static final int COUNT_CAP = 100_000;

    private final JdbcClient jdbc;
    private final RuleEngineTransactions transactions;

    /**
     * Creates the reader on the host's data source and transactions.
     *
     * @param dataSource the host's data source
     * @param transactions the engine's access to the host's transaction manager
     */
    public EventOutboxLag(DataSource dataSource, RuleEngineTransactions transactions) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /**
     * Counts the rows the relay has not marked {@code SENT}, up to {@link #COUNT_CAP}.
     *
     * @return the number of unsent rows, at most {@link #COUNT_CAP}
     */
    public long pending() {
        return transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
            return jdbc.sql("SELECT count(*) FROM (SELECT 1 FROM rle_decision_outbox "
                    + "WHERE status IN ('PENDING', 'CLAIMED') LIMIT " + COUNT_CAP + ") unsent")
                    .query(Long.class).single();
        });
    }

    /**
     * Returns the age of the oldest unsent row.
     *
     * @param now the instant to measure from, normally the host's clock
     * @return whole seconds, 0 when it is not older than now, empty when nothing waits
     */
    public OptionalLong oldestAgeSeconds(Instant now) {
        Instant oldest = transactions.inNewTransaction(status -> {
            jdbc.sql("SET LOCAL statement_timeout = '2s'").update();
            return jdbc.sql("SELECT min(created_at) FROM rle_decision_outbox WHERE status IN ('PENDING', 'CLAIMED')")
                    .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant())
                    .optional().orElse(null);
        });
        if (oldest == null) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0, Duration.between(oldest, now).toSeconds()));
    }
}
