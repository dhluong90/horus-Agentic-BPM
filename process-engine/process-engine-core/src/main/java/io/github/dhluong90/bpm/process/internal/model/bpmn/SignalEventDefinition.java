package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code signalEventDefinition}.
 *
 * @param id id
 * @param signalRef signalRef
 * @param info info
 */
public record SignalEventDefinition(
        String id,
        String signalRef,
        ElementInfo info) implements EventDefinition {

    public SignalEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
