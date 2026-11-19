package io.github.dhluong90.bpm.process.bpmn.xml;

import static org.assertj.core.api.Assertions.assertThat;

import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;

class BpmnCodecsTest {

    @Test
    void every_element_name_has_exactly_one_codec() {
        BpmnCodecs codecs = new BpmnCodecs();
        assertThat(codecs.all()).hasSize(9);
        assertThat(codecs.forName(new QName(BpmnNamespaces.BPMN, "userTask"))).isPresent();
        assertThat(codecs.forName(new QName(BpmnNamespaces.BPMNDI, "BPMNShape"))).isPresent();
        assertThat(codecs.forName(new QName(BpmnNamespaces.BPMN, "choreographyTask"))).isEmpty();
    }

    @Test
    void limits_defaults_match_the_design() {
        assertThat(Limits.DEFAULTS.maxBytes()).isEqualTo(1_048_576);
        assertThat(Limits.DEFAULTS.maxDepth()).isEqualTo(32);
        assertThat(Limits.DEFAULTS.maxElements()).isEqualTo(5_000);
    }
}
