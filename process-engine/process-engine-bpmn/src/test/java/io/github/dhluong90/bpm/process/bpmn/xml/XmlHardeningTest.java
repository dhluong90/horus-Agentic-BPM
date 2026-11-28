package io.github.dhluong90.bpm.process.bpmn.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import org.junit.jupiter.api.Test;

class XmlHardeningTest {

    @Test
    void the_factory_is_the_jdk_default_with_dtd_and_external_entities_off() {
        XMLInputFactory factory = XmlHardening.newInputFactory(Limits.DEFAULTS);

        assertThat(factory.getClass()).isEqualTo(XMLInputFactory.newDefaultFactory().getClass());
        assertThat(factory.getProperty(XMLInputFactory.SUPPORT_DTD)).isEqualTo(false);
        assertThat(factory.getProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES)).isEqualTo(false);
        assertThat(factory.getProperty(XMLConstants.ACCESS_EXTERNAL_DTD)).isEqualTo("");
    }

    @Test
    void the_resolver_refuses_every_external_resource() throws Exception {
        XMLInputFactory factory = XmlHardening.newInputFactory(Limits.DEFAULTS);
        assertThatThrownBy(() -> factory.getXMLResolver().resolveEntity(null, "http://127.0.0.1:9/x.dtd", null, null))
                .hasMessageContaining("not resolved");
    }

    @Test
    void the_declared_encoding_decides_how_the_bytes_are_read() {
        // 0xE9 is "e acute" in ISO-8859-1 and not valid UTF-8
        assertThatThrownBy(() -> XmlHardening.utf8(new byte[] {'<', 'a', '>', (byte) 0xE9, '<', '/', 'a', '>'}))
                .isInstanceOf(BpmnUnsafeXmlException.class);
        assertThat(XmlHardening.utf8("\uFEFF<a>caf\u00e9</a>".getBytes(StandardCharsets.UTF_8))).isEqualTo("<a>caf\u00e9</a>");

        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>Nguy\u00e9n</a>"
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThat(XmlHardening.decode(latin1)).endsWith("<a>Nguy\u00e9n</a>");
        byte[] windows = "<?xml version='1.0' encoding='windows-1252'?><a>\u20ac</a>"
                .getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThat(XmlHardening.decode(windows)).endsWith("<a>\u20ac</a>");
        assertThat(XmlHardening.decode("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a>caf\u00e9</a>"
                .getBytes(StandardCharsets.UTF_8))).endsWith("<a>caf\u00e9</a>");
        assertThat(XmlHardening.decode("<a>caf\u00e9</a>".getBytes(StandardCharsets.UTF_8))).isEqualTo("<a>caf\u00e9</a>");
    }

    @Test
    void an_encoding_that_hides_the_markup_or_does_not_match_the_bytes_is_refused() {
        assertThatThrownBy(() -> XmlHardening.decode("<?xml version=\"1.0\" encoding=\"UTF-16\"?><a/>"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(BpmnUnsafeXmlException.class)
                .hasMessageContaining("does not read");
        assertThatThrownBy(() -> XmlHardening.decode("<?xml version=\"1.0\" encoding=\"NO-SUCH\"?><a/>"
                .getBytes(StandardCharsets.UTF_8))).isInstanceOf(BpmnUnsafeXmlException.class);
        // US-ASCII declared, a byte above 0x7F
        assertThatThrownBy(() -> XmlHardening.decode(new byte[] {'<', '?', 'x', 'm', 'l', ' ', 'e', 'n', 'c', 'o', 'd', 'i',
                'n', 'g', '=', '"', 'U', 'S', '-', 'A', 'S', 'C', 'I', 'I', '"', '?', '>', '<', 'a', '>', (byte) 0xE9,
                '<', '/', 'a', '>'})).isInstanceOf(BpmnUnsafeXmlException.class);
        // a byte order mark says UTF-8
        byte[] bomAndLatin1 = ("\u00ef\u00bb\u00bf<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a/>")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> XmlHardening.decode(bomAndLatin1)).isInstanceOf(BpmnUnsafeXmlException.class);
    }
}
