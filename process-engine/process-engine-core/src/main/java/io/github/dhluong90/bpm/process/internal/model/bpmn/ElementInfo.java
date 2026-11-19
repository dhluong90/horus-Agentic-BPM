package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;

/**
 * Documentation, extensions and other attributes shared by every element.
 *
 * @param documentation documentation
 * @param extensions extensions
 * @param otherAttributes otherAttributes
 */
public record ElementInfo(
        List<Documentation> documentation,
        ExtensionBag extensions,
        Map<QName, String> otherAttributes) {

    public ElementInfo {
        documentation = documentation == null ? List.of() : List.copyOf(documentation);
        extensions = extensions == null ? ExtensionBag.EMPTY : extensions;
        otherAttributes = otherAttributes == null ? Map.of() : Map.copyOf(otherAttributes);
    }

    private static final ElementInfo EMPTY = new ElementInfo(List.of(), ExtensionBag.EMPTY, Map.of());

    /** @return an info with nothing in it */
    public static ElementInfo empty() {
        return EMPTY;
    }
}
