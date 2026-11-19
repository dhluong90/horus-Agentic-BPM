package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code outputSet}.
 *
 * @param id id
 * @param name name
 * @param dataOutputRefs dataOutputRefs
 * @param info info
 */
public record OutputSet(
        String id,
        String name,
        List<String> dataOutputRefs,
        ElementInfo info) implements BaseElement {

    public OutputSet {
        dataOutputRefs = dataOutputRefs == null ? List.of() : List.copyOf(dataOutputRefs);
        info = info == null ? ElementInfo.empty() : info;
    }
}
