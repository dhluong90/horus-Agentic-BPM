package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
 * The definition lifecycle over REST, with auto-approval off: a KYC onboarding process imported as a draft by
 * {@code lan}, her own approval refused, {@code minh}'s approval making it active, a reject with and without a
 * reason, and an older version refused over a newer active one (PRE-018, PRE-019, PRE-021).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass", "horus.bpm.processes.definitions.auto-approve=false",
    "horus.bpm.processes.jobs.enabled=false"})
@ActiveProfiles("dev")
@Testcontainers
class DefinitionLifecycleIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String BASE = "/api/bpm/processes/v1/definitions";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Test
    void PRE_018_import_creates_draft_not_active_version() throws Exception {
        // Given the start-up import with auto-approval off
        JsonNode seeded = json(get("lan", BASE + "/card-fee-review/versions/1"), 200);

        // Then the seeded definition is a draft, and nothing can start on it
        assertThat(seeded.get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(seeded.get("source").stringValue()).isEqualTo("STARTUP");
        HttpResponse<String> start = send("tuan", "POST", "/api/bpm/processes/v1/instances",
                "{\"definitionKey\":\"card-fee-review\",\"businessKey\":\"IT-DRAFT-1\",\"variables\":{}}", null,
                "application/json");
        assertThat(start.statusCode()).isEqualTo(404);
        assertThat(json(start, 404).get("code").stringValue()).isEqualTo("NO_EFFECTIVE_VERSION");
    }

    @Test
    void PRE_019_no_maker_of_a_definition_version_can_approve() throws Exception {
        // Given a KYC onboarding draft imported and requested by lan; lan and minh both hold processes:approve
        JsonNode draft = importKyc("Review identity documents", 201);
        String v = BASE + "/kyc-onboarding/versions/" + draft.get("version").intValue();
        assertThat(draft.get("status").stringValue()).isEqualTo("DRAFT");
        JsonNode pending = json(send("lan", "POST", v + ":request-approval", "", "\"0\"", "application/json"), 200);
        assertThat(pending.get("requestedBy").stringValue()).isEqualTo("lan");

        // When lan approves her own request
        String hash = pending.get("definitionHash").stringValue();
        HttpResponse<String> self = send("lan", "POST", v + ":approve", reviewed(hash), "\"1\"", "application/json");

        // Then it is refused and nothing is written
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(json(self, 403).get("code").stringValue()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        JsonNode still = json(get("lan", v), 200);
        assertThat(still.get("status").stringValue()).isEqualTo("PENDING_APPROVAL");
        assertThat(still.get("lockVersion").intValue()).isEqualTo(1);

        // When minh approves without If-Match, then with the lock version
        assertThat(send("minh", "POST", v + ":approve", "", null, "application/json").statusCode()).isEqualTo(428);
        HttpResponse<String> approved = send("minh", "POST", v + ":approve", "{\"expectedDefinitionHash\":\"" + hash
                + "\",\"comment\":\"documents step checked\"}", "\"1\"", "application/json");

        // Then it is active, approved by minh, and the history names both people
        JsonNode body = json(approved, 200);
        assertThat(body.get("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(body.get("approvedBy").stringValue()).isEqualTo("minh");
        assertThat(approved.headers().firstValue("ETag")).contains("\"2\"");
        JsonNode history = json(get("minh", v + "/history"), 200).get("items");
        assertThat(history).extracting(h -> h.get("action").stringValue() + " " + h.get("actor").stringValue())
                .containsExactly("IMPORTED lan", "REQUESTED lan", "APPROVED minh");
    }

    @Test
    void PRE_021_reject_returns_draft_and_superseded_approval_refused() throws Exception {
        // Given a pending version 3 and a newer version 4 of the same key
        JsonNode v3 = importKyc("Review identity documents, third", 201);
        String p3 = BASE + "/kyc-onboarding/versions/" + v3.get("version").intValue();
        json(send("lan", "POST", p3 + ":request-approval", "", "\"0\"", "application/json"), 200);

        // When minh rejects without a reason, then with one
        HttpResponse<String> noReason = send("minh", "POST", p3 + ":reject", "{}", "\"1\"", "application/json");
        JsonNode rejected = json(send("minh", "POST", p3 + ":reject", "{\"reason\":\"missing fraud check\"}", "\"1\"",
                "application/json"), 200);

        // Then the first is 400 and the second returns the version to draft with the reason
        assertThat(noReason.statusCode()).isEqualTo(400);
        assertThat(json(noReason, 400).get("code").stringValue()).isEqualTo("INPUT_INVALID");
        assertThat(rejected.get("status").stringValue()).isEqualTo("DRAFT");
        assertThat(rejected.get("decisionComment").stringValue()).isEqualTo("missing fraud check");

        // When version 3 is requested again, version 4 is approved, and then minh approves version 3
        json(send("lan", "POST", p3 + ":request-approval", "", "\"2\"", "application/json"), 200);
        JsonNode v4 = importKyc("Review identity documents, fourth", 201);
        String p4 = BASE + "/kyc-onboarding/versions/" + v4.get("version").intValue();
        json(send("lan", "POST", p4 + ":request-approval", "", "\"0\"", "application/json"), 200);
        json(send("minh", "POST", p4 + ":approve", reviewed(v4.get("definitionHash").stringValue()), "\"1\"",
                "application/json"), 200);
        HttpResponse<String> overtake = send("minh", "POST", p3 + ":approve", reviewed(v3.get("definitionHash")
                .stringValue()), "\"3\"", "application/json");

        // Then the older version is refused and stays pending
        assertThat(overtake.statusCode()).isEqualTo(409);
        assertThat(json(overtake, 409).get("code").stringValue()).isEqualTo("VERSION_SUPERSEDED");
        assertThat(json(get("minh", p3), 200).get("status").stringValue()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void TR_TEN_02_an_unknown_version_answers_definition_not_found_as_another_tenants_version_does() throws Exception {
        HttpResponse<String> missing = get("minh", BASE + "/kyc-onboarding/versions/99");

        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing, 404).get("code").stringValue()).isEqualTo("DEFINITION_NOT_FOUND");
    }

    private JsonNode importKyc(String reviewName, int expected) throws Exception {
        String bpmn = resource().replace("Review identity documents", reviewName);
        return json(send("lan", "POST", BASE, bpmn, null, "application/xml"), expected);
    }

    private static String resource() throws IOException {
        try (InputStream in = DefinitionLifecycleIT.class.getResourceAsStream("/processes/kyc-onboarding.bpmn")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static JsonNode json(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String user, String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String user, String method, String path, String body, String ifMatch,
            String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":it-pass").getBytes(StandardCharsets.UTF_8));
    }

    /** The body of an approval that names the content reviewed (PRE-177). */
    private static String reviewed(String definitionHash) {
        return "{\"expectedDefinitionHash\":\"" + definitionHash + "\"}";
    }
}
