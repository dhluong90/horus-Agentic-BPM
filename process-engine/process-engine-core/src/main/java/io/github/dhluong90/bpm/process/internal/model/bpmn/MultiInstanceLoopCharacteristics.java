package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code multiInstanceLoopCharacteristics}.
 *
 * @param id id
 * @param isSequential isSequential
 * @param behavior behavior
 * @param loopCardinality loopCardinality
 * @param loopDataInputRef loopDataInputRef
 * @param loopDataOutputRef loopDataOutputRef
 * @param inputDataItem inputDataItem
 * @param outputDataItem outputDataItem
 * @param completionCondition completionCondition
 * @param oneBehaviorEventRef oneBehaviorEventRef
 * @param noneBehaviorEventRef noneBehaviorEventRef
 * @param info info
 */
public record MultiInstanceLoopCharacteristics(
        String id,
        boolean isSequential,
        MultiInstanceBehavior behavior,
        Expression loopCardinality,
        String loopDataInputRef,
        String loopDataOutputRef,
        DataInput inputDataItem,
        DataOutput outputDataItem,
        Expression completionCondition,
        String oneBehaviorEventRef,
        String noneBehaviorEventRef,
        ElementInfo info) implements LoopCharacteristics {

    public MultiInstanceLoopCharacteristics {
        info = info == null ? ElementInfo.empty() : info;
    }
}
