package io.github.dhluong90.bpm.process.bpmn.xml;

import java.util.Map;

/** What a codec needs while writing. */
public interface WriteContext {

    /** @return namespace prefixes to URIs, as declared on the root element */
    Map<String, String> namespaces();

    /**
     * Writes a child value with the codec registered for its type.
     *
     * @param element the model value
     * @param out the writer
     * @throws javax.xml.stream.XMLStreamException on a writer failure
     */
    void writeChild(Object element, javax.xml.stream.XMLStreamWriter out) throws javax.xml.stream.XMLStreamException;
}
