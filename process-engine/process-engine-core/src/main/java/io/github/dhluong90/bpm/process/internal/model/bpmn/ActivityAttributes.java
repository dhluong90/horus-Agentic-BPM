package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * Attributes and children every BPMN activity has.
 *
 * @param isForCompensation isForCompensation
 * @param startQuantity startQuantity
 * @param completionQuantity completionQuantity
 * @param defaultFlow defaultFlow
 * @param ioSpecification ioSpecification
 * @param dataInputAssociations dataInputAssociations
 * @param dataOutputAssociations dataOutputAssociations
 * @param resources resources
 * @param loopCharacteristics loopCharacteristics
 */
public record ActivityAttributes(
        boolean isForCompensation,
        int startQuantity,
        int completionQuantity,
        String defaultFlow,
        IoSpecification ioSpecification,
        List<DataInputAssociation> dataInputAssociations,
        List<DataOutputAssociation> dataOutputAssociations,
        List<ResourceRole> resources,
        LoopCharacteristics loopCharacteristics) {

    public ActivityAttributes {
        dataInputAssociations = dataInputAssociations == null ? List.of() : List.copyOf(dataInputAssociations);
        dataOutputAssociations = dataOutputAssociations == null ? List.of() : List.copyOf(dataOutputAssociations);
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    private static final ActivityAttributes DEFAULTS =
            new ActivityAttributes(false, 1, 1, null, null, List.of(), List.of(), List.of(), null);

    /** @return the XSD defaults: not for compensation, quantities 1, nothing else */
    public static ActivityAttributes defaults() {
        return DEFAULTS;
    }
}
