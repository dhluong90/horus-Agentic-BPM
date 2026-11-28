package io.github.dhluong90.bpm.process.bpmn.xml;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *   <li>Bytes are decoded strictly once, in the encoding the declaration names (UTF-8 by default), and the parser
 *       reads that text, so the stored source and the parsed document never differ.</li>
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

    /** The declaration at the start of a document; it is ASCII in every encoding this class accepts. */
    private static final Pattern DECLARATION = Pattern.compile(
            "^(?:\u00ef\u00bb\u00bf)?\\s*<\\?xml[^>]*?\\sencoding\\s*=\\s*[\"']([A-Za-z][A-Za-z0-9._-]*)[\"']");

    /**
     * Decodes a document strictly in the encoding its XML declaration names, UTF-8 when it names none (a leading byte
     * order mark is dropped). The text is decoded once and parsed from that text, so the stored source and the parsed
     * document never differ. The accepted encodings are UTF-8, US-ASCII, ISO-8859-n and windows-125n, which keep the
     * ASCII markup in place; others (UTF-16, UTF-32) and names the JVM does not know are refused.
     *
     * @param source the bytes
     * @return the text
     * @throws BpmnUnsafeXmlException when the bytes are not valid in the declared encoding, or the encoding is not
     *     accepted
     */
    public static String decode(byte[] source) {
        Matcher declaration = DECLARATION.matcher(new String(source, 0, Math.min(source.length, 200),
                StandardCharsets.ISO_8859_1));
        if (!declaration.find()) {
            return utf8(source);
        }
        Charset charset;
        try {
            charset = Charset.forName(declaration.group(1));
        } catch (IllegalArgumentException e) {
            throw new BpmnUnsafeXmlException("The XML declaration names an encoding this engine does not read: "
                    + declaration.group(1));
        }
        String name = charset.name();
        boolean accepted = name.equals("UTF-8") || name.equals("US-ASCII") || name.startsWith("ISO-8859-")
                || name.startsWith("windows-125");
        if (!accepted) {
            throw new BpmnUnsafeXmlException("The XML declaration names an encoding this engine does not read: "
                    + declaration.group(1) + "; use UTF-8, US-ASCII, ISO-8859-n or windows-125n");
        }
        if (name.equals("UTF-8")) {
            return utf8(source);
        }
        if (source.length >= 3 && (source[0] & 0xFF) == 0xEF && (source[1] & 0xFF) == 0xBB && (source[2] & 0xFF) == 0xBF) {
            throw new BpmnUnsafeXmlException("The document starts with a UTF-8 byte order mark but declares "
                    + declaration.group(1));
        }
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString();
        } catch (CharacterCodingException e) {
            throw new BpmnUnsafeXmlException("The document is not valid " + name);
        }
    }
}
