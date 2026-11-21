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
     * An engine event in the standard envelope (DESIGN section 6.4): {@code eventId}, {@code eventType},
     * {@code occurredAt}, {@code tenantId}, {@code correlationId}, {@code causationId}, {@code producer},
     * {@code schemaVersion} and {@code payload}. The envelope members are additive within each {@code .v1} type.
     *
     * @param eventId unique and stable id of this event, derived from the instance (or definition version) and its
     *     sequence, so a replayed transaction cannot mint a new one; consumers deduplicate on it
     * @param type for example {@code processes.instance.started.v1}
     * @param tenantId the tenant the event belongs to; listeners scope by it
     * @param occurredAt engine clock time
     * @param payload event fields beyond the envelope; never instance variables
     * @param correlationId the request's correlation id, or for work started by a job the id the job carries; when
     *     null the chain starts at this event and the id is {@code eventId}
     * @param causationId the id of the command or job that caused the change, or null
     */
    record Event(String eventId, String type, String tenantId, Instant occurredAt, Map<String, Object> payload,
            String correlationId, String causationId) {

        /** The {@code producer} member of every engine event. */
        public static final String PRODUCER = "horus-process-engine";

        /** The {@code schemaVersion} member: the version of the envelope layout. */
        public static final int SCHEMA_VERSION = 1;

        public Event {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
            correlationId = correlationId == null ? eventId : correlationId;
        }

        /** An event that starts its own correlation chain and has no recorded cause. */
        public Event(String eventId, String type, String tenantId, Instant occurredAt, Map<String, Object> payload) {
            this(eventId, type, tenantId, occurredAt, payload, null, null);
        }

        /** @return this event with the given correlation and causation ids; a null correlation id is kept as is */
        public Event withContext(String correlation, String causation) {
            return new Event(eventId, type, tenantId, occurredAt, payload,
                    correlation == null ? correlationId : correlation, causation);
        }

        /** @return the producer, {@value #PRODUCER} */
        public String producer() {
            return PRODUCER;
        }

        /** @return the envelope layout version, {@value #SCHEMA_VERSION} */
        public int schemaVersion() {
            return SCHEMA_VERSION;
        }

        /**
         * @return the event as the envelope map a host serialises into its outbox row or onto a broker: the nine
         *     members, with the payload nested under {@code payload}
         */
        public Map<String, Object> envelope() {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("eventId", eventId);
            envelope.put("eventType", type);
            envelope.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
            envelope.put("tenantId", tenantId);
            envelope.put("correlationId", correlationId);
            envelope.put("causationId", causationId);
            envelope.put("producer", PRODUCER);
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("payload", payload);
            return envelope;
        }
    }
}
