package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code assignment}.
 *
 * @param id id
 * @param from from
 * @param to to
 * @param info info
 */
public record Assignment(
        String id,
        Expression from,
        Expression to,
        ElementInfo info) implements BaseElement {

    public Assignment {
        info = info == null ? ElementInfo.empty() : info;
    }
}
