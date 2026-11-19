package io.github.dhluong90.bpm.process.bpmn.xml.codec;

import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.bpmn.xml.ElementCodec;
import io.github.dhluong90.bpm.process.bpmn.xml.ReadContext;
import io.github.dhluong90.bpm.process.bpmn.xml.WriteContext;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/** Codec stub of the contract. Owned by the BPMN XML stream. */
public final class EventCodec implements ElementCodec<Object> {

    @Override
    public Set<QName> handles() {
        return names("startEvent", "endEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent", "timerEventDefinition", "messageEventDefinition", "signalEventDefinition", "errorEventDefinition", "escalationEventDefinition", "compensateEventDefinition", "conditionalEventDefinition", "linkEventDefinition", "cancelEventDefinition", "terminateEventDefinition");
    }

    @Override
    public Object read(XMLStreamReader in, ReadContext ctx) {
        throw new UnsupportedOperationException("owned by the BPMN XML stream");
    }

    @Override
    public void write(Object element, XMLStreamWriter out, WriteContext ctx) {
        throw new UnsupportedOperationException("owned by the BPMN XML stream");
    }

    static Set<QName> names(String... localNames) {
        return Arrays.stream(localNames).map(n -> new QName(BpmnNamespaces.BPMN, n)).collect(Collectors.toUnmodifiableSet());
    }
}
