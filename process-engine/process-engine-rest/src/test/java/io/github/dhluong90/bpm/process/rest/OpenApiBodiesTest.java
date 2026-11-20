package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The contract checker catches drift and says where: operation, field and schema path. */
class OpenApiBodiesTest {

    private static final Path CONTRACT = ProcessRestContractTest.CONTRACT;
    private static final String BASE = ProcessController.BASE;
    private static final String VALID_INSTANCE = """
            {"id":"01JD0000000000000000000010","definitionKey":"card-fee-review","definitionVersion":1,
             "businessKey":null,"status":"RUNNING","variables":{},"tokens":[],"tasks":[],"lockVersion":0,
             "startedBy":"user:lan","startedAt":"2027-01-04T09:00:00Z","endedAt":null}""";

    /** A test double of the REST layer that answers with {@code definitionKey} renamed to {@code processKey}. */
    @RestController
    static class DriftingController {

        @GetMapping(BASE + "/instances/{id}")
        ResponseEntity<byte[]> instance(@PathVariable("id") String id) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(VALID_INSTANCE.replace("\"definitionKey\"", "\"processKey\"").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void a_renamed_field_in_a_live_response_fails_naming_the_operation_the_field_and_the_schema_path() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DriftingController()).build();
        MvcResult result = mvc.perform(get(BASE + "/instances/01JD0000000000000000000010")).andReturn();

        assertThatThrownBy(() -> OpenApiBodies.load(CONTRACT).assertExchange(result))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("getInstance GET /instances/{id} -> 200 application/json")
                .hasMessageContaining("field $.definitionKey")
                .hasMessageContaining("schema #/components/schemas/ProcessInstance/required")
                .hasMessageContaining("field $.processKey")
                .hasMessageContaining("schema #/components/schemas/ProcessInstance/additionalProperties");
    }

    @Test
    void the_same_body_with_the_original_field_name_passes() {
        assertThat(OpenApiBodies.load(CONTRACT).responseViolations("GET", BASE + "/instances/01JD0000000000000000000010",
                200, "application/json", VALID_INSTANCE)).isEmpty();
    }

    @Test
    void a_renamed_optional_field_fails_only_when_objects_are_closed() {
        String renamed = VALID_INSTANCE.replace("\"startedBy\"", "\"starter\"");

        assertThat(OpenApiBodies.load(CONTRACT).responseViolations("GET", "/instances/01JD0000000000000000000010", 200,
                "application/json", renamed)).singleElement().asString().contains("field $.starter");
        assertThat(OpenApiBodies.loadOpen(CONTRACT).responseViolations("GET", "/instances/01JD0000000000000000000010",
                200, "application/json", renamed)).isEmpty();
    }

    @Test
    void a_wrong_type_an_unknown_enum_value_and_a_bad_date_are_each_reported() {
        String wrong = VALID_INSTANCE.replace("\"lockVersion\":0", "\"lockVersion\":\"0\"")
                .replace("\"RUNNING\"", "\"PAUSED\"").replace("2027-01-04T09:00:00Z", "yesterday");

        assertThat(OpenApiBodies.load(CONTRACT).responseViolations("GET", "/instances/01JD0000000000000000000010", 200,
                "application/json", wrong))
                .anySatisfy(v -> assertThat(v).contains("field $.lockVersion", "/lockVersion/type"))
                .anySatisfy(v -> assertThat(v).contains("field $.status", "/status/enum"))
                .anySatisfy(v -> assertThat(v).contains("field $.startedAt", "/startedAt/format"));
    }

    @Test
    void a_problem_without_its_code_fails_against_the_problem_schema() {
        String problem = """
                {"type":"https://example.org/x","title":"Conflict","status":409,"correlationId":"c-1"}""";

        assertThat(OpenApiBodies.load(CONTRACT).responseViolations("POST", "/tasks/01JD0000000000000000000011:complete",
                409, "application/problem+json", problem))
                .singleElement().asString()
                .startsWith("completeTask POST /tasks/{id}:complete -> 409 application/problem+json")
                .contains("field $.code", "schema #/components/schemas/Problem/required");
    }

    @Test
    void an_undocumented_status_or_media_type_fails() {
        OpenApiBodies bodies = OpenApiBodies.load(CONTRACT);

        assertThat(bodies.responseViolations("GET", "/instances/01JD0000000000000000000010", 418, "application/json",
                "{}")).singleElement().asString().contains("status 418 is not documented");
        assertThat(bodies.responseViolations("GET", "/instances/01JD0000000000000000000010", 404, "application/json",
                "{}")).singleElement().asString().contains("media type application/json is not documented");
    }

    @Test
    void request_bodies_are_checked_too() {
        assertThat(OpenApiBodies.load(CONTRACT).requestViolations("POST", BASE + "/instances",
                "application/json;charset=UTF-8", "{\"definitionKey\":\"Not A Key\"}"))
                .singleElement().asString()
                .startsWith("startInstance POST /instances request application/json")
                .contains("field $.definitionKey", "schema #/components/schemas/StartRequest/properties/definitionKey"
                        + "/pattern");
    }

    @Test
    void a_path_outside_the_contract_is_reported() {
        assertThatThrownBy(() -> OpenApiBodies.load(CONTRACT).responseViolations("DELETE", "/instances/x", 204, null, ""))
                .isInstanceOf(AssertionError.class).hasMessageContaining("DELETE /instances/x is not an operation");
    }
}
