package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code dataOutput}.
 *
 * @param id id
 * @param name name
 * @param itemSubjectRef itemSubjectRef
 * @param isCollection isCollection
 * @param info info
 */
public record DataOutput(
        String id,
        String name,
        String itemSubjectRef,
        boolean isCollection,
        ElementInfo info) implements BaseElement {

    public DataOutput {
        info = info == null ? ElementInfo.empty() : info;
    }
}
