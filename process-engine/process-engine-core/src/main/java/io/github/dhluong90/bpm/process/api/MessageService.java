package io.github.dhluong90.bpm.process.api;

import java.util.Map;

/**
 * Delivers a message to the process that waits for it (#491). The engine never listens: the host's consumer, or a
 * partner through {@code POST /messages:correlate}, calls this once per message received. The tenant comes from the
 * host's {@code TenantResolver}; a message never reaches an instance of another tenant. The caller needs
 * {@code processes:message:correlate}.
 *
 * <p>A message goes to exactly one place: the one instance that waits for its name and correlation key (a message
 * catch event or a receive task), or, when the key is not given or nothing waits for it, a new instance of the
 * definition whose message start event has that name. A message that matches nothing is refused with
 * {@code MESSAGE_NOT_CORRELATED} and nothing is stored: there is no buffering. A message that matches two waiting
 * instances is refused with {@code MESSAGE_CORRELATION_AMBIGUOUS} and nothing runs.
 */
public interface MessageService {

    /**
     * @param messageName the message name, 1 to 100 characters
     * @param correlationKey the key, at most 200 characters; may be null only for a message that starts a process
     * @param payload the message fields; only those a step names with {@code horus:output} are kept
     * @param idempotencyKey required; a replay with the same key and request returns the original result
     * @return where the message went
     * @throws MessageNotCorrelatedException when no instance waits and no process starts on the message
     * @throws MessageAmbiguousException when two or more instances (or start definitions) match
     * @throws PreconditionRequiredException when {@code idempotencyKey} is null
     * @throws InputInvalidException for a malformed name, key or payload
     * @throws StepFailedException when a step the message runs fails; nothing is written
     */
    MessageDelivery correlate(String messageName, String correlationKey, Map<String, Object> payload,
            String idempotencyKey);

    /**
     * Where a message went.
     *
     * @param outcome {@code DELIVERED} to a waiting instance, or {@code STARTED} for a new (or, with the same key, the
     *     existing) instance of a process with a message start event
     * @param instanceId the instance
     * @param nodeId the step that received it, or null when it started an instance
     */
    record MessageDelivery(Outcome outcome, String instanceId, String nodeId) {

        /** What the message did. */
        public enum Outcome { DELIVERED, STARTED }
    }
}
