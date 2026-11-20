package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code participant} (pool).
 *
 * @param id id
 * @param name name
 * @param processRef processRef
 * @param minimumMultiplicity minimumMultiplicity
 * @param maximumMultiplicity maximumMultiplicity
 * @param info info
 */
public record Participant(
        String id,
        String name,
        String processRef,
        Integer minimumMultiplicity,
        Integer maximumMultiplicity,
        ElementInfo info) implements BaseElement {

    public Participant {
        info = info == null ? ElementInfo.empty() : info;
    }
}
