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

/** Codec stub of the contract. Owned by the diagram interchange stream. */
public final class CollaborationCodec implements ElementCodec<Object> {

    @Override
    public Set<QName> handles() {
        return names("collaboration", "participant", "messageFlow", "laneSet", "lane");
    }

    @Override
    public Object read(XMLStreamReader in, ReadContext ctx) {
        throw new UnsupportedOperationException("owned by the diagram interchange stream");
    }

    @Override
    public void write(Object element, XMLStreamWriter out, WriteContext ctx) {
        throw new UnsupportedOperationException("owned by the diagram interchange stream");
    }

    static Set<QName> names(String... localNames) {
        return Arrays.stream(localNames).map(n -> new QName(BpmnNamespaces.BPMN, n)).collect(Collectors.toUnmodifiableSet());
    }
}
