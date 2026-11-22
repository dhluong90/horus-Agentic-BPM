package io.github.dhluong90.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.UnsupportedElement;
import io.github.dhluong90.bpm.rules.api.UnsupportedStandardException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Problem Details of a refused standard: a named code, the namespace and line, and the list with its stage. */
class RefusedStandardsProblemTest {

    private final ProblemDetails details = new ProblemDetails();

    @Test
    void RLE_060_cmmn_is_422_with_the_detail_the_namespace_and_the_line() {
        ResponseEntity<byte[]> response = details.handle(
                UnsupportedStandardException.cmmn("http://www.omg.org/spec/CMMN/20151109/MODEL", 1),
                new ServletWebRequest(new MockHttpServletRequest()));

        JsonNode body = body(response);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(body.get("code").stringValue()).isEqualTo("CMMN_UNSUPPORTED");
        assertThat(body.get("detail").stringValue()).isEqualTo("CMMN is not supported; model the case as a BPMN process");
        assertThat(body.get("namespace").stringValue()).isEqualTo("http://www.omg.org/spec/CMMN/20151109/MODEL");
        assertThat(body.get("line").intValue()).isEqualTo(1);
    }

    @Test
    void RLE_061_each_entry_carries_element_id_line_stage_and_message_in_unsupported_elements() {
        JsonNode body = body(details.handle(UnsupportedStandardException.dmnElements(List.of(
                new UnsupportedElement("businessKnowledgeModel", "bkm-1", 55, "S2"),
                new UnsupportedElement("decisionService", "ds-1", 70, "S2"))),
                new ServletWebRequest(new MockHttpServletRequest())));

        assertThat(body.get("code").stringValue()).isEqualTo("DMN_UNSUPPORTED_ELEMENT");
        assertThat(body.get("unsupportedElements")).hasSize(2);
        assertThat(body.at("/unsupportedElements/0/element").stringValue()).isEqualTo("businessKnowledgeModel");
        assertThat(body.at("/unsupportedElements/0/elementId").stringValue()).isEqualTo("bkm-1");
        assertThat(body.at("/unsupportedElements/0/line").intValue()).isEqualTo(55);
        assertThat(body.at("/unsupportedElements/0/message").stringValue()).contains("businessKnowledgeModel", "stage S2");
        assertThat(body.at("/unsupportedElements/1/stage").stringValue()).isEqualTo("S2");
        assertThat(body.has("violationsTruncated")).isFalse();
    }

    @Test
    void RLE_061_a_list_over_100_entries_is_cut_and_says_so() {
        List<UnsupportedElement> found = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            found.add(new UnsupportedElement("knowledgeSource", "ks" + i, i + 1, "S1"));
        }

        JsonNode body = body(details.handle(UnsupportedStandardException.dmnElements(found),
                new ServletWebRequest(new MockHttpServletRequest())));

        assertThat(body.get("unsupportedElements")).hasSize(100);
        assertThat(body.get("violationsTruncated").booleanValue()).isTrue();
    }

    private static JsonNode body(ResponseEntity<byte[]> response) {
        return JsonMapper.builder().build().readTree(new String(response.getBody(), StandardCharsets.UTF_8));
    }
}
