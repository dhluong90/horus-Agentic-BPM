package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The one place that creates XML readers for BPMN, on the baseline the DMN reader uses:
 * <ul>
 *   <li>{@code XMLInputFactory.newDefaultFactory()}, so a StAX implementation on the host's classpath cannot take
 *       over;</li>
 *   <li>{@code SUPPORT_DTD=false}, {@code IS_SUPPORTING_EXTERNAL_ENTITIES=false}, {@code ACCESS_EXTERNAL_DTD=""},
 *       an {@code XMLResolver} that throws, and a no-op {@code XMLReporter};</li>
 *   <li>UTF-8 input only: bytes are decoded strictly once, and a declaration naming another encoding is refused, so
 *       the stored source and the parsed document never differ.</li>
 * </ul>
 * Readers must still refuse {@code DTD} and {@code ENTITY_REFERENCE} events and enforce {@link Limits} while
 * streaming. Security review required for any change.
 */
public final class XmlHardening {

    private XmlHardening() {
    }

    /**
     * @param limits the limits of the read
     * @return a factory with DTD and external entity support off
     */
    public static XMLInputFactory newInputFactory(Limits limits) {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        // Only the five predefined entities and character references exist without a DTD; they are replaced.
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("External resources are not resolved");
        });
        factory.setXMLReporter((message, errorType, relatedInformation, location) -> { });
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        } catch (IllegalArgumentException ignored) {
            // the default JDK factory supports it
        }
        return factory;
    }

    /**
     * Decodes a document strictly as UTF-8 (a leading byte order mark is dropped).
     *
     * @param source the bytes
     * @return the text
     * @throws BpmnUnsafeXmlException when the bytes are not valid UTF-8
     */
    public static String utf8(byte[] source) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(source)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new BpmnUnsafeXmlException("The document is not valid UTF-8");
        }
    }

    /**
     * Refuses a declaration that names an encoding other than UTF-8.
     *
     * @param reader a reader positioned at the start of the document
     * @throws BpmnUnsafeXmlException when another encoding is declared
     */
    public static void requireUtf8Declaration(XMLStreamReader reader) {
        String declared = reader.getCharacterEncodingScheme();
        if (declared != null && !declared.equalsIgnoreCase("UTF-8")) {
            throw new BpmnUnsafeXmlException("The XML declaration names an encoding other than UTF-8; BPMN files must be UTF-8");
        }
    }
}
