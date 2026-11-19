package com.horus.bpm.host.autoconfigure;

import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.call;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.caller;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.lan;
import static com.horus.bpm.host.autoconfigure.HostMetadataEndpointsTest.minh;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.horus.bpm.host.spi.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contract tests: what the host serves validates against the JSON Schemas the cockpit is built against
 * (RLE-019, TR-UI-08), and the OpenAPI document points at those same schemas.
 */
class HostMetadataContractTest {

    private static final Path OPENAPI = Path.of(System.getProperty("bpm.openapi.file", "api/openapi.yaml"));

    /** A host that sets every optional field the schemas allow. */
    @Configuration(proxyBeanMethods = false)
    static class FullyConfiguredRules {

        @Bean
        ModuleDescriptor ruleEngineModuleDescriptor() {
            return new ModuleDescriptor("rules", "0.2.0-rc.1", "v1", List.of("rules.simulation", "rules.grid-editor"),
                    Map.of("authoring.cockpit-drafts", false, "evaluation.max-batch", 1000, "time-zone", "Asia/Ho_Chi_Minh"),
                    TestHosts.RULES_PERMISSIONS, Map.of("ROLE_PRICING_APPROVER", List.of("rules:approve")));
        }
    }

    @Test
    void RLE_019_meta_and_me_validate_against_the_published_schemas() {
        List<JwtRequestPostProcessor> callers = List.of(
                lan(),
                minh(),
                caller("thu", "bank-a", "ROLE_TELLER"),
                caller("binh", "bank-b", "rules:evaluate", "rules:log:read")
                        .jwt(token -> token.subject("binh").claim("tenant_id", "bank-b")
                                .claim("preferred_username", "binh.tran").claim("groups", List.of("fees", "ops"))));
        for (Class<?> rules : List.of(TestHosts.RulesWithoutMapping.class, FullyConfiguredRules.class)) {
            TestHosts.runner().withUserConfiguration(rules).withPropertyValues("horus.bpm.host-id=loans").run(context -> {
                MockMvc mvc = TestHosts.mockMvc(context);
                for (JwtRequestPostProcessor caller : callers) {
                    String meta = call(mvc, get("/api/bpm/meta").with(caller)).getResponse().getContentAsString();
                    String me = call(mvc, get("/api/bpm/me").with(caller)).getResponse().getContentAsString();

                    JsonSchemas.assertValid("bpm-meta.v1.schema.json", meta);
                    JsonSchemas.assertValid("bpm-me.v1.schema.json", me);
                }
            });
        }
    }

    @Test
    void RLE_019_openapi_document_references_the_published_schemas_and_its_examples_validate() throws Exception {
        JsonNode openapi = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(OPENAPI));

        assertThat(openapi.path("openapi").asText()).isEqualTo("3.1.0");
        Map<String, String> schemaByPath = Map.of(
                "/api/bpm/meta", "bpm-meta.v1.schema.json",
                "/api/bpm/me", "bpm-me.v1.schema.json");
        for (Map.Entry<String, String> entry : schemaByPath.entrySet()) {
            JsonNode get = openapi.path("paths").path(entry.getKey()).path("get");
            JsonNode json = get.path("responses").path("200").path("content").path("application/json");
            String ref = json.path("schema").path("$ref").asText();

            assertThat(ref).isEqualTo("../../cockpit/api/schema/" + entry.getValue());
            assertThat(OPENAPI.toAbsolutePath().getParent().resolve(ref).normalize()).exists();
            JsonSchemas.assertValid(entry.getValue(), new ObjectMapper().writeValueAsString(json.path("example")));
            assertThat(get.path("responses").fieldNames()).toIterable().contains("200", "304", "401", "403");
        }
        assertThat(openapi.path("components").path("headers").path("CacheControl").path("schema").path("const").asText())
                .isEqualTo(HostMetadataController.CACHE_CONTROL);
    }
}
