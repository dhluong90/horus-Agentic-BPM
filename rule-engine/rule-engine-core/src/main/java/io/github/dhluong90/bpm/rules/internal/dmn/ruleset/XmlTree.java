package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLResolver;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A small, immutable element tree read from XML with the JDK streaming parser under hard limits: no DTD, no
 * entity of any kind, no external resource, at most {@link #MAX_DEPTH} levels, {@link #MAX_ELEMENTS} elements and
 * {@link #MAX_TEXT} characters of text per element. Comments and processing instructions are skipped.
 *
 * @param namespace the namespace URI, or an empty string
 * @param name the local name
 * @param attributes attribute values keyed by {@code {namespace}local} for qualified attributes, by local name
 *        otherwise; {@code xmlns} declarations are not attributes
 * @param children the child elements in document order
 * @param text the concatenated character data directly inside the element
 * @param line the line of the start tag, from 1
 */
record XmlTree(String namespace, String name, Map<String, String> attributes, List<XmlTree> children, String text,
        int line) {

    /** Deepest element nesting accepted. */
    static final int MAX_DEPTH = 32;

    /** Most elements accepted in one document. */
    static final int MAX_ELEMENTS = 50_000;

    /** Most characters of text accepted inside one element. */
    static final int MAX_TEXT = 65_536;

    XmlTree {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = List.copyOf(children);
    }

    /** Longest attribute value accepted, in characters; as long as the longest cell text. */
    static final int MAX_ATTRIBUTE = 4096;

    private static final XMLResolver REFUSE_RESOLVER = (publicId, systemId, baseUri, namespace) -> {
        throw new XMLStreamException("External resources are not allowed");
    };

    /**
     * Parses a document.
     *
     * @param source the UTF-8 bytes; the caller has already bounded their size
     * @return the root element
     * @throws ValidationFailedException when the document is not UTF-8 or not well-formed, declares a DTD or
     *         another encoding, or breaks a limit
     */
    static XmlTree parse(byte[] source) {
        return parse(source, true, REFUSE_RESOLVER);
    }

    /**
     * Parses a document. The bytes are decoded as UTF-8 here and the parser reads the decoded text, so the stored
     * source and the parsed document cannot disagree; an XML declaration naming another encoding is refused.
     * Package-private for the test that proves the factory settings alone stop entities and external DTDs.
     *
     * @param source the bytes
     * @param refuseDoctype whether a DOCTYPE is refused on sight (always true outside that test)
     * @param resolver the resolver of external resources (refusing outside that test)
     * @return the root element
     */
    static XmlTree parse(byte[] source, boolean refuseDoctype, XMLResolver resolver) {
        String document = Quote.utf8(source);
        if (document.startsWith("\uFEFF")) {
            document = document.substring(1);
        }
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Only the five predefined entities and character references exist without a DTD; they are replaced.
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setXMLResolver(resolver);
        // Parser warnings and errors become exceptions here, never lines on System.err the host cannot see.
        factory.setXMLReporter((message, errorType, relatedInformation, location) -> { });
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // the default JDK factory supports it; nothing else is used
        }
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(document));
            String declared = reader.getCharacterEncodingScheme();
            if (declared != null && !declared.equalsIgnoreCase("UTF-8")) {
                throw new ValidationFailedException("The XML declaration names the encoding " + Quote.of(declared)
                        + "; DMN files must be UTF-8 (line 1)");
            }
            Builder root = null;
            List<Builder> stack = new ArrayList<>();
            int elements = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.DTD -> {
                        if (refuseDoctype) {
                            throw invalid(reader, "DTDs are not allowed in a DMN document");
                        }
                    }
                    case XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.ENTITY_DECLARATION ->
                            throw invalid(reader, "Entity references are not allowed in a DMN document");
                    case XMLStreamConstants.START_ELEMENT -> {
                        if (++elements > MAX_ELEMENTS) {
                            throw invalid(reader, "Document has more than " + MAX_ELEMENTS + " elements");
                        }
                        if (stack.size() >= MAX_DEPTH) {
                            throw invalid(reader, "Elements nested deeper than " + MAX_DEPTH);
                        }
                        Builder element = new Builder(reader);
                        if (stack.isEmpty()) {
                            root = element;
                        } else {
                            stack.getLast().children.add(element);
                        }
                        stack.add(element);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            StringBuilder text = stack.getLast().text;
                            if (text.length() + reader.getTextLength() > MAX_TEXT) {
                                throw invalid(reader, "Element text longer than " + MAX_TEXT + " characters");
                            }
                            text.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> stack.removeLast();
                    default -> {
                        // comments, processing instructions, document start and end
                    }
                }
            }
            if (root == null) {
                throw new ValidationFailedException("Document has no root element");
            }
            return root.build();
        } catch (XMLStreamException e) {
            String where = e.getLocation() == null ? "" : " at line " + e.getLocation().getLineNumber();
            throw new ValidationFailedException("Document is not well-formed XML" + where + ": " + Quote.text(firstLine(e.getMessage())));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release beyond the byte array
                }
            }
        }
    }

    /**
     * Returns the attribute in no namespace.
     *
     * @param local the attribute name
     * @return the value, if present
     */
    Optional<String> attribute(String local) {
        return Optional.ofNullable(attributes.get(local));
    }

    /**
     * Returns a qualified attribute.
     *
     * @param ns the namespace URI
     * @param local the local name
     * @return the value, if present
     */
    Optional<String> attribute(String ns, String local) {
        return Optional.ofNullable(attributes.get("{" + ns + "}" + local));
    }

    /**
     * Returns the children with a local name in the element's own namespace.
     *
     * @param local the local name
     * @return the matching children in document order
     */
    List<XmlTree> children(String local) {
        return children.stream().filter(c -> c.namespace.equals(namespace) && c.name.equals(local)).toList();
    }

    /**
     * Returns the only child with a local name in the element's own namespace.
     *
     * @param local the local name
     * @return the child, if exactly one exists
     * @throws ValidationFailedException when there is more than one
     */
    Optional<XmlTree> child(String local) {
        List<XmlTree> found = children(local);
        if (found.size() > 1) {
            throw new ValidationFailedException("Element <" + name + "> at line " + line + " has more than one <"
                    + local + ">");
        }
        return found.stream().findFirst();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "parse error";
        }
        String[] lines = message.strip().split("\\R");
        return lines[lines.length - 1].replaceFirst("^Message: ", "");
    }

    private static ValidationFailedException invalid(XMLStreamReader reader, String detail) {
        return new ValidationFailedException(detail + " (line " + reader.getLocation().getLineNumber() + ")");
    }

    private static final class Builder {
        private final String namespace;
        private final String name;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private final List<Builder> children = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private final int line;

        Builder(XMLStreamReader reader) {
            this.namespace = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
            this.name = reader.getLocalName();
            this.line = reader.getLocation().getLineNumber();
            for (int i = 0; i < reader.getAttributeCount(); i++) {
                QName attribute = reader.getAttributeName(i);
                String ns = attribute.getNamespaceURI();
                String key = ns == null || ns.isEmpty() ? attribute.getLocalPart() : "{" + ns + "}" + attribute.getLocalPart();
                String value = reader.getAttributeValue(i);
                if (value.length() > MAX_ATTRIBUTE) {
                    throw invalid(reader, "Attribute " + Quote.of(attribute.getLocalPart()) + " of <" + name
                            + "> is longer than " + MAX_ATTRIBUTE + " characters");
                }
                attributes.put(key, value);
            }
        }

        XmlTree build() {
            return new XmlTree(namespace, name, attributes, children.stream().map(Builder::build).toList(),
                    text.toString(), line);
        }
    }
}
