package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * Data inputs and outputs of an event and their associations.
 *
 * @param dataInputs dataInputs
 * @param dataOutputs dataOutputs
 * @param dataInputAssociations dataInputAssociations
 * @param dataOutputAssociations dataOutputAssociations
 */
public record EventData(
        List<DataInput> dataInputs,
        List<DataOutput> dataOutputs,
        List<DataInputAssociation> dataInputAssociations,
        List<DataOutputAssociation> dataOutputAssociations) {

    public EventData {
        dataInputs = dataInputs == null ? List.of() : List.copyOf(dataInputs);
        dataOutputs = dataOutputs == null ? List.of() : List.copyOf(dataOutputs);
        dataInputAssociations = dataInputAssociations == null ? List.of() : List.copyOf(dataInputAssociations);
        dataOutputAssociations = dataOutputAssociations == null ? List.of() : List.copyOf(dataOutputAssociations);
    }

    private static final EventData EMPTY = new EventData(List.of(), List.of(), List.of(), List.of());

    /** @return no data */
    public static EventData empty() {
        return EMPTY;
    }
}
