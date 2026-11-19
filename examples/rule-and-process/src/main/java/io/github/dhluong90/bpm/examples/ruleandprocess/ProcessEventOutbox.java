package io.github.dhluong90.bpm.examples.ruleandprocess;

import io.github.dhluong90.bpm.process.spi.ProcessEventPublisher;
import java.time.ZoneOffset;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two kinds of listener of the Process Engine's listener contract.
 * <ul>
 *   <li>{@link #record} runs inside the engine transaction and writes the host's outbox row, keyed by the event's
 *       {@code eventId} and scoped by {@code event.tenantId()}, so the event commits or rolls back with the state
 *       change and a relay can deduplicate. It does no network I/O; an exception would roll the step back.</li>
 *   <li>{@link #notifyAfterCommit} runs after commit only, at most once, and never for rolled-back work. If it wrote
 *       to the database, it would need its own transaction ({@code REQUIRES_NEW}): a {@code REQUIRED} write there
 *       joins the finished transaction and is never committed.</li>
 * </ul>
 * Payloads carry no instance variables, but {@code businessKey} and the assignee may be personal data: the outbox
 * keeps the retention and masking rules of the engine's instance data.
 */
@Component
class ProcessEventOutbox {

    private static final Log LOG = LogFactory.getLog(ProcessEventOutbox.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    ProcessEventOutbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void record(ProcessEventPublisher.Event event) {
        Object businessKey = event.payload().get("businessKey");
        jdbc.sql("INSERT INTO event_outbox (tenant_id, event_id, event_type, business_key, payload, occurred_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (tenant_id, event_id) DO NOTHING")
                .params(event.tenantId(), event.eventId(), event.type(),
                        businessKey == null ? null : String.valueOf(businessKey), JSON.writeValueAsString(event.payload()),
                        event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void notifyAfterCommit(ProcessEventPublisher.Event event) {
        LOG.info("process event " + event.type() + " committed for tenant " + event.tenantId());
    }
}
