package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:type="agentTask"} on a {@code serviceTask}: the agent step (#52, DESIGN section 4.1).
 *
 * @param agent the name of the host's agent adapter ({@code horus:agent})
 * @param outputSchema the output schema the answer must meet, as canonical JSON text of a JSON object
 *     ({@code <horus:outputSchema>}); part of the definition, so the definition hash and its approval cover it
 * @param timeout bounds one call and sets the lease ({@code horus:timeout}), or null for the agent runner's default
 */
public record AgentTaskSpec(
        String agent,
        String outputSchema,
        Duration timeout) implements HorusExtension {
}
