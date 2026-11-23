package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionContext;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.Token;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataInput;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataInputAssociation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataObject;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataObjectReference;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataOutput;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataOutputAssociation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import java.util.Map;
import java.util.Optional;
import javax.xml.namespace.QName;

/**
 * Data objects as scoped process variables (#499). A data object is the variable named by its {@code horus:variable}
 * attribute, or by its id. A data output association copies a value of the step's result into the variable of the
 * data object it targets; a data input association adds the variable's value to the inputs of the step. A data store
 * reference is kept and exported but not executed in this release.
 */
final class DataBindings {

    private static final QName VARIABLE = new QName("urn:horus-bpm:bpmn:extensions:v1", "variable");

    private DataBindings() {
    }

    private static Optional<ActivityAttributes> attributes(FlowNode node) {
        return node instanceof Activity a ? Optional.of(a.activity()) : Optional.empty();
    }

    /** @return the variable of the data object that the reference names, or empty when it is not a data object */
    private static Optional<DataObject> dataObject(ExecutionContext ctx, String referenceId) {
        if (referenceId == null) {
            return Optional.empty();
        }
        for (FlowElement element : ctx.process().process().allFlowElements()) {
            if (element instanceof DataObjectReference ref && ref.id().equals(referenceId)) {
                for (FlowElement candidate : ctx.process().process().allFlowElements()) {
                    if (candidate instanceof DataObject d && d.id().equals(ref.dataObjectRef())) {
                        return Optional.of(d);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String variable(DataObject d) {
        return d.info().otherAttributes().getOrDefault(VARIABLE, d.id());
    }

    private static String name(DataObject d) {
        return d.name() != null ? d.name() : d.id();
    }

    static void readInputs(FlowNode node, Token token, ExecutionContext ctx, Map<String, Object> out) {
        ActivityAttributes a = attributes(node).orElse(null);
        if (a == null) {
            return;
        }
        for (DataInputAssociation association : a.dataInputAssociations()) {
            String inputName = association.targetRef();
            if (a.ioSpecification() != null) {
                for (DataInput input : a.ioSpecification().dataInputs()) {
                    if (input.id().equals(association.targetRef()) && input.name() != null) {
                        inputName = input.name();
                    }
                }
            }
            for (String source : association.sourceRefs()) {
                Optional<DataObject> object = dataObject(ctx, source);
                if (object.isEmpty()) {
                    continue;
                }
                Object value = ctx.variables().get(variable(object.get()), token).orElseThrow(() ->
                        new ExecutionException("DATA_OBJECT_UNSET", node.id(), "Data object " + name(object.get())
                                + " has no value yet"));
                out.put(inputName, value);
            }
        }
    }

    static void writeOutputs(FlowNode node, Map<String, Object> output, Token token, ExecutionContext ctx) {
        ActivityAttributes a = attributes(node).orElse(null);
        if (a == null) {
            return;
        }
        for (DataOutputAssociation association : a.dataOutputAssociations()) {
            Optional<DataObject> object = dataObject(ctx, association.targetRef());
            if (object.isEmpty()) {
                continue;
            }
            Object value = Map.copyOf(output);
            if (!association.sourceRefs().isEmpty()) {
                String key = association.sourceRefs().get(0);
                if (a.ioSpecification() != null) {
                    for (DataOutput o : a.ioSpecification().dataOutputs()) {
                        if (o.id().equals(key) && o.name() != null) {
                            key = o.name();
                        }
                    }
                }
                if (!output.containsKey(key)) {
                    throw new ExecutionException("DATA_OUTPUT_MISSING", node.id(), "The result has no value named "
                            + key + " for data object " + name(object.get()));
                }
                value = output.get(key);
            }
            ctx.variables().set(variable(object.get()), value);
            ctx.history("DATA_OBJECT_WRITTEN", token, Map.of("dataObject", name(object.get()), "variable",
                    variable(object.get())));
        }
    }
}
