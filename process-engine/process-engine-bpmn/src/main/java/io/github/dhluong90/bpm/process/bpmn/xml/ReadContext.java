package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import java.util.List;
import java.util.Optional;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** What a codec needs while reading: limits, id references, problems and dispatch to other codecs. */
public interface ReadContext {

    /** @return the limits of this read */
    Limits limits();

    /** @return the {@code expressionLanguage} of the definitions element, or the BPMN default */
    String expressionLanguage();

    /**
     * Records an element id; a duplicate id is a problem.
     *
     * @param id the id
     * @param element the model value
     */
    void register(String id, Object element);

    /**
     * @param id an id reference
     * @return the element read so far with that id
     */
    Optional<Object> resolve(String id);

    /**
     * Reads a child element with the codec registered for its name.
     *
     * @param in the reader on the child's start tag
     * @return the model value
     * @throws XMLStreamException on malformed XML
     */
    Object readChild(XMLStreamReader in) throws XMLStreamException;

    /**
     * @param name an element name
     * @return whether a codec handles it
     */
    boolean handles(QName name);

    /** @param problem an element that cannot be read or is refused */
    void problem(DefinitionProblem problem);

    /** @param warning a non-fatal finding, for example a foreign extension kept opaque */
    void warn(DefinitionProblem warning);

    /** @return problems so far */
    List<DefinitionProblem> problems();
}
