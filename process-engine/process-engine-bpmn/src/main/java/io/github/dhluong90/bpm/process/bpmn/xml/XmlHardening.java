package io.github.dhluong90.bpm.process.bpmn.xml;

import javax.xml.stream.XMLInputFactory;

/**
 * The one place that creates XML readers for BPMN and DMN: no DTD, no external entities, no entity replacement,
 * and the {@link Limits} enforced while reading. Owned by the BPMN XML stream; security review required.
 */
public final class XmlHardening {

    private XmlHardening() {
    }

    /**
     * @param limits the limits of the read
     * @return a factory with DTD and external entity support off
     */
    public static XMLInputFactory newInputFactory(Limits limits) {
        throw new UnsupportedOperationException("owned by the BPMN XML stream");
    }
}
