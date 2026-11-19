package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code collaboration}.
 *
 * @param id id
 * @param name name
 * @param isClosed isClosed
 * @param participants participants
 * @param messageFlows messageFlows
 * @param artifacts artifacts
 * @param info info
 */
public record Collaboration(
        String id,
        String name,
        boolean isClosed,
        List<Participant> participants,
        List<MessageFlow> messageFlows,
        List<Artifact> artifacts,
        ElementInfo info) implements RootElement {

    public Collaboration {
        Objects.requireNonNull(id, "id must not be null");
        participants = participants == null ? List.of() : List.copyOf(participants);
        messageFlows = messageFlows == null ? List.of() : List.copyOf(messageFlows);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        info = info == null ? ElementInfo.empty() : info;
    }
}
