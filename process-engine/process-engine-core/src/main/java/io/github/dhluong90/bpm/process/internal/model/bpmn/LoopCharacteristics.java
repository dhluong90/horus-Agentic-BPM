package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tLoopCharacteristics}.
 */
public sealed interface LoopCharacteristics extends BaseElement
        permits StandardLoopCharacteristics, MultiInstanceLoopCharacteristics {
}
