package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** QA review of #147 over HTTP: exact 413 boundaries, import limit, cursor tampering, limits, tenant scope. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
@Testcontainers
class EditorApiQaIT {

    static final int COMMAND = 16_384;
    static final int EVALUATE = 1_048_576;
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final List<String> AUTHOR_APPROVER = List.of("rules:read", "rules:author", "rules:approve", "rules:evaluate");
    static final List<String> APPROVER = List.of("rules:read", "rules:approve", "rules:evaluate");
    static final Map<String, List<String>> PERMISSIONS = Map.of("lan", AUTHOR_APPROVER, "minh", APPROVER,
            "dan", List.of("rules:read"));

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-12-15T02:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestTokens.register(registry);
    }

    @Autowired
    Environment environment;

    static byte[] padded(String json, int size) {
        byte[] head = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[size];
        System.arraycopy(head, 0, out, 0, head.length);
        java.util.Arrays.fill(out, head.length, size, (byte) ' ');
        return out;
    }

    @Test
    void RLE_044_a_command_body_of_exactly_16384_bytes_is_accepted_and_16385_is_413() throws Exception {
        String t = "qa-cmd-limit";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", EditorApiIT.cardFee("card-fee"), "application/yaml", null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null);

        Reply over = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                padded("{\"reason\":\"too big\"}", COMMAND + 1), "application/json", null);
        Reply exact = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                padded("{\"reason\":\"exactly at the limit\"}", COMMAND), "application/json", null);
        assertThat(over.status()).as(over.body()).isEqualTo(413);
        assertThat(exact.status()).as(exact.body()).isEqualTo(200);

        send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null);
        Reply approveOver = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve",
                padded("{\"comment\":\"too big\"}", COMMAND + 1), "application/json", null);
        Reply approveExact = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve",
                padded("{\"comment\":\"exactly at the limit\"}", COMMAND), "application/json", null);
        assertThat(approveOver.status()).as(approveOver.body()).isEqualTo(413);
        assertThat(approveExact.status()).as(approveExact.body()).isEqualTo(200);
    }

    @Test
    void RLE_044_an_evaluate_body_of_exactly_1048576_bytes_is_accepted_and_one_more_is_413() throws Exception {
        String t = "qa-eval-limit";
        publish(t, "card-fee");
        String input = "{\"inputs\":{\"tier\":\"SILVER\",\"txCount\":5}}";

        Reply exact = send(t, "minh", "POST", "/decisions/card-fee:evaluate", padded(input, EVALUATE), "application/json", null);
        Reply over = send(t, "minh", "POST", "/decisions/card-fee:evaluate", padded(input, EVALUATE + 1), "application/json", null);

        assertThat(exact.status()).as(exact.body()).isEqualTo(200);
        assertThat(over.status()).as(over.body()).isEqualTo(413);
    }

    @Test
    void RLE_044_an_import_over_1048576_bytes_is_413_payload_too_large() throws Exception {
        // DESIGN 4.2: imports are read through the bounded reader at RuleSetReader.MAX_SOURCE_BYTES
        String t = "qa-import-limit";
        byte[] yaml = EditorApiIT.cardFee("card-fee");
        byte[] big = new byte[EVALUATE + 1];
        System.arraycopy(yaml, 0, big, 0, yaml.length);
        java.util.Arrays.fill(big, yaml.length, big.length, (byte) '\n');

        Reply reply = send(t, "lan", "POST", "/rule-sets", big, "application/yaml", null);

        assertThat(reply.status()).as(reply.body()).isEqualTo(413);
        assertThat(reply.json().path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void RLE_039_a_tampered_cursor_and_bad_limits_are_400_query_invalid() throws Exception {
        String t = "qa-cursor";
        publish(t, "card-fee");
        String nul = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("rule-sets:\u0000".getBytes(StandardCharsets.UTF_8));

        for (String query : List.of("cursor=" + nul, "limit=abc", "limit=", "limit=2147483648", "limit=-1",
                "cursor=%3D%3D%3D", "cursor=" + "A".repeat(401))) {
            Reply reply = send(t, "dan", "GET", "/rule-sets?" + query, null, null, null);
            assertThat(reply.status()).as(query + " -> " + reply.body()).isEqualTo(400);
            assertThat(reply.json().path("code").asString()).as(query).isEqualTo("QUERY_INVALID");
        }
    }

    @Test
    void RLE_039_every_editor_endpoint_is_scoped_to_the_caller_tenant() throws Exception {
        publish("qa-tenant-a", "card-fee");
        send("qa-tenant-a", "lan", "POST", "/rule-sets/card-fee/versions", EditorApiIT.cardFee("card-fee"), "application/yaml", null);
        send("qa-tenant-a", "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null);
        String b = "qa-tenant-b";

        assertThat(send(b, "dan", "GET", "/rule-sets", null, null, null).json().path("data")).isEmpty();
        assertThat(send(b, "dan", "GET", "/rule-sets/card-fee", null, null, null).status()).isEqualTo(404);
        assertThat(send(b, "dan", "GET", "/rule-sets/card-fee/versions", null, null, null).status()).isEqualTo(404);
        assertThat(send(b, "dan", "GET", "/rule-sets/card-fee/versions/1/history", null, null, null).status()).isEqualTo(404);
        assertThat(send(b, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"cross tenant\"}".getBytes(StandardCharsets.UTF_8), "application/json", null).status())
                .isEqualTo(404);
        assertThat(send("qa-tenant-a", "dan", "GET", "/rule-sets/card-fee/versions/2", null, null, null)
                .json().path("status").asString()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void RLE_025_reject_with_a_stale_if_match_is_409_and_a_retry_with_the_same_key_replays() throws Exception {
        String t = "qa-ifmatch";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", EditorApiIT.cardFee("card-fee"), "application/yaml", null);
        Reply pending = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null);
        byte[] body = "{\"reason\":\"R2 waiver needs pricing committee minutes\"}".getBytes(StandardCharsets.UTF_8);

        Reply stale = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", body, "application/json", "\"0\"", "k-qa-stale");
        Reply ok = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", body, "application/json", pending.etag(), "k-qa-ok");
        Reply replay = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", body, "application/json", pending.etag(), "k-qa-ok");

        assertThat(stale.status()).as(stale.body()).isEqualTo(409);
        assertThat(stale.json().path("code").asString()).isEqualTo("VERSION_CONFLICT");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(ok.body());
        assertThat(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/history", null, null, null).json().path("data"))
                .extracting(n -> n.path("action").asString()).containsExactly("CREATED", "REQUESTED", "REJECTED");
    }

    void publish(String tenant, String name) throws Exception {
        assertThat(send(tenant, "lan", "POST", "/rule-sets", EditorApiIT.cardFee(name), "application/yaml", null).status())
                .isEqualTo(201);
        assertThat(send(tenant, "lan", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null)
                .status()).isEqualTo(200);
        assertThat(send(tenant, "minh", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null)
                .status()).isEqualTo(200);
    }

    Reply send(String tenant, String user, String method, String path, byte[] body, String contentType, String ifMatch)
            throws Exception {
        return send(tenant, user, method, path, body, contentType, ifMatch, null);
    }

    Reply send(String tenant, String user, String method, String path, byte[] body, String contentType, String ifMatch,
            String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", TestTokens.bearerFor(user, tenant, PERMISSIONS.get(user)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("ETag").orElse(null), response.body());
    }

    record Reply(int status, String etag, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
