package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

/**
 * Every BPMN element: an id, documentation, typed and foreign extensions, and unknown attributes, so a model read
 * from XML writes back without loss (ADR-0018).
 */
public interface BaseElement {

    /** @return the BPMN id, or null where the standard allows none */
    String id();

    /** @return documentation, extensions and other attributes */
    ElementInfo info();

    /** @return the {@code documentation} children */
    default List<Documentation> documentation() {
        return info().documentation();
    }

    /** @return typed {@code horus} records and opaque foreign fragments */
    default ExtensionBag extensions() {
        return info().extensions();
    }

    /** @return attributes of other namespaces, kept for round trip */
    default Map<QName, String> otherAttributes() {
        return info().otherAttributes();
    }
}
