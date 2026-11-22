package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.dhluong90.bpm.process.api.PermissionDeniedException;
import io.github.dhluong90.bpm.process.api.RoleFanOutExceededException;
import io.github.dhluong90.bpm.process.api.StepCatalogue;
import io.github.dhluong90.bpm.process.api.StepCatalogueView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The step catalogue body (#103, PRE-032) and the role fan-out problem (#408, PRE-216) as the controller and the
 * problem handler write them, validated against {@code process-engine/api/openapi.yaml}. The service is a double that
 * answers what {@code bank-a}'s host registered, or the refusal a caller without {@code processes:author} gets.
 */
class StepCatalogueRestContractTest {

    private static final OpenApiBodies BODIES = OpenApiBodies.load(ProcessRestContractTest.CONTRACT);
    private static final String PATH = ProcessController.BASE + "/step-catalogue";

    private volatile String caller = "carol";

    private final StepCatalogue catalogue = () -> {
        if (caller.equals("erin")) {
            throw new PermissionDeniedException("Needs processes:author");
        }
        if (caller.equals("dung")) {
            throw new RoleFanOutExceededException(101, 100);
        }
        return new StepCatalogueView(
                List.of(new StepCatalogueView.Handler("cardProfiles.load", "local", Map.of(), false),
                        new StepCatalogueView.Handler("kyc.verifyDocuments", "remote",
                                Map.of("type", "object", "required", List.of("customerId"),
                                        "properties", Map.of("customerId", Map.of("type", "string"))), true)),
                List.of(new StepCatalogueView.Agent("doc-assessor", Map.of())),
                List.of(new StepCatalogueView.RuleSet("card-fee", 3, "FIRST", Map.of("tier", "string"),
                        Map.of("fee", "integer")),
                        new StepCatalogueView.RuleSet("agent-output-guardrails", 2, "FIRST", Map.of("score", "integer"),
                                Map.of("allow", "boolean", "reasons", "list<string>"))),
                List.of(new StepCatalogueView.RuleSet("agent-output-guardrails", 2, "FIRST", Map.of("score", "integer"),
                        Map.of("allow", "boolean", "reasons", "list<string>"))),
                List.of("vn-banking-hours"), List.of("card-officer", "credit-officer"));
    };

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StepCatalogueController(catalogue))
            .setControllerAdvice(new ProcessProblemDetails()).build();

    @Test
    void PRE_032_catalogue_body_matches_the_contract_and_carries_an_etag() throws Exception {
        // When carol reads the catalogue
        MvcResult read = mvc.perform(get(PATH)).andReturn();
        BODIES.assertExchange(read);

        // Then the handlers, agent, both rule set lists, the calendar and the roles, with a body hash as ETag
        String body = read.getResponse().getContentAsString();
        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("\"name\":\"kyc.verifyDocuments\"").contains("\"mode\":\"remote\"")
                .contains("\"compensable\":true").contains("\"calendars\":[\"vn-banking-hours\"]")
                .contains("\"guardrail\":[{\"name\":\"agent-output-guardrails\"");
        String etag = read.getResponse().getHeader("ETag");
        assertThat(etag).matches("\"[0-9a-f]{32}\"");

        // And the same ETag as If-None-Match is 304 with no body
        MvcResult unchanged = mvc.perform(get(PATH).header("If-None-Match", etag)).andReturn();
        assertThat(unchanged.getResponse().getStatus()).isEqualTo(304);
        assertThat(unchanged.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void PRE_032_a_caller_without_processes_author_is_403_PERMISSION_DENIED() throws Exception {
        caller = "erin";
        MvcResult refused = mvc.perform(get(PATH)).andReturn();
        BODIES.assertExchange(refused);
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("\"code\":\"PERMISSION_DENIED\"");
    }

    @Test
    void PRE_216_role_fan_out_problem_is_400_with_the_cap_and_the_number_of_queues() throws Exception {
        caller = "dung";
        // The double throws it from this endpoint; the contract documents the problem on GET /tasks, where it arises
        MvcResult refused = mvc.perform(get(PATH)).andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("\"code\":\"ROLE_FAN_OUT_EXCEEDED\"")
                .contains("\"cap\":100").contains("\"provided\":101");
    }
}
