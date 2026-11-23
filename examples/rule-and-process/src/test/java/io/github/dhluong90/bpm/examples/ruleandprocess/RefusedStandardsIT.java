package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Both engines refuse a CMMN file, and elements outside the current stage, by name over HTTP, and nothing of a
 * refused file is written (PRE-107, PRE-108, RLE-060, RLE-061).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class RefusedStandardsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String CMMN_NAMESPACE = "http://www.omg.org/spec/CMMN/20151109/MODEL";
    private static final String CMMN = "<definitions xmlns=\"" + CMMN_NAMESPACE + "\" id=\"claim-case\">\n"
            + "  <case id=\"c1\">\n    <!-- a case -->\n    <casePlanModel id=\"cpm1\"/>\n  </case>\n</definitions>\n";
    private static final String DETAIL = "CMMN is not supported; model the case as a BPMN process";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void PRE_107_a_cmmn_file_is_422_cmmn_unsupported_at_the_process_import_and_writes_nothing() throws Exception {
        long before = count("pre_definition");

        HttpResponse<String> response = post("/api/bpm/processes/v1/definitions", "application/xml", CMMN);

        assertCmmn(response);
        assertThat(count("pre_definition")).isEqualTo(before);
    }

    @Test
    void RLE_060_a_cmmn_file_is_422_cmmn_unsupported_at_the_rule_set_import_and_writes_nothing() throws Exception {
        long before = count("rle_version") + count("rle_rule_set");

        HttpResponse<String> response = post("/api/bpm/rules/v1/rule-sets/claim-case/versions", "application/xml", CMMN);

        assertCmmn(response);
        assertThat(count("rle_version") + count("rle_rule_set")).isEqualTo(before);
    }

    @Test
    void PRE_108_unsupported_bpmn_elements_are_listed_with_their_stage_and_nothing_is_stored() throws Exception {
        long before = count("pre_definition");
        String loan = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" id=\"d\" targetNamespace=\"urn:t\">\n"
                + "<bpmn:process id=\"loan-refusal\" isExecutable=\"true\">\n"
                + "<bpmn:startEvent id=\"start\"/>\n"
                + "<bpmn:inclusiveGateway id=\"ig-1\"/>\n"
                + "<bpmn:eventBasedGateway id=\"notify\" instantiate=\"true\"/>\n"
                + "<bpmn:adHocSubProcess id=\"adhoc-1\"/>\n"
                + "</bpmn:process></bpmn:definitions>\n";

        HttpResponse<String> response = post("/api/bpm/processes/v1/definitions", "application/xml", loan);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").stringValue()).isEqualTo("BPMN_UNSUPPORTED_ELEMENT");
        assertThat(body.get("unsupportedElements")).extracting(p -> p.get("element").stringValue() + ":" + p.get("stage").stringValue()
                + ":" + p.get("line").intValue()).containsExactly("inclusiveGateway:S1:5", "eventBasedGateway with instantiate:S3:6",
                "adHocSubProcess:S3:7");
        assertThat(count("pre_definition")).isEqualTo(before);
    }

    @Test
    void RLE_061_dmn_elements_outside_the_stage_are_listed_and_nothing_is_stored() throws Exception {
        long before = count("rle_version") + count("rle_rule_set");
        String fees = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<definitions xmlns=\"https://www.omg.org/spec/DMN/20230324/MODEL/\" id=\"fees\" name=\"fees\">\n"
                + "<businessKnowledgeModel id=\"bkm-1\" name=\"Rounding\"/>\n"
                + "<decisionService id=\"ds-1\" name=\"Pricing\"/>\n"
                + "</definitions>\n";

        HttpResponse<String> response = post("/api/bpm/rules/v1/rule-sets/fees/versions", "application/xml", fees);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").stringValue()).isEqualTo("DMN_UNSUPPORTED_ELEMENT");
        assertThat(body.get("unsupportedElements")).extracting(e -> e.get("element").stringValue() + ":" + e.get("elementId").stringValue()
                + ":" + e.get("line").intValue() + ":" + e.get("stage").stringValue())
                .containsExactly("businessKnowledgeModel:bkm-1:3:S2", "decisionService:ds-1:4:S2");
        assertThat(count("rle_version") + count("rle_rule_set")).isEqualTo(before);
    }

    private static void assertCmmn(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t)
                .startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").stringValue()).isEqualTo("CMMN_UNSUPPORTED");
        assertThat(body.get("detail").stringValue()).isEqualTo(DETAIL);
        assertThat(body.get("namespace").stringValue()).isEqualTo(CMMN_NAMESPACE);
        assertThat(body.get("line").intValue()).isEqualTo(1);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private HttpResponse<String> post(String path, String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("lan:it-pass".getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
