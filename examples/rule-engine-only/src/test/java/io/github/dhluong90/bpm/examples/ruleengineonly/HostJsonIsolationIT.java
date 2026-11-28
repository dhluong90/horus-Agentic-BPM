package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The engine's JSON is the engine's own: the host sets snake case and dates as numbers for its own
 * controllers, and the engine still answers in camelCase with ISO 8601 timestamps.
 *
 * <p>Runs unchanged in the Spring Boot 4.x leg (host Jackson 3) and the 3.5 leg (host Jackson 2). The
 * dates-as-numbers setting has a different property name on each line, so it is set for the line on the
 * classpath. The 3.5 leg's check that the host's Jackson 2 stays at its pinned version is the dependency
 * tree step in CI ({@code tools/check-jackson-coexistence.py}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
@Import(HostJsonIsolationIT.HostLoanController.class)
@Testcontainers
class HostJsonIsolationIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Instant SUBMITTED_AT = Instant.parse("2026-12-15T02:00:00Z");

    /** Spring Boot 4 moved the date features of Jackson 3 to {@code spring.jackson.datatype.datetime}. */
    static final boolean BOOT_4 = ClassUtils.isPresent(
            "org.springframework.boot.jackson.autoconfigure.JacksonProperties", HostJsonIsolationIT.class.getClassLoader());

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(BOOT_4
                ? "spring.jackson.datatype.datetime.write-dates-as-timestamps"
                : "spring.jackson.serialization.write-dates-as-timestamps", () -> "true");
        TestTokens.register(registry);
    }

    /** A controller of the host's own, answering with the host's Jackson settings. */
    @RestController
    static class HostLoanController {
        record LoanApplication(String applicationId, String customerTier, Instant submittedAt) {
        }

        @GetMapping("/host/loan-applications/latest")
        LoanApplication latest() {
            return new LoanApplication("LA-1001", "GOLD", SUBMITTED_AT);
        }
    }

    @Autowired
    Environment environment;

    @Test
    void RLE_023_engine_json_ignores_host_jackson_settings() throws Exception {
        // Given card-fee v1 imported and published by two different users
        Reply imported = send("alice", "POST", "/api/bpm/rules/v1/rule-sets", resource("/rules/card-fee.yaml"), "application/yaml");
        assertThat(send("alice", "POST", "/api/bpm/rules/v1/rule-sets/card-fee/versions/1:request-publish", null, null).status())
                .isEqualTo(200);
        assertThat(send("bob", "POST", "/api/bpm/rules/v1/rule-sets/card-fee/versions/1:approve", null, null, "\"1\"")
                .status()).isEqualTo(200);

        // When a Gold customer with 5 transactions is evaluated
        Reply decision = send("teller", "POST", "/api/bpm/rules/v1/decisions/card-fee:evaluate",
                "{ \"inputs\": { \"tier\": \"GOLD\", \"txCount\": 5 } }".getBytes(StandardCharsets.UTF_8), "application/json");
        Reply host = send("teller", "GET", "/host/loan-applications/latest", null, null);

        // Then the engine answers camelCase with an ISO 8601 timestamp
        assertThat(imported.status()).isEqualTo(201);
        assertThat(allFieldNames(imported.json())).isNotEmpty().noneMatch(name -> name.contains("_"));
        assertThat(decision.status()).isEqualTo(200);
        assertThat(fieldNames(decision.json())).contains("matchedRuleIds", "decisionId", "evaluatedAt")
                .doesNotContain("matched_rule_ids", "decision_id", "evaluated_at");
        assertThat(allFieldNames(decision.json())).noneMatch(name -> name.contains("_"));
        assertThat(decision.json().path("matchedRuleIds").toString()).isEqualTo("[\"R1\"]");
        assertThat(decision.json().path("evaluatedAt").isString()).isTrue();
        DateTimeFormatter.ISO_INSTANT.parse(decision.json().path("evaluatedAt").asString());
        assertThat(decision.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":0}");

        // And the host's own controller still follows the host's settings: snake case, dates as numbers
        assertThat(host.status()).isEqualTo(200);
        assertThat(fieldNames(host.json())).containsExactlyInAnyOrder("application_id", "customer_tier", "submitted_at");
        assertThat(host.json().path("submitted_at").isNumber()).isTrue();
        assertThat(host.json().path("submitted_at").asLong()).isEqualTo(SUBMITTED_AT.getEpochSecond());
    }

    /** Every property name in the document, nested objects and arrays included. */
    static List<String> allFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                names.add(entry.getKey());
                names.addAll(allFieldNames(entry.getValue()));
            });
        } else if (node.isArray()) {
            node.values().forEach(child -> names.addAll(allFieldNames(child)));
        }
        return names;
    }

    static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    Reply send(String user, String method, String path, byte[] body, String contentType) throws Exception {
        return send(user, method, path, body, contentType, null);
    }

    Reply send(String user, String method, String path, byte[] body, String contentType, String ifMatch)
            throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> send(user, "GET", p, null, null, null).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path))
                .header("Authorization", TestTokens.bearer(user))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    static byte[] resource(String path) {
        try (InputStream in = HostJsonIsolationIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
