package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives every engine event inside the transaction of the state change it reports (process engine DESIGN sections
 * 5.8 and 6.4), so the event and the state change commit or roll back together. Types follow
 * {@code processes.<aggregate>.<pastTense>.v1}. The starter's default hands events to the Spring application event
 * publisher. Must be thread-safe.
 *
 * <p>Listener contract:
 * <ul>
 *   <li>A durable consumer writes an outbox row in that transaction; the host relays it after commit. This is the
 *       only way to integrate events with money movement, audit or other services.</li>
 *   <li>A notification listener uses {@code @TransactionalEventListener(phase = AFTER_COMMIT)}: it runs at most once
 *       and never for work that rolled back.</li>
 *   <li>A plain listener runs inside the engine transaction: it must not do network I/O, and an exception it throws
 *       rolls the state change back.</li>
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
     * @param type for example {@code processes.instance.started.v1}
     * @param tenantId the tenant
     * @param occurredAt engine clock time
     * @param payload event fields beyond the envelope
     */
    record Event(String type, String tenantId, Instant occurredAt, Map<String, Object> payload) {
        public Event {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
