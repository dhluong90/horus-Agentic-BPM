package io.github.dhluong90.bpm.process.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.rest.ProcessController;
import io.github.dhluong90.bpm.process.rest.ProcessProblemDetails;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * PRE-391 over HTTP: the publish request of a draft with open bindings is {@code 422 VALIDATION_FAILED} naming each
 * binding, and the version is still a draft afterwards.
 */
class OpenBindingsRestIT {

    private static final String NS = "xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
            + " xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"d\" targetNamespace=\"urn:t\"";

    @Test
    void PRE_391_the_rest_publish_request_of_a_draft_with_open_bindings_is_422_and_names_each_binding() throws Exception {
        // Given a draft with a service step that has no handler and a user step with no assignee
        TestEngine.reset();
        TestEngine engine = TestEngine.replica("pod-1", TestClock.at("2027-03-27T03:00:00Z")).autoApprove(false).build();
        String xml = "<bpmn:definitions " + NS + "><bpmn:process id=\"invoice-approval\" isExecutable=\"true\">"
                + "<bpmn:startEvent id=\"s\"/><bpmn:serviceTask id=\"post\" implementation=\"##WebService\"/>"
                + "<bpmn:userTask id=\"approve\" horus:resultVariable=\"r\"/><bpmn:endEvent id=\"e\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"s\" targetRef=\"post\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"post\" targetRef=\"approve\"/>"
                + "<bpmn:sequenceFlow id=\"f3\" sourceRef=\"approve\" targetRef=\"e\"/></bpmn:process></bpmn:definitions>";
        engine.identity.as("bank-a", "pipeline", "processes:deploy", "processes:read");
        DefinitionVersion draft = engine.services.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, xml.getBytes(StandardCharsets.UTF_8), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProcessController(engine.services, engine.services,
                engine.services, null, 1_048_576)).setControllerAdvice(new ProcessProblemDetails()).build();

        // When alice requests publish over HTTP
        engine.identity.as("bank-a", "alice", "processes:author", "processes:read");
        String body = mvc.perform(post("/api/bpm/processes/v1/definitions/invoice-approval/versions/1:request-approval")
                .header("If-Match", "\"" + draft.lockVersion() + "\"").header("Idempotency-Key", "k-1"))
                // Then it is 422 and the body names the steps with a binding still open
                .andExpect(status().isUnprocessableEntity()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("VALIDATION_FAILED", "HANDLER_MISSING", "\"post\"", "ASSIGNEE_INVALID", "\"approve\"");
        engine.identity.as("bank-a", "auditor", "processes:read");
        assertThat(engine.services.find(draft.ref()).orElseThrow().status()).isEqualTo("DRAFT");
    }
}
