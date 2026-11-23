package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataAssociation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataStoreReference;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which data stores a process executes against (#499): a data store is executed only when a data input or output
 * association of an activity names a {@code dataStoreReference}. A reference that no association uses stays inert, so
 * a definition that only documents a store needs no adapter.
 */
public final class DataStoreUse {

    private DataStoreUse() {
    }

    /**
     * @param process the process
     * @param referenceId the id of a flow element
     * @return the data store id the element names when it is a {@code dataStoreReference}
     */
    public static Optional<String> storeOf(Process process, String referenceId) {
        if (referenceId == null) {
            return Optional.empty();
        }
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof DataStoreReference ref && referenceId.equals(ref.id()) && ref.dataStoreRef() != null) {
                return Optional.of(ref.dataStoreRef());
            }
        }
        return Optional.empty();
    }

    /**
     * @param process the process
     * @return each data store an association executes against, with the id of the first activity that uses it
     */
    public static Map<String, String> storesUsed(Process process) {
        Map<String, String> used = new LinkedHashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (!(element instanceof Activity activity)) {
                continue;
            }
            for (DataAssociation association : activity.activity().dataInputAssociations()) {
                association.sourceRefs().forEach(ref -> storeOf(process, ref)
                        .ifPresent(store -> used.putIfAbsent(store, element.id())));
            }
            for (DataAssociation association : activity.activity().dataOutputAssociations()) {
                storeOf(process, association.targetRef()).ifPresent(store -> used.putIfAbsent(store, element.id()));
            }
        }
        return used;
    }
}
