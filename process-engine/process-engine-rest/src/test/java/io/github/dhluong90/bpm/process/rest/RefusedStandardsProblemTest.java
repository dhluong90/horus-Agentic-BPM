package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.CmmnUnsupportedException;
import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The Problem Details of a refused standard: a named code, the namespace and line, and the list with its stage. */
class RefusedStandardsProblemTest {

    private final ProcessProblemDetails details = new ProcessProblemDetails();

    @Test
    void PRE_107_cmmn_is_422_with_the_detail_the_namespace_and_the_line() {
        ResponseEntity<byte[]> response = details.handle(
                new CmmnUnsupportedException("http://www.omg.org/spec/CMMN/20151109/MODEL", 1),
                new ServletWebRequest(new MockHttpServletRequest()));

        JsonNode body = body(response);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(body.get("code").stringValue()).isEqualTo("CMMN_UNSUPPORTED");
        assertThat(body.get("detail").stringValue()).isEqualTo("CMMN is not supported; model the case as a BPMN process");
        assertThat(body.get("namespace").stringValue()).isEqualTo("http://www.omg.org/spec/CMMN/20151109/MODEL");
        assertThat(body.get("line").intValue()).isEqualTo(1);
        assertThat(body.has("problems")).isFalse();
    }

    @Test
    void PRE_108_each_entry_carries_element_id_line_and_stage_and_the_list_says_when_it_was_cut() {
        List<DefinitionProblem> problems = List.of(
                new DefinitionProblem("BPMN_UNSUPPORTED_ELEMENT", "is not supported", "boundaryEvent", "be-timeout", 42, "S1"),
                new DefinitionProblem("BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED", "language", "conditionExpression", "f2", 88,
                        "not planned"));
        List<DefinitionProblem> warnings = List.of(new DefinitionProblem("FOREIGN_EXTENSION_IGNORED", "is in namespace x",
                "camunda:class", "score", 30));

        JsonNode body = body(details.handle(new BpmnUnsupportedElementException(problems, warnings, true),
                new ServletWebRequest(new MockHttpServletRequest())));

        assertThat(body.get("code").stringValue()).isEqualTo("BPMN_UNSUPPORTED_ELEMENT");
        assertThat(body.at("/problems/0/element").stringValue()).isEqualTo("boundaryEvent");
        assertThat(body.at("/problems/0/elementId").stringValue()).isEqualTo("be-timeout");
        assertThat(body.at("/problems/0/line").intValue()).isEqualTo(42);
        assertThat(body.at("/problems/0/stage").stringValue()).isEqualTo("S1");
        assertThat(body.at("/problems/1/code").stringValue()).isEqualTo("BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED");
        assertThat(body.at("/problems/1/stage").stringValue()).isEqualTo("not planned");
        assertThat(body.get("violationsTruncated").booleanValue()).isTrue();
        assertThat(body.at("/warnings/0/code").stringValue()).isEqualTo("FOREIGN_EXTENSION_IGNORED");
        assertThat(body.at("/warnings/0/line").intValue()).isEqualTo(30);
    }

    private static JsonNode body(ResponseEntity<byte[]> response) {
        return JsonMapper.builder().build().readTree(new String(response.getBody(), StandardCharsets.UTF_8));
    }
}
