package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code intermediateThrowEvent}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param eventDefinitions eventDefinitions
 * @param eventDefinitionRefs eventDefinitionRefs
 * @param data data
 * @param info info
 */
public record IntermediateThrowEvent(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        List<EventDefinition> eventDefinitions,
        List<String> eventDefinitionRefs,
        EventData data,
        ElementInfo info) implements ThrowEvent {

    public IntermediateThrowEvent {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        eventDefinitions = eventDefinitions == null ? List.of() : List.copyOf(eventDefinitions);
        eventDefinitionRefs = eventDefinitionRefs == null ? List.of() : List.copyOf(eventDefinitionRefs);
        data = data == null ? EventData.empty() : data;
        info = info == null ? ElementInfo.empty() : info;
    }
}
