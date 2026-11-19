package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code dataInput}.
 *
 * @param id id
 * @param name name
 * @param itemSubjectRef itemSubjectRef
 * @param isCollection isCollection
 * @param info info
 */
public record DataInput(
        String id,
        String name,
        String itemSubjectRef,
        boolean isCollection,
        ElementInfo info) implements BaseElement {

    public DataInput {
        info = info == null ? ElementInfo.empty() : info;
    }
}
