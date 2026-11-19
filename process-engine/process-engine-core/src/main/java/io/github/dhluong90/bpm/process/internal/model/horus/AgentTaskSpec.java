package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:type="agentTask"} attributes.
 *
 * @param agent agent
 * @param outputSchema outputSchema
 * @param timeout timeout
 */
public record AgentTaskSpec(
        String agent,
        String outputSchema,
        Duration timeout) implements HorusExtension {
}
