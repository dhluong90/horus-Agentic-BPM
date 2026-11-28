package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A pending version with no recorded author over HTTP on the example host (RLE-054, PD-105). alice imports and
 * requests card-fee v1; the test then clears its author, as on a row written before the author was recorded. bob's
 * approval, sent with {@code If-Match}, is {@code 409 AUTHOR_UNKNOWN}; the version stays pending and offers no
 * {@code approve}. bob rejects it, alice imports the change again, and bob approves the new version.
 */
@Testcontainers
class AuthorUnknownHostIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> AUTHOR = List.of("rules:read", "rules:author");
    static final List<String> APPROVER = List.of("rules:read", "rules:approve");
    static final String BASE = "/api/bpm/rules/v1/rule-sets/card-fee";

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "50000 VND" } }
            """;

    @Test
    void RLE_054_approving_a_pending_version_with_no_recorded_author_is_409_author_unknown() throws Exception {
        String alice = TestTokens.bearerFor("alice", "bank-a", AUTHOR);
        String bob = TestTokens.bearerFor("bob", "bank-a", APPROVER);

        try (ConfigurableApplicationContext host = start()) {
            int port = Integer.parseInt(host.getEnvironment().getProperty("local.server.port"));

            // Given card-fee v1 pending, requested by alice, and then stripped of its author
            assertThat(send(port, alice, "POST", "/api/bpm/rules/v1/rule-sets", CARD_FEE, null, null).status())
                    .isEqualTo(201);
            Reply requested = send(port, alice, "POST", BASE + "/versions/1:request-publish", null, null, "rp-1");
            assertThat(requested.status()).isEqualTo(200);
            long lock = requested.json().path("lockVersion").asLong();
            clearAuthor("bank-a", "card-fee", 1);

            // When bob approves with If-Match
            Reply refused = send(port, bob, "POST", BASE + "/versions/1:approve",
                    "{\"comment\":\"reviewed\",\"expectedCanonicalHash\":\"" + requested.json().path("canonicalHash").asString()
                            + "\"}", lock, "ap-1");

            // Then 409 AUTHOR_UNKNOWN as problem details, and v1 stays pending, unchanged and without approve
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.contentType()).startsWith("application/problem+json");
            assertThat(refused.json().path("code").asString()).isEqualTo("AUTHOR_UNKNOWN");
            assertThat(refused.json().path("type").asString()).endsWith("/wiki/Common-Error-Codes#author_unknown");
            assertThat(refused.json().path("detail").asString()).contains("card-fee v1", "no recorded author");
            JsonNode v1 = send(port, bob, "GET", BASE + "/versions/1", null, null, null).json();
            assertThat(v1.path("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(v1.path("lockVersion").asLong()).isEqualTo(lock);
            assertThat(v1.path("createdBy").isNull() || v1.path("createdBy").isMissingNode()).isTrue();
            List<String> actions = new ArrayList<>();
            v1.path("allowedActions").forEach(a -> actions.add(a.asString()));
            assertThat(actions).containsExactly("reject");

            // And: bob rejects it, alice imports the change again, and bob approves the new version
            assertThat(send(port, bob, "POST", BASE + "/versions/1:reject",
                    "{\"reason\":\"author unknown, submit again\",\"expectedCanonicalHash\":\""
                            + requested.json().path("canonicalHash").asString() + "\"}", lock, "rj-1").status()).isEqualTo(200);
            assertThat(send(port, alice, "POST", BASE + "/versions", CARD_FEE, null, "im-2").status()).isEqualTo(201);
            Reply again = send(port, alice, "POST", BASE + "/versions/2:request-publish", null, null, "rp-2");
            assertThat(again.json().path("createdBy").asString()).isEqualTo("alice");
            Reply approved = send(port, bob, "POST", BASE + "/versions/2:approve",
                    "{\"comment\":\"reviewed\",\"expectedCanonicalHash\":\"" + again.json().path("canonicalHash").asString()
                            + "\"}", again.json().path("lockVersion").asLong(), "ap-2");
            assertThat(approved.status()).isEqualTo(200);
            assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
        }
    }

    /** Clears the author of a version in the host database, as on a row written before the author was recorded. */
    private static void clearAuthor(String tenant, String ruleSet, int version) throws SQLException {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement("""
                        UPDATE rle_version v SET created_by = NULL
                        FROM rle_rule_set s
                        WHERE v.tenant_id = ? AND s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                          AND s.name = ? AND v.version_num = ?
                        """)) {
            statement.setString(1, tenant);
            statement.setString(2, ruleSet);
            statement.setInt(3, version);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private static ConfigurableApplicationContext start() {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        TestTokens.properties().forEach((name, value) -> args.add("--" + name + "=" + value));
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    /**
     * Sends a request. A body starting with {@code {} is JSON, any other body YAML.
     *
     * @param ifMatch the lock version for {@code If-Match}, or null
     * @param key the {@code Idempotency-Key}, or null
     */
    private static Reply send(int port, String bearer, String method, String path, String body, Long ifMatch,
            String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (body != null) {
            request.header("Content-Type", body.startsWith("{") ? "application/json" : "application/yaml");
        }
        if (ifMatch != null) {
            request.header("If-Match", "\"" + ifMatch + "\"");
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    record Reply(int status, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
