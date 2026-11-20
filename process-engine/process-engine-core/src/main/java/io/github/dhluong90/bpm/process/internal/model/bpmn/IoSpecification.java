package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code ioSpecification}.
 *
 * @param id id
 * @param dataInputs dataInputs
 * @param dataOutputs dataOutputs
 * @param inputSets inputSets
 * @param outputSets outputSets
 * @param info info
 */
public record IoSpecification(
        String id,
        List<DataInput> dataInputs,
        List<DataOutput> dataOutputs,
        List<InputSet> inputSets,
        List<OutputSet> outputSets,
        ElementInfo info) implements BaseElement {

    public IoSpecification {
        dataInputs = dataInputs == null ? List.of() : List.copyOf(dataInputs);
        dataOutputs = dataOutputs == null ? List.of() : List.copyOf(dataOutputs);
        inputSets = inputSets == null ? List.of() : List.copyOf(inputSets);
        outputSets = outputSets == null ? List.of() : List.copyOf(outputSets);
        info = info == null ? ElementInfo.empty() : info;
    }
}
