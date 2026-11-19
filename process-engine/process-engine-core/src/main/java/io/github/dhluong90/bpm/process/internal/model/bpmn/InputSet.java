package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code inputSet}.
 *
 * @param id id
 * @param name name
 * @param dataInputRefs dataInputRefs
 * @param info info
 */
public record InputSet(
        String id,
        String name,
        List<String> dataInputRefs,
        ElementInfo info) implements BaseElement {

    public InputSet {
        dataInputRefs = dataInputRefs == null ? List.of() : List.copyOf(dataInputRefs);
        info = info == null ? ElementInfo.empty() : info;
    }
}
