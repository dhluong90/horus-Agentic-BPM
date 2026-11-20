package io.github.dhluong90.bpm.process.bpmn.xml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.BpmnUnsafeXmlException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
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
    void only_utf8_is_accepted() throws Exception {
        assertThatThrownBy(() -> XmlHardening.utf8(new byte[] {'<', 'a', '>', (byte) 0xE9, '<', '/', 'a', '>'}))
                .isInstanceOf(BpmnUnsafeXmlException.class);
        assertThat(XmlHardening.utf8("﻿<a>café</a>".getBytes(StandardCharsets.UTF_8))).isEqualTo("<a>café</a>");

        XMLStreamReader latin1 = XmlHardening.newInputFactory(Limits.DEFAULTS)
                .createXMLStreamReader(new StringReader("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a/>"));
        assertThatThrownBy(() -> XmlHardening.requireUtf8Declaration(latin1)).isInstanceOf(BpmnUnsafeXmlException.class);
        XMLStreamReader utf8 = XmlHardening.newInputFactory(Limits.DEFAULTS)
                .createXMLStreamReader(new StringReader("<?xml version=\"1.0\" encoding=\"UTF-8\"?><a/>"));
        XmlHardening.requireUtf8Declaration(utf8);
    }
}
