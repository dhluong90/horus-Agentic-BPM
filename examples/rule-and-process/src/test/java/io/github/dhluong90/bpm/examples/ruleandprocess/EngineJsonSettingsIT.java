package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PRE-045 on the sample host: the host's own JSON settings (snake case, dates as timestamps) apply to the host's
 * controllers and never to an engine payload. Runs unchanged in the Spring Boot 4.x legs (host Jackson 3) and the
 * 3.5 legs (host Jackson 2 beside the engine's Jackson 3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass",
    "spring.jackson.property-naming-strategy=SNAKE_CASE"
})
@ActiveProfiles("dev")
@Testcontainers
class EngineJsonSettingsIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Jackson 3 moved the switch from SerializationFeature to DateTimeFeature; each line binds its own key.
        if (SpringBootVersion.getVersion().startsWith("3.")) {
            registry.add("spring.jackson.serialization.write-dates-as-timestamps", () -> "true");
        } else {
            registry.add("spring.jackson.datatype.datetime.write-dates-as-timestamps", () -> "true");
        }
    }

    /** A host endpoint written by the host's own message converter, so the host settings are visibly live. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HostEndpoint {
        record Receipt(String applicantName, Instant receivedAt) {
        }

        @Bean
        RouterFunction<ServerResponse> receiptEndpoint() {
            return RouterFunctions.route().GET("/api/cards/receipt", request -> ServerResponse.ok()
                    .body(new Receipt("Tran Thi B", Instant.parse("2027-02-01T09:00:00Z")))).build();
        }
    }

    @LocalServerPort
    int port;

    @Test
    void PRE_045_engine_rest_bodies_ignore_host_json_settings() throws Exception {
        String body = "{\"definitionKey\":\"card-fee-review\",\"businessKey\":\"APP-1001\",\"variables\":"
                + "{\"applicationId\":\"APP-1001\",\"customerId\":\"C-002\",\"applicantName\":\"Tran Thi B\"}}";

        HttpResponse<String> started = send("tuan", "POST", "/api/bpm/processes/v1/instances", body,
                "01JD2X5K9T1Q4N8V2M7B3C6D0E");
        HttpResponse<String> read = send("tuan", "GET", "/api/bpm/processes/v1/instances/"
                + JSON.readTree(started.body()).get("id").stringValue(), null, null);
        HttpResponse<String> host = send("tuan", "GET", "/api/cards/receipt", null, null);

        // The engine read the camelCase body with its own mapper: a host-bound body would have lost definitionKey.
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        for (HttpResponse<String> engine : java.util.List.of(started, read)) {
            JsonNode instance = JSON.readTree(engine.body());
            assertThat(instance.get("businessKey").stringValue()).isEqualTo("APP-1001");
            assertThat(instance.get("definitionKey").stringValue()).isEqualTo("card-fee-review");
            assertThat(instance.has("business_key")).isFalse();
            assertThat(instance.get("startedAt").isString()).as("an ISO 8601 string, not a timestamp").isTrue();
            assertThat(Instant.parse(instance.get("startedAt").stringValue())).isNotNull();
            assertThat(instance.at("/variables/applicantName").stringValue()).isEqualTo("Tran Thi B");
            assertThat(instance.at("/tasks/0/lockVersion").isNumber()).isTrue();
        }
        // The host's own controller still answers with the host's settings.
        assertThat(host.statusCode()).as(host.body()).isEqualTo(200);
        JsonNode receipt = JSON.readTree(host.body());
        assertThat(receipt.get("applicant_name").stringValue()).isEqualTo("Tran Thi B");
        assertThat(receipt.get("received_at").isNumber()).as(host.body()).isTrue();
    }

    @Test
    void PRE_045_engine_lists_and_problems_keep_camel_case_under_host_settings() throws Exception {
        HttpResponse<String> list = send("tuan", "GET", "/api/bpm/processes/v1/definitions", null, null);
        HttpResponse<String> problem = send("tuan", "GET", "/api/bpm/processes/v1/instances?limit=0", null, null);

        JsonNode page = JSON.readTree(list.body());
        assertThat(page.has("nextCursor")).isTrue();
        assertThat(page.has("hasNext")).isTrue();
        assertThat(page.at("/data/0/latestVersion").isNumber()).isTrue();
        assertThat(page.at("/data/0/lastChangedAt").isString()).isTrue();
        assertThat(JSON.readTree(problem.body()).has("correlationId")).isTrue();
    }

    private HttpResponse<String> send(String user, String method, String path, String body, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
