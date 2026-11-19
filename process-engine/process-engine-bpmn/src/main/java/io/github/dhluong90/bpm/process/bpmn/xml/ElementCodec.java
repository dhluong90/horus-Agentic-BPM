package io.github.dhluong90.bpm.process.bpmn.xml;

import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;

/**
 * Reads and writes the elements it handles, losslessly. One codec per element family; registered in
 * {@link BpmnCodecs}.
 *
 * @param <T> the model type produced and consumed
 */
public interface ElementCodec<T> {

    /** @return the qualified element names this codec reads */
    Set<QName> handles();

    /**
     * Reads one element. The reader is positioned on its start tag and must be left on its end tag.
     *
     * @param in the reader
     * @param ctx id references, limits and warnings
     * @return the model value
     * @throws XMLStreamException on malformed XML
     */
    T read(XMLStreamReader in, ReadContext ctx) throws XMLStreamException;

    /**
     * Writes one element.
     *
     * @param element the model value
     * @param out the writer
     * @param ctx namespaces and options
     * @throws XMLStreamException on a writer failure
     */
    void write(T element, XMLStreamWriter out, WriteContext ctx) throws XMLStreamException;
}
