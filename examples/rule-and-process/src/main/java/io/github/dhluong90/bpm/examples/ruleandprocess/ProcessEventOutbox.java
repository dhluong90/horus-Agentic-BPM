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

/**
 * The two kinds of listener of the Process Engine's listener contract.
 * <ul>
 *   <li>{@link #record} runs inside the engine transaction and writes the host's outbox row, so the event commits or
 *       rolls back with the state change. It does no network I/O; an exception would roll the step back.</li>
 *   <li>{@link #notifyAfterCommit} runs after commit only, at most once, and never for rolled-back work.</li>
 * </ul>
 */
@Component
class ProcessEventOutbox {

    private static final Log LOG = LogFactory.getLog(ProcessEventOutbox.class);

    private final JdbcClient jdbc;

    ProcessEventOutbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void record(ProcessEventPublisher.Event event) {
        Object businessKey = event.payload().get("businessKey");
        jdbc.sql("INSERT INTO event_outbox (tenant_id, event_type, business_key, payload, occurred_at) VALUES (?, ?, ?, ?, ?)")
                .params(event.tenantId(), event.type(), businessKey == null ? null : String.valueOf(businessKey),
                        String.valueOf(event.payload()), event.occurredAt().atOffset(ZoneOffset.UTC))
                .update();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void notifyAfterCommit(ProcessEventPublisher.Event event) {
        LOG.info("process event " + event.type() + " committed for tenant " + event.tenantId());
    }
}
