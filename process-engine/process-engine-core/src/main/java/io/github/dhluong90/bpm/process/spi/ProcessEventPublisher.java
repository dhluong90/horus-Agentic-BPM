package io.github.dhluong90.bpm.process.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives every engine event inside the transaction of the state change it reports, so a host can write it to
 * its outbox atomically. Types follow {@code processes.<aggregate>.<pastTense>.v1} (DESIGN section 6.4). The
 * starter's default hands events to the Spring application event publisher. Must be thread-safe.
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
