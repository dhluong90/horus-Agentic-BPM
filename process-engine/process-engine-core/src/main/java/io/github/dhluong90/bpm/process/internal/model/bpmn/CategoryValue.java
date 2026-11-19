package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code categoryValue}.
 *
 * @param id id
 * @param value value
 * @param info info
 */
public record CategoryValue(
        String id,
        String value,
        ElementInfo info) implements BaseElement {

    public CategoryValue {
        info = info == null ? ElementInfo.empty() : info;
    }
}
