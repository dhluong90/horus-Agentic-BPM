package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Definition import limits, id syntax and source retention on the example host, over REST (PRE-035, PRE-039,
 * PRE-042). Auto-approval is off, so an import is a draft.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.definitions.auto-approve=false",
    "horus.bpm.processes.jobs.enabled=false"})
@ActiveProfiles("dev")
@Testcontainers
class SpecGapsIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String BASE = "/api/bpm/processes/v1/definitions";

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

    @Test
    void PRE_035_definition_size_limits_enforced() throws Exception {
        // Given documents of 500 steps, 501 steps, and 300 steps padded above 1 MiB
        // When each is imported
        HttpResponse<String> exactly = post(chain("size-500", 500, 0));
        HttpResponse<String> tooMany = post(chain("size-501", 501, 0));
        HttpResponse<String> tooBig = post(chain("size-big", 300, 1_200_000));

        // Then the first is a draft; 501 steps is 422 VALIDATION_FAILED, over 1 MiB is 413 PAYLOAD_TOO_LARGE, nothing is stored
        assertThat(exactly.statusCode()).as(exactly.body()).isEqualTo(201);
        assertThat(JSON.readTree(exactly.body()).get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(tooMany.statusCode()).as(tooMany.body()).isEqualTo(422);
        assertThat(JSON.readTree(tooMany.body()).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(tooMany.body()).contains("TOO_MANY_NODES").contains("500");
        assertThat(tooBig.statusCode()).as(tooBig.body()).isEqualTo(413);
        assertThat(JSON.readTree(tooBig.body()).get("code").stringValue()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(get(BASE + "/size-501/versions/1").statusCode()).isEqualTo(404);
        assertThat(get(BASE + "/size-big/versions/1").statusCode()).isEqualTo(404);
    }

    @Test
    void PRE_042_bpmn_style_step_ids_kept() throws Exception {
        // Given a definition with modeller-style ids and another whose step id starts with a digit
        String good = doc("ids-ok", "<bpmn:startEvent id=\"StartEvent_1\"/>"
                + "<bpmn:userTask id=\"Activity_0x1y2z3\" name=\"Check\" horus:assignee=\"role:KYC_OFFICER\" horus:resultVariable=\"r\"/>"
                + "<bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"StartEvent_1\" targetRef=\"Activity_0x1y2z3\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"Activity_0x1y2z3\" targetRef=\"end\"/>");
        String bad = doc("ids-bad", "<bpmn:startEvent id=\"start\"/>"
                + "<bpmn:userTask id=\"1-bad\" name=\"Check\" horus:assignee=\"role:KYC_OFFICER\" horus:resultVariable=\"r\"/>"
                + "<bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f1\" sourceRef=\"start\" targetRef=\"1-bad\"/>"
                + "<bpmn:sequenceFlow id=\"f2\" sourceRef=\"1-bad\" targetRef=\"end\"/>");

        // When both are imported
        HttpResponse<String> ok = post(good);
        HttpResponse<String> refused = post(bad);

        // Then the first keeps its ids in the stored definition and the second is 422 naming the id
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        String stored = get(BASE + "/ids-ok/versions/1/export?format=bpmn").body();
        assertThat(stored).contains("Activity_0x1y2z3").contains("StartEvent_1");
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("1-bad");
    }

    @Test
    void PRE_039_import_keeps_source_document_and_fixed_hash() throws Exception {
        // Given a BPMN file with a comment
        String source = keepSourceFile();

        // When it is imported and read back with its source
        post(source);
        HttpResponse<String> back = get(BASE + "/keep-src/versions/1?include=source");

        // Then the original text comes back byte for byte
        assertThat(back.statusCode()).as(back.body()).isEqualTo(200);
        assertThat(JSON.readTree(back.body()).get("sourceDocument").stringValue()).isEqualTo(source);
    }

    /** What PRE-039 guarantees today: the format and the hash of the source are kept, the definition hash is stable. */
    @Test
    void BPMN_import_records_source_format_and_hash_and_the_definition_hash_is_stable() throws Exception {
        // Given a BPMN file with a comment
        String source = keepSourceFile();

        // When it is imported and read twice
        JsonNode imported = JSON.readTree(post(source).body());
        JsonNode first = JSON.readTree(get(BASE + "/keep-src/versions/1").body());
        JsonNode second = JSON.readTree(get(BASE + "/keep-src/versions/1").body());

        // Then format and source hash are recorded, and the definition hash does not change between reads
        assertThat(first.get("sourceFormat").stringValue()).isEqualTo("BPMN_XML");
        assertThat(first.get("sourceHash").stringValue()).isEqualTo(sha256(source));
        assertThat(first.get("definitionHash").stringValue()).isEqualTo(imported.get("definitionHash").stringValue());
        assertThat(second.get("definitionHash").stringValue()).isEqualTo(first.get("definitionHash").stringValue());
    }

    private static String keepSourceFile() {
        return doc("keep-src", "<!-- keep this comment -->"
                + "<bpmn:startEvent id=\"start\"/><bpmn:endEvent id=\"end\"/>"
                + "<bpmn:sequenceFlow id=\"f\" sourceRef=\"start\" targetRef=\"end\"/>");
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void RLE_033_import_as_new_version_creates_next_draft_or_refuses_whole() throws Exception {
        String rules = "/api/bpm/rules/v1/rule-sets";
        String valid = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/rules/card-fee.yaml"));
        String v3 = valid.replace("then: { fee: \"10000 VND\" }", "then: { fee: \"12000 VND\" }");
        String otherSet = valid.replace("ruleSet: card-fee", "ruleSet: kyc-risk");
        String bad = valid.replace("when: { txCount: \">= 3\" }", "when: { balance: \">= 50000000\" }");

        HttpResponse<String> first = rulePost(rules + "/card-fee/versions", v3, "k-401");
        HttpResponse<String> replay = rulePost(rules + "/card-fee/versions", v3, "k-401");
        HttpResponse<String> mismatch = rulePost(rules + "/card-fee/versions", otherSet, "k-402");
        HttpResponse<String> invalid = rulePost(rules + "/card-fee/versions", bad, "k-403");
        HttpResponse<String> exists = rulePost(rules, v3, "k-404");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(JSON.readTree(first.body()).get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
        assertThat(JSON.readTree(replay.body()).get("version").intValue())
                .isEqualTo(JSON.readTree(first.body()).get("version").intValue());
        assertThat(mismatch.statusCode()).as(mismatch.body()).isEqualTo(400);
        assertThat(JSON.readTree(mismatch.body()).get("code").stringValue()).isEqualTo("INPUT_INVALID");
        assertThat(invalid.statusCode()).as(invalid.body()).isEqualTo(422);
        assertThat(JSON.readTree(invalid.body()).get("code").stringValue()).isEqualTo("VALIDATION_FAILED");
        assertThat(exists.statusCode()).as(exists.body()).isEqualTo(409);
        assertThat(JSON.readTree(exists.body()).get("code").stringValue()).isEqualTo("RULE_SET_EXISTS");
        int created = JSON.readTree(first.body()).get("version").intValue();
        assertThat(get(rules + "/card-fee/versions/" + (created + 1)).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> rulePost(String path, String yaml, String key) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic("lan")).header("Content-Type", "application/yaml")
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.ofString(yaml)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String chain(String key, int nodes, int padBytes) {
        StringBuilder b = new StringBuilder("<bpmn:startEvent id=\"n0\"/>");
        for (int i = 1; i < nodes - 1; i++) {
            b.append("<bpmn:userTask id=\"n").append(i).append("\" horus:assignee=\"role:KYC_OFFICER\" horus:resultVariable=\"r\"/>");
        }
        b.append("<bpmn:endEvent id=\"n").append(nodes - 1).append("\"/>");
        for (int i = 0; i < nodes - 1; i++) {
            b.append("<bpmn:sequenceFlow id=\"f").append(i).append("\" sourceRef=\"n").append(i)
                    .append("\" targetRef=\"n").append(i + 1).append("\"/>");
        }
        if (padBytes > 0) {
            b.append("<!--").append("x".repeat(padBytes)).append("-->");
        }
        return doc(key, b.toString());
    }

    private static String doc(String key, String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<bpmn:definitions"
                + " xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""
                + " xmlns:horus=\"urn:horus-bpm:bpmn:extensions:v1\" id=\"Definitions_" + key + "\""
                + " targetNamespace=\"urn:horus-bpm:examples\" expressionLanguage=\"urn:horus-bpm:expr:v1\">"
                + "<bpmn:process id=\"" + key + "\" isExecutable=\"true\">" + body
                + "</bpmn:process></bpmn:definitions>";
    }

    private HttpResponse<String> post(String bpmn) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE))
                .header("Authorization", basic("lan")).header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(bpmn)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic("lan")).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8));
    }
}
