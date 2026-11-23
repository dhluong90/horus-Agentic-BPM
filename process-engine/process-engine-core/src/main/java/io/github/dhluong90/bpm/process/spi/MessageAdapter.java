package io.github.dhluong90.bpm.process.spi;

import java.util.Map;

/**
 * The host's side of a message a process sends: a send task, a message throw event or a message end event (#491). The
 * engine never opens a connection to a broker; it hands the message to this port and the host decides how it leaves.
 * The starter collects at most one bean of this type; there is no default, and a step that sends a message fails
 * with {@code MESSAGE_ADAPTER_MISSING} when none is registered.
 *
 * <p>Transactions: {@link #send} runs inside the step transaction, on the host's connection. A host writes the
 * message to its outbox table in that transaction and relays it after the commit, so a message is sent if and only if
 * the step committed. The call must be fast and in-process and must never call the network.
 *
 * <p>Idempotency: a step that rolls back and runs again hands over the same {@link OutgoingMessage#messageId()}, so
 * the host keeps one outbox row per id ({@code ON CONFLICT DO NOTHING}). The relay delivers at least once; the
 * receiver deduplicates on the same id.
 *
 * <p>Receiving needs no port: the engine never listens. The host's consumer calls
 * {@code MessageService.correlate} or {@code POST /messages:correlate} for each message it receives.
 *
 * <p>Implementations must be thread-safe.
 */
@FunctionalInterface
public interface MessageAdapter {

    /**
     * Hands a message to the host's outbox.
     *
     * @param message the message
     */
    void send(OutgoingMessage message);

    /**
     * One message to send. The payload holds only the values the step names with {@code horus:input}.
     *
     * @param tenantId the instance's tenant; an outbox row is scoped by it
     * @param messageId stable for the step, the idempotency key of the outbox row
     * @param name the message name
     * @param correlationKey the key the receiver correlates on, or null
     * @param payload the named values
     * @param instanceId the sending instance
     * @param nodeId the sending step
     */
    record OutgoingMessage(String tenantId, String messageId, String name, String correlationKey,
            Map<String, Object> payload, String instanceId, String nodeId) {
    }
}
