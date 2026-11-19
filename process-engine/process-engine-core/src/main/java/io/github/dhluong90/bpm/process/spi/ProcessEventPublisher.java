package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Receives every engine event inside the transaction of the state change it reports (process engine DESIGN sections
 * 5.8 and 6.4). The default publisher is Spring {@code ApplicationEventPublisher} called in that transaction. Must
 * be thread-safe.
 *
 * <p>Listener contract (DESIGN 5.8): a listener that needs the event durably writes an outbox row in that
 * transaction; a listener that only notifies (mail, UI refresh) registers with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, so rolled-back work never emits and delivery is at most
 * once; a plain {@code @EventListener} runs in the transaction, an exception it throws rolls the state change back,
 * and it must not do network I/O (the rule of {@code ServiceTaskHandler}). The audit trail ({@code pre_history}) is
 * written by the engine itself and never depends on a listener.
 *
 * <p>Further rules for listeners:
 * <ul>
 *   <li>Consumers deduplicate on {@link Event#eventId()}: a host outbox relay delivers at least once, and the same
 *       transition always carries the same id.</li>
 *   <li>A listener scopes every read and write, and every outbox row, by {@link Event#tenantId()}, never by the
 *       ambient security context: the publisher is application-wide and sees every tenant's events.</li>
 *   <li>An {@code AFTER_COMMIT} listener that writes to the database does so in a new transaction
 *       ({@code REQUIRES_NEW}); a {@code REQUIRED} write there joins the finished transaction and is never
 *       committed.</li>
 *   <li>Payloads never carry instance variables or handler output. {@code businessKey} and the task assignee may be
 *       personal data, so a host outbox inherits the retention and masking rules of {@code pre_instance}.</li>
 *   <li>An idempotent replay returns the stored answer without running the work, so it publishes no events.</li>
 * </ul>
 */
@FunctionalInterface
public interface ProcessEventPublisher {

    /**
     * @param event the event
     */
    void publish(Event event);

    /**
     * An engine event.
     *
     * @param eventId unique and stable id of this event, derived from the instance (or definition version) and its
     *     sequence, so a replayed transaction cannot mint a new one; consumers deduplicate on it
     * @param type for example {@code processes.instance.started.v1}
     * @param tenantId the tenant the event belongs to; listeners scope by it
     * @param occurredAt engine clock time
     * @param payload event fields beyond the envelope; never instance variables
     */
    record Event(String eventId, String type, String tenantId, Instant occurredAt, Map<String, Object> payload) {
        public Event {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
