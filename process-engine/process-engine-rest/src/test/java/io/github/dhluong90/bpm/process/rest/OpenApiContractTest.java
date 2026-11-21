package io.github.dhluong90.bpm.process.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import tools.jackson.databind.JsonNode;

/** The controllers and process-engine/api/openapi.yaml list the same operations and the same problem codes. */
class OpenApiContractTest {

    private static final JsonNode SPEC = read();

    private static JsonNode read() {
        try {
            return EngineJson.YAML.readTree(Files.readAllBytes(Path.of("../api/openapi.yaml")));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void controller_and_openapi_list_the_same_operations() {
        Set<String> documented = new TreeSet<>();
        SPEC.get("paths").properties().forEach(path -> path.getValue().propertyNames()
                .forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey())));

        Set<String> implemented = new TreeSet<>();
        for (Method method : java.util.stream.Stream.of(ProcessController.class, AgentReplayController.class,
                MigrationController.class, MigrationExecutionController.class, LayoutController.class, IncidentController.class)
                .flatMap(type -> java.util.Arrays.stream(type.getDeclaredMethods())).toList()) {
            PostMapping post = method.getAnnotation(PostMapping.class);
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (post != null) {
                implemented.add("POST " + post.value()[0]);
            }
            if (get != null) {
                implemented.add("GET " + get.value()[0]);
            }
            PutMapping put = method.getAnnotation(PutMapping.class);
            if (put != null) {
                implemented.add("PUT " + put.value()[0]);
            }
        }
        assertThat(implemented).isEqualTo(documented);
    }

    @Test
    void every_problem_code_the_rest_layer_maps_is_documented() {
        Set<String> documented = new HashSet<>();
        SPEC.at("/components/schemas/Problem/properties/code/enum").forEach(code -> documented.add(code.stringValue()));

        assertThat(documented).containsAll(ProcessProblemDetails.STATUS.keySet())
                .contains("INTERNAL", "DEFINITION_INTEGRITY_FAILED", "PRECONDITION_REQUIRED", "SELF_APPROVAL_FORBIDDEN",
                        "TENANT_NOT_ALLOWED", "PAYLOAD_TOO_LARGE", "BPMN_UNSUPPORTED_ELEMENT", "BPMN_UNSAFE_XML");
    }
}
