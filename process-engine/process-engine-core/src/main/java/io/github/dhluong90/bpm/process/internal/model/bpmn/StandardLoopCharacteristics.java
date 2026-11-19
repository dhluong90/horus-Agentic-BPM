package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code standardLoopCharacteristics}.
 *
 * @param id id
 * @param testBefore testBefore
 * @param loopMaximum loopMaximum
 * @param loopCondition loopCondition
 * @param info info
 */
public record StandardLoopCharacteristics(
        String id,
        boolean testBefore,
        Integer loopMaximum,
        Expression loopCondition,
        ElementInfo info) implements LoopCharacteristics {

    public StandardLoopCharacteristics {
        info = info == null ? ElementInfo.empty() : info;
    }
}
