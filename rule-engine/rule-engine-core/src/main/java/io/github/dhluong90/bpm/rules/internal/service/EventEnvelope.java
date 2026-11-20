package io.github.dhluong90.bpm.rules.internal.service;

import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.internal.json.CorrelationIds;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Properties;
import tools.jackson.databind.node.ObjectNode;

/**
 * The envelope every Rule Engine event carries (DESIGN section 3.3): {@code eventId}, {@code eventType},
 * {@code occurredAt}, {@code tenantId}, {@code correlationId}, {@code causationId}, {@code producer} and
 * {@code schemaVersion}, as members of the payload next to the event's own fields. The members are additive within
 * each {@code .v1} type.
 */
final class EventEnvelope {

    /** The version of the envelope layout; a breaking change to it would be a new event type version. */
    static final int SCHEMA_VERSION = 1;

    /** The producer name; the engine version follows a slash. */
    static final String PRODUCER_NAME = "rule-engine";

    private static final String VERSION_RESOURCE = "rule-engine-core.properties";

    /** {@code rule-engine/<engine version>}, read once from the file the build writes. */
    static final String PRODUCER = PRODUCER_NAME + "/" + engineVersion();

    private EventEnvelope() {
    }

    /**
     * Starts a payload with the envelope members.
     *
     * @param eventId the event id, which is also the outbox row id (ULID)
     * @param eventType the type, with its {@code .v<N>} suffix
     * @param tenantId the tenant, from the security context
     * @param occurredAt when the state change committed
     * @param correlationId the caller's correlation id, or null to start a chain at this event
     * @param causationId the id of the command or event that caused this one, or null
     * @return the payload, to which the event adds its own members
     */
    static ObjectNode start(String eventId, String eventType, String tenantId, Instant occurredAt,
            String correlationId, String causationId) {
        ObjectNode payload = EngineJson.NODES.objectNode()
                .put("eventId", eventId)
                .put("eventType", eventType)
                .put("occurredAt", occurredAt.toString())
                .put("tenantId", tenantId)
                .put("correlationId", correlationId == null ? eventId : correlationId)
                .put("producer", PRODUCER)
                .put("schemaVersion", SCHEMA_VERSION);
        if (causationId == null) {
            payload.putNull("causationId");
        } else {
            payload.put("causationId", causationId);
        }
        return payload;
    }

    /**
     * Refuses a malformed correlation id passed through the Java API, as the decision API does.
     *
     * @param correlationId the id, or null
     * @return the id
     * @throws InputInvalidException when it is not 1 to 128 characters of {@code A-Z a-z 0-9 . _ : -}
     */
    static String requireValidCorrelationId(String correlationId) {
        if (correlationId != null && !CorrelationIds.isValid(correlationId)) {
            throw new InputInvalidException("correlationId must be 1 to " + EngineDefaults.CORRELATION_ID_MAX_LENGTH
                    + " characters of A-Z a-z 0-9 . _ : -");
        }
        return correlationId;
    }

    private static String engineVersion() {
        try (InputStream in = EventEnvelope.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("rule-engine-core is missing " + VERSION_RESOURCE
                        + "; rebuild it with Maven.");
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("engine-version");
            if (version == null || version.isBlank() || version.startsWith("${")) {
                throw new IllegalStateException(VERSION_RESOURCE + " has no engine-version; rebuild it with Maven.");
            }
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
