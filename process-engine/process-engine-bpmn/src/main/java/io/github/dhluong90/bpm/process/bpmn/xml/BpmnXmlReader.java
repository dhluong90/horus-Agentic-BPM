package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Definitions;

/**
 * Reads a BPMN 2.0.2 document into the semantic model through {@link BpmnCodecs}. Owned by the BPMN XML stream.
 */
public final class BpmnXmlReader {

    private final Limits limits;

    /** @param limits the read limits */
    public BpmnXmlReader(Limits limits) {
        this.limits = limits;
    }

    /**
     * @param source the XML bytes
     * @return the model
     * @throws io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException on a DOCTYPE, an entity or a limit
     * @throws io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException naming refused elements
     */
    public Definitions read(byte[] source) {
        throw new UnsupportedOperationException("owned by the BPMN XML stream; limits " + limits);
    }
}
