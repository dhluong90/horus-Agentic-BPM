package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives every engine event after the transaction of the state change it reports has committed, never for work
 * that rolled back. Delivery is at most once and not durable in this release: an event is lost if the process stops
 * between the commit and the call, so hosts must not use events for money movement, audit or cross-service
 * integration until an engine outbox exists. Types follow {@code processes.<aggregate>.<pastTense>.v1} (DESIGN
 * section 6.4). The starter's default hands events to the Spring application event publisher. Must be thread-safe.
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
