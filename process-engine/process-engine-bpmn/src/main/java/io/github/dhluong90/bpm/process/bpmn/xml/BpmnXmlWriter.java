package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Definitions;

/** Writes the semantic model as BPMN 2.0.2 XML. Owned by the BPMN XML stream. */
public final class BpmnXmlWriter {

    /**
     * @param definitions the model
     * @return UTF-8 XML bytes
     */
    public byte[] write(Definitions definitions) {
        throw new UnsupportedOperationException("owned by the BPMN XML stream");
    }
}
