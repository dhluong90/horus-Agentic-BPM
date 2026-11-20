package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Objects;

/**
 * BPMN {@code boundaryEvent}.
 *
 * @param id id
 * @param name name
 * @param incoming incoming
 * @param outgoing outgoing
 * @param eventDefinitions eventDefinitions
 * @param eventDefinitionRefs eventDefinitionRefs
 * @param data data
 * @param parallelMultiple parallelMultiple
 * @param attachedToRef attachedToRef
 * @param cancelActivity cancelActivity
 * @param info info
 */
public record BoundaryEvent(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        List<EventDefinition> eventDefinitions,
        List<String> eventDefinitionRefs,
        EventData data,
        boolean parallelMultiple,
        String attachedToRef,
        boolean cancelActivity,
        ElementInfo info) implements CatchEvent {

    public BoundaryEvent {
        Objects.requireNonNull(id, "id must not be null");
        incoming = incoming == null ? List.of() : List.copyOf(incoming);
        outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
        eventDefinitions = eventDefinitions == null ? List.of() : List.copyOf(eventDefinitions);
        eventDefinitionRefs = eventDefinitionRefs == null ? List.of() : List.copyOf(eventDefinitionRefs);
        data = data == null ? EventData.empty() : data;
        info = info == null ? ElementInfo.empty() : info;
    }
}
