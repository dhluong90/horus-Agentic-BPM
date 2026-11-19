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

    private static String normalise(String base, String[] value) {
        String path = base + (value.length == 0 ? "" : value[0]);
        return path.replaceAll("\\{([A-Za-z]+):[^}]*}", "{$1}");
    }
}
