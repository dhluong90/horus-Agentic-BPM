package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code conditionalEventDefinition}.
 *
 * @param id id
 * @param condition condition
 * @param info info
 */
public record ConditionalEventDefinition(
        String id,
        Expression condition,
        ElementInfo info) implements EventDefinition {

    public ConditionalEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
