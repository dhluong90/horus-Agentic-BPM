package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A DMN decision table over HTTP: import with {@code Content-Type: application/xml}, the same four eyes as YAML,
 * evaluate, export as DMN 1.5 and import the export as the next draft with the same canonical hash.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DmnImportIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestTokens.register(registry);
    }

    @Autowired
    Environment environment;

    @Test
    void TR_STD_25_a_dmn_file_imports_through_the_import_endpoint_and_round_trips() throws Exception {
        // Given the hand-written modeller table of card-fee
        byte[] file = resource("/rules/dmn/card-fee-modeller.dmn");

        // When alice imports and requests, bob approves, a teller evaluates
        Reply created = send("alice", "POST", "/rule-sets", file, "application/dmn+xml");
        send("alice", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null);
        Reply self = send("alice", "POST", "/rule-sets/card-fee/versions/1:approve", null, null);
        Reply approved = send("bob", "POST", "/rule-sets/card-fee/versions/1:approve", null, null);
        Reply decision = send("teller", "POST", "/decisions/card-fee:evaluate",
                "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json");

        // Then
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(created.json().path("sourceFormat").asString()).isEqualTo("DMN_XML");
        assertThat(self.json().path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(decision.json().path("matchedRuleIds").get(0).asString()).isEqualTo("DecisionRule_0fk2x7a");
        assertThat(decision.json().path("outputs").path("fee").asInt()).isZero();

        // When the version is exported as DMN and the export imported as the next draft
        HttpResponse<byte[]> export = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee/versions/1/export?format=dmn"))
                .header("Authorization", TestTokens.bearer("alice")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        Reply v2 = send("alice", "POST", "/rule-sets/card-fee/versions", export.body(), "application/xml");
        Reply badFormat = send("alice", "GET", "/rule-sets/card-fee/versions/1/export?format=xlsx", null, null);

        // Then
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Content-Disposition")).contains("attachment; filename=\"card-fee-v1.dmn\"");
        assertThat(new String(export.body(), java.nio.charset.StandardCharsets.UTF_8))
                .contains("https://www.omg.org/spec/DMN/20230324/MODEL/").doesNotContain("bank-a", "alice", "bob");
        assertThat(v2.status()).isEqualTo(201);
        assertThat(v2.json().path("version").asInt()).isEqualTo(2);
        assertThat(v2.json().path("canonicalHash").asString()).isEqualTo(created.json().path("canonicalHash").asString());
        assertThat(badFormat.status()).isEqualTo(400);
    }

    @Test
    void TR_STD_04_an_unsupported_cell_is_refused_with_its_rule_column_and_line() throws Exception {
        byte[] bad = new String(resource("/rules/dmn/card-fee-modeller.dmn"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("&gt;= 3", "abs(txCount) &gt;= 3").getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Reply refused = send("alice", "POST", "/rule-sets", bad, "application/xml");

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.contentType()).startsWith("application/problem+json");
        assertThat(refused.json().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(refused.json().path("detail").asString())
                .contains("Rule DecisionRule_1lh0wd3, input txCount", "function 'abs'", "(line 32)");
    }

    @Test
    void TR_STD_25_an_import_body_over_one_megabyte_is_refused_with_413() throws Exception {
        byte[] huge = new byte[1_048_577];
        java.util.Arrays.fill(huge, (byte) ' ');

        Reply xml = send("alice", "POST", "/rule-sets", huge, "application/xml");
        Reply yaml = send("alice", "POST", "/rule-sets/card-fee/versions", huge, "application/yaml");

        for (Reply refused : java.util.List.of(xml, yaml)) {
            assertThat(refused.status()).isEqualTo(413);
            assertThat(refused.contentType()).startsWith("application/problem+json");
            assertThat(refused.json().path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        }
    }

    Reply send(String user, String method, String path, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", TestTokens.bearer(user))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        // An approval or rejection must name the lock version it was decided on (#234); these tests approve a version
        // just requested, at lock version 1, unless they pass their own If-Match.
        if (path.matches(".*:(approve|reject)$")) {
            request.header("If-Match", "\"1\"");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    static byte[] resource(String path) {
        try (InputStream in = DmnImportIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
