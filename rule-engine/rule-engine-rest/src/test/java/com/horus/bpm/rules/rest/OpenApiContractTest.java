package com.horus.bpm.rules.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.internal.json.EngineJson;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.JsonNode;

/** Every implemented operation is in rule-engine/api/openapi.yaml with the same method and path, and nothing more. */
class OpenApiContractTest {

    @Test
    void controllers_and_openapi_list_the_same_operations() throws Exception {
        JsonNode spec = EngineJson.YAML.readTree(Files.readAllBytes(Path.of("../api/openapi.yaml")));
        Set<String> documented = new TreeSet<>();
        spec.get("paths").properties().forEach(path -> path.getValue().propertyNames()
                .forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey())));

        Set<String> implemented = new TreeSet<>();
        for (Class<?> controller : new Class<?>[] {RuleSetController.class, DecisionController.class}) {
            String base = controller.getAnnotation(RequestMapping.class).value()[0].replace(RestSupport.BASE, "");
            for (Method method : controller.getDeclaredMethods()) {
                PostMapping post = method.getAnnotation(PostMapping.class);
                GetMapping get = method.getAnnotation(GetMapping.class);
                if (post != null) {
                    implemented.add("POST " + normalise(base, post.value()));
                }
                if (get != null) {
                    implemented.add("GET " + normalise(base, get.value()));
                }
            }
        }

        assertThat(implemented).isEqualTo(documented);
    }

    @Test
    void TR_SEC_08_every_error_response_in_openapi_uses_the_one_problem_schema() throws Exception {
        // Given
        JsonNode spec = EngineJson.YAML.readTree(Files.readAllBytes(Path.of("../api/openapi.yaml")));
        java.util.List<String> findings = new java.util.ArrayList<>();

        // When
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            String name = operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();
            JsonNode responses = operation.getValue().get("responses");
            responses.properties().forEach(response -> {
                boolean error = response.getKey().startsWith("4") || response.getKey().startsWith("5");
                if (error && !"#/components/responses/Problem".equals(response.getValue().path("$ref").asString())) {
                    findings.add(name + " " + response.getKey() + " does not use the Problem response");
                }
            });
            if (!responses.has("500")) {
                findings.add(name + " does not list 500");
            }
        }));
        JsonNode problem = spec.at("/components/responses/Problem/content/application~1problem+json/schema/$ref");

        // Then
        assertThat(findings).isEmpty();
        assertThat(problem.asString()).isEqualTo("#/components/schemas/Problem");
        assertThat(spec.at("/components/schemas/Problem/required").toString())
                .contains("\"type\"", "\"code\"", "\"detail\"", "\"correlationId\"");
    }

    @Test
    void TR_IDEM_01_every_write_operation_in_openapi_accepts_an_idempotency_key_and_answers_409() throws Exception {
        // Given
        JsonNode spec = EngineJson.YAML.readTree(Files.readAllBytes(Path.of("../api/openapi.yaml")));
        java.util.List<String> findings = new java.util.ArrayList<>();

        // When
        spec.get("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            // A POST that writes nothing (a simulation, RLE-016) takes no key; its description says so.
            if (!"post".equals(operation.getKey())
                    || operation.getValue().path("description").asString().contains("Writes nothing")) {
                return;
            }
            String name = "POST " + path.getKey();
            boolean key = false;
            for (JsonNode parameter : operation.getValue().path("parameters")) {
                key |= "#/components/parameters/IdempotencyKey".equals(parameter.path("$ref").asString());
            }
            if (!key) {
                findings.add(name + " does not accept Idempotency-Key");
            }
            if (!operation.getValue().path("responses").has("409")) {
                findings.add(name + " does not list 409 for IDEMPOTENCY_IN_FLIGHT");
            }
            if (!operation.getValue().path("responses").has("422")) {
                findings.add(name + " does not list 422 for IDEMPOTENCY_KEY_REUSE_MISMATCH");
            }
        }));

        // Then
        assertThat(findings).isEmpty();
    }

    private static String normalise(String base, String[] value) {
        String path = base + (value.length == 0 ? "" : value[0]);
        return path.replaceAll("\\{([A-Za-z]+):[^}]*}", "{$1}");
    }
}
