package io.github.dhluong90.bpm.process.api;

/** The XML declares a DOCTYPE, an entity, or exceeds the size or depth limit. Code {@code BPMN_UNSAFE_XML}. */
public final class BpmnUnsafeXmlException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public BpmnUnsafeXmlException(String message) {
        super("BPMN_UNSAFE_XML", message);
    }
}
