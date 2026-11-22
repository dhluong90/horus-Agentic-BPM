package io.github.dhluong90.bpm.process.internal.model.horus;

import java.time.Duration;

/**
 * {@code horus:type="agentTask"} on a {@code serviceTask}: the agent step (#52, DESIGN section 4.1).
 *
 * @param agent the name of the host's agent adapter ({@code horus:agent})
 * @param outputSchema the output schema the answer must meet, as canonical JSON text of a JSON object
 *     ({@code <horus:outputSchema>}); part of the definition, so the definition hash and its approval cover it
 * @param timeout bounds one call and sets the lease ({@code horus:timeout}), or null for the agent runner's default
 * @param fieldTitles the localised titles of the step's input and output fields as canonical JSON text of
 *     {@code {"inputs": {name: {language: title}}, "outputs": {...}}} ({@code <horus:fieldTitles>}, #386), or null;
 *     labels for the review screen only, no check or decision ever reads them. Part of the definition, so the
 *     definition hash covers them
 */
public record AgentTaskSpec(
        String agent,
        String outputSchema,
        Duration timeout,
        String fieldTitles) implements HorusExtension {

    /**
     * An agent step with no field titles.
     *
     * @param agent the adapter name
     * @param outputSchema the output schema as JSON text
     * @param timeout the call timeout, or null
     */
    public AgentTaskSpec(String agent, String outputSchema, Duration timeout) {
        this(agent, outputSchema, timeout, null);
    }
}
