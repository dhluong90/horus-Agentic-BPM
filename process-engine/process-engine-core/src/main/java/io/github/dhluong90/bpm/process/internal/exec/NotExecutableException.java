package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;

/** A flow node has no registered behaviour; reported as {@code BPMN_NOT_EXECUTABLE} at activation. */
public final class NotExecutableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String element;

    /** @param node the node */
    public NotExecutableException(FlowNode node) {
        this(node.id(), node.getClass().getSimpleName());
    }

    /**
     * @param nodeId node id
     * @param element element type name
     */
    public NotExecutableException(String nodeId, String element) {
        super(element + " '" + nodeId + "' is not executable in this release");
        this.nodeId = nodeId;
        this.element = element;
    }

    /** @return the node id */
    public String nodeId() {
        return nodeId;
    }

    /** @return the element type name */
    public String element() {
        return element;
    }
}
