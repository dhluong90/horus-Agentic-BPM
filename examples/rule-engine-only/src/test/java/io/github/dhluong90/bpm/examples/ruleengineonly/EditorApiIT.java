package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authoring API of #147 over HTTP, as the cockpit rule editor calls it: the rule set and version lists,
 * reject with its history, located validation errors, allowed actions and body limits. Each test uses its own
 * tenant, so the lists are exact while the tests share one database. Users: lan authors and approves, minh
 * approves, dan reads, teller evaluates.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
@Testcontainers
class EditorApiIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    static final List<String> AUTHOR_APPROVER = List.of("rules:read", "rules:author", "rules:approve", "rules:evaluate");
    static final List<String> APPROVER = List.of("rules:read", "rules:approve", "rules:evaluate");
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", AUTHOR_APPROVER, "minh", APPROVER, "dan", List.of("rules:read"), "teller", List.of("rules:evaluate"));

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(HOST_NOW, ZoneOffset.UTC);
        }
    }

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

    @Autowired
    JdbcClient jdbc;

    @Test
    void RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor() throws Exception {
        // Given bank-list-a has card-fee (v1 published, v2 pending), fx-margin and kyc-risk; bank-list-b has aml-score
        String a = "bank-list-a";
        publish(a, "card-fee");
        send(a, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        send(a, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        send(a, "lan", "POST", "/rule-sets", cardFee("kyc-risk"), "application/yaml", null, null);
        send(a, "lan", "POST", "/rule-sets", cardFee("fx-margin"), "application/yaml", null, null);
        send("bank-list-b", "lan", "POST", "/rule-sets", cardFee("aml-score"), "application/yaml", null, null);

        // When dan reads two at a time
        Reply first = send(a, "dan", "GET", "/rule-sets?limit=2", null, null, null, null);
        Reply second = send(a, "dan", "GET", "/rule-sets?limit=2&cursor=" + first.json().path("nextCursor").asString(),
                null, null, null, null);

        // Then
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().path("data").toString()).isEqualTo("[{\"ruleSet\":\"card-fee\",\"latestVersion\":2,"
                + "\"latestStatus\":\"PENDING_APPROVAL\",\"publishedVersion\":1,\"lastChangedAt\":\"2026-12-15T02:00:00Z\"},"
                + "{\"ruleSet\":\"fx-margin\",\"latestVersion\":1,\"latestStatus\":\"DRAFT\",\"publishedVersion\":null,"
                + "\"lastChangedAt\":\"2026-12-15T02:00:00Z\"}]");
        assertThat(second.json().path("data").findValuesAsString("ruleSet")).containsExactly("kyc-risk");
        assertThat(second.json().path("nextCursor").isNull()).isTrue();
        assertThat(first.json().path("hasNext").asBoolean()).isTrue();
        assertThat(second.json().path("hasNext").asBoolean()).isFalse();
        assertThat(send(a, "dan", "GET", "/rule-sets/card-fee", null, null, null, null).json().toString())
                .isEqualTo(first.json().path("data").get(0).toString());

        // and tenant isolation, bounds and the permission
        assertThat(send("bank-list-b", "dan", "GET", "/rule-sets", null, null, null, null).json().path("data")
                .findValuesAsString("ruleSet")).containsExactly("aml-score");
        assertProblem(send("bank-list-b", "dan", "GET", "/rule-sets/card-fee", null, null, null, null),
                404, "RULE_SET_NOT_FOUND");
        assertProblem(send(a, "dan", "GET", "/rule-sets?limit=0", null, null, null, null), 400, "QUERY_INVALID");
        assertProblem(send(a, "dan", "GET", "/rule-sets?limit=201", null, null, null, null), 400, "QUERY_INVALID");
        assertProblem(send(a, "dan", "GET", "/rule-sets?limit=ten", null, null, null, null), 400, "QUERY_INVALID");
        assertProblem(send(a, "dan", "GET", "/rule-sets?cursor=forged", null, null, null, null), 400, "QUERY_INVALID");
        assertProblem(send(a, "teller", "GET", "/rule-sets", null, null, null, null), 403, "PERMISSION_DENIED");
        assertThat(HTTP.send(HttpRequest.newBuilder(uri("/rule-sets")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    @Test
    void RLE_040_versions_are_listed_newest_first_with_their_actors() throws Exception {
        String t = "bank-versions";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);

        Reply first = send(t, "minh", "GET", "/rule-sets/card-fee/versions?limit=2", null, null, null, null);
        Reply second = send(t, "minh", "GET", "/rule-sets/card-fee/versions?limit=2&cursor="
                + first.json().path("nextCursor").asString(), null, null, null, null);

        assertThat(first.status()).isEqualTo(200);
        JsonNode v3 = first.json().path("data").get(0);
        JsonNode v2 = first.json().path("data").get(1);
        assertThat(v3.path("version").asInt()).isEqualTo(3);
        assertThat(v3.path("status").asString()).isEqualTo("DRAFT");
        assertThat(v3.path("createdBy").asString()).isEqualTo("lan");
        assertThat(v3.path("requestedBy").isNull()).isTrue();
        assertThat(v3.has("definition")).isFalse();
        assertThat(v2.path("requestedBy").asString()).isEqualTo("lan");
        assertThat(v2.path("allowedActions").toString()).isEqualTo("[\"approve\",\"reject\"]");
        assertThat(second.json().path("data").get(0).path("approvedBy").asString()).isEqualTo("minh");
        assertThat(second.json().path("nextCursor").isNull()).isTrue();

        assertProblem(send("bank-other", "minh", "GET", "/rule-sets/card-fee/versions", null, null, null, null),
                404, "RULE_SET_NOT_FOUND");
        assertProblem(send(t, "minh", "GET", "/rule-sets/no-such-set/versions", null, null, null, null),
                404, "RULE_SET_NOT_FOUND");
        assertProblem(send(t, "minh", "GET", "/rule-sets/card-fee/versions?limit=500", null, null, null, null),
                400, "QUERY_INVALID");
        assertProblem(send(t, "teller", "GET", "/rule-sets/card-fee/versions", null, null, null, null),
                403, "PERMISSION_DENIED");
    }

    @Test
    void RLE_025_reject_returns_pending_version_to_draft_with_reason() throws Exception {
        // Given card-fee v2 pending, requested by lan
        String t = "bank-reject";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        Reply pending = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        String etag = pending.etag();
        byte[] body = "{\"reason\":\"R2 waiver needs pricing committee minutes\"}".getBytes(StandardCharsets.UTF_8);

        // When minh rejects with k-201 and If-Match, then replays it
        Reply rejected = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", body, "application/json", "k-201", etag);
        Reply replay = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", body, "application/json", "k-201", etag);
        Reply mismatch = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"another reason\"}".getBytes(StandardCharsets.UTF_8), "application/json", "k-201", etag);

        // Then
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(rejected.json().path("requestedBy").isNull()).isTrue();
        assertThat(rejected.etag()).isEqualTo("\"" + (Long.parseLong(etag.replace("\"", "")) + 1) + "\"");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(rejected.body());
        assertProblem(mismatch, 422, "IDEMPOTENCY_KEY_REUSE_MISMATCH");
        Reply history = send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/history", null, null, null, null);
        assertThat(history.json().path("data").findValuesAsString("action"))
                .containsExactly("CREATED", "REQUESTED", "REJECTED");
        assertThat(history.json().path("data").get(2).path("reason").asString())
                .isEqualTo("R2 waiver needs pricing committee minutes");
        assertThat(history.json().path("data").get(2).path("actor").asString()).isEqualTo("minh");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t "
                        + "AND payload->>'eventType' = 'rules.version.rejected.v1'").param("t", t)
                .query(Long.class).single()).isEqualTo(1L);
        // and lan sees request-publish again
        assertThat(send(t, "lan", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).json()
                .path("allowedActions").toString()).isEqualTo("[\"request-publish\"]");
    }

    @Test
    void RLE_025_reject_failure_cases_change_nothing() throws Exception {
        String t = "bank-reject-fail";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        Reply pending = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        long before = rows(t);

        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, null), 400, "INPUT_INVALID");
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", null, null, null, null),
                400, "INPUT_INVALID");
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":42}".getBytes(StandardCharsets.UTF_8), "application/json", null, null), 400, "INPUT_INVALID");
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"stale\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, "\"0\""),
                409, "VERSION_CONFLICT");
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/1:reject",
                "{\"reason\":\"too late\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, null),
                409, "INVALID_STATE");
        assertProblem(send(t, "dan", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"no\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, null),
                403, "PERMISSION_DENIED");
        assertProblem(send("bank-elsewhere", "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"no\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, null),
                404, "RULE_SET_NOT_FOUND");

        assertThat(rows(t)).isEqualTo(before);
        assertThat(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).etag())
                .isEqualTo(pending.etag());
    }

    @Test
    void RLE_041_validation_failure_lists_each_cell_problem_with_rule_and_column() throws Exception {
        byte[] bad = """
                schemaVersion: 1
                ruleSet: card-fee-bad
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: balance, type: money }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: { balance: ">= 50000000" }, then: { fee: "10000 VND" } }
                  - { id: R3, when: {}, then: { fee: "30000" } }
                """.getBytes(StandardCharsets.UTF_8);

        Reply refused = send("bank-violations", "lan", "POST", "/rule-sets", bad, "application/yaml", null, null);

        assertProblem(refused, 422, "VALIDATION_FAILED");
        JsonNode violations = refused.json().path("violations");
        assertThat(violations.size()).isEqualTo(2);
        assertThat(violations.get(0).path("ruleId").asString() + "/" + violations.get(0).path("column").asString())
                .isEqualTo("R2/balance");
        assertThat(violations.get(1).path("ruleId").asString() + "/" + violations.get(1).path("column").asString())
                .isEqualTo("R3/fee");
        assertThat(violations.get(1).path("message").asString()).contains("money", "currency");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_rule_set WHERE tenant_id = 'bank-violations'")
                .query(Long.class).single()).isZero();

        Reply structural = send("bank-violations", "lan", "POST", "/rule-sets",
                new String(cardFee("card-fee"), StandardCharsets.UTF_8).replace("hitPolicy: first", "hitPolicy: first\nmaks: true")
                        .getBytes(StandardCharsets.UTF_8), "application/yaml", null, null);
        assertProblem(structural, 422, "VALIDATION_FAILED");
        assertThat(structural.json().path("violations").toString()).startsWith("[{\"ruleId\":null,\"column\":null,");
    }

    @Test
    void RLE_043_allowed_actions_follow_permissions_and_four_eyes() throws Exception {
        String t = "bank-actions";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);

        assertThat(actions(t, "lan", 2)).isEqualTo("[]");
        assertThat(actions(t, "minh", 2)).isEqualTo("[\"approve\",\"reject\"]");
        assertThat(actions(t, "dan", 2)).isEqualTo("[]");
        assertThat(actions(t, "lan", 3)).isEqualTo("[\"request-publish\"]");
        assertThat(actions(t, "minh", 3)).isEqualTo("[]");
        for (String user : new String[] {"lan", "minh", "dan"}) {
            assertThat(actions(t, user, 1)).isEqualTo("[]");
        }
        // the hint does not replace the check
        assertProblem(send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:approve", null, null, null, null),
                403, "SELF_APPROVAL_FORBIDDEN");
    }

    @Test
    void RLE_044_oversized_command_and_evaluation_bodies_are_refused_with_413() throws Exception {
        String t = "bank-limits";
        publish(t, "card-fee");
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("card-fee"), "application/yaml", null, null);
        Reply pending = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, null, null);
        long before = rows(t);
        byte[] command = ("{\"reason\":\"" + "x".repeat(16_385) + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] evaluate = ("{\"inputs\":{\"tier\":\"" + "x".repeat(1_048_577) + "\"}}").getBytes(StandardCharsets.UTF_8);

        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject", command, "application/json", null, null),
                413, "PAYLOAD_TOO_LARGE");
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve", command, "application/json", null, null),
                413, "PAYLOAD_TOO_LARGE");
        assertProblem(send(t, "minh", "POST", "/decisions/card-fee:evaluate", evaluate, "application/json", null, null),
                413, "PAYLOAD_TOO_LARGE");
        // a chunked body without Content-Length is cut off as well
        HttpResponse<String> chunked = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee/versions/2:reject"))
                .header("Authorization", TestTokens.bearerFor("minh", t, APPROVER)).header("If-Match", "\"1\"")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(command))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).isEqualTo(413);

        assertThat(rows(t)).isEqualTo(before);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE tenant_id = :t").param("t", t)
                .query(Long.class).single()).isZero();
        assertThat(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).etag())
                .isEqualTo(pending.etag());
        assertThat(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"R2 waiver needs pricing committee minutes\"}".getBytes(StandardCharsets.UTF_8),
                "application/json", null, null).status()).isEqualTo(200);
    }

    @Test
    void RLE_044_an_oversized_import_is_413_and_the_permission_is_checked_before_the_body_is_read() throws Exception {
        String t = "bank-import-limits";
        String padding = "# " + "x".repeat(1_048_576) + "\n";
        byte[] big = (padding + new String(cardFee("card-fee"), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        publish(t, "fx-margin");

        // Declared length over the limit, on both import endpoints
        assertProblem(send(t, "lan", "POST", "/rule-sets", big, "application/yaml", null, null), 413, "PAYLOAD_TOO_LARGE");
        assertProblem(send(t, "lan", "POST", "/rule-sets/fx-margin/versions", big, "application/yaml", null, null),
                413, "PAYLOAD_TOO_LARGE");
        // A chunked body without Content-Length is cut off one byte past the limit
        HttpResponse<String> chunked = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets"))
                .header("Authorization", TestTokens.bearerFor("lan", t, AUTHOR_APPROVER))
                .header("Content-Type", "application/yaml")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).isEqualTo(413);
        // A caller without rules:author is refused on the permission, not on the size
        assertProblem(send(t, "dan", "POST", "/rule-sets", big, "application/yaml", null, null), 403, "PERMISSION_DENIED");

        assertThat(jdbc.sql("SELECT count(*) FROM rle_rule_set WHERE tenant_id = :t AND name = 'card-fee'").param("t", t)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id "
                + "AND s.id = v.rule_set_id WHERE v.tenant_id = :t AND s.name = 'fx-margin'").param("t", t)
                .query(Long.class).single()).isEqualTo(1L);
        // and a normal import still works
        assertThat(send(t, "lan", "POST", "/rule-sets", cardFee("card-fee"), "application/yaml", null, null).status())
                .isEqualTo(201);
    }

    @Test
    void RLE_039_a_cursor_with_a_nul_byte_is_400_not_500() throws Exception {
        String t = "bank-cursor";
        publish(t, "card-fee");
        String nul = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("rule-sets:\u0000".getBytes(StandardCharsets.UTF_8));

        assertProblem(send(t, "dan", "GET", "/rule-sets?cursor=" + nul, null, null, null, null), 400, "QUERY_INVALID");
    }

    @Test
    void RLE_041_the_problem_body_says_whether_the_violations_were_truncated() throws Exception {
        Reply refused = send("bank-truncated", "lan", "POST", "/rule-sets",
                new String(cardFee("card-fee"), StandardCharsets.UTF_8).replace("then: { fee: \"10000 VND\" }",
                        "then: { fee: \"30000\" }").getBytes(StandardCharsets.UTF_8), "application/yaml", null, null);

        assertProblem(refused, 422, "VALIDATION_FAILED");
        assertThat(refused.json().path("violationsTruncated").isBoolean()).isTrue();
        assertThat(refused.json().path("violationsTruncated").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- helpers

    String actions(String tenant, String user, int version) throws Exception {
        Reply one = send(tenant, user, "GET", "/rule-sets/card-fee/versions/" + version, null, null, null, null);
        JsonNode listed = null;
        for (JsonNode item : send(tenant, user, "GET", "/rule-sets/card-fee/versions", null, null, null, null)
                .json().path("data")) {
            if (item.path("version").asInt() == version) {
                listed = item;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.path("allowedActions").toString()).isEqualTo(one.json().path("allowedActions").toString());
        return one.json().path("allowedActions").toString();
    }

    long rows(String tenant) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM rle_version WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_version_history WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_idempotency WHERE tenant_id = :t)
                """).param("t", tenant).query(Long.class).single();
    }

    void publish(String tenant, String name) throws Exception {
        assertThat(send(tenant, "lan", "POST", "/rule-sets", cardFee(name), "application/yaml", null, null).status())
                .isEqualTo(201);
        assertThat(send(tenant, "lan", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null, null)
                .status()).isEqualTo(200);
        assertThat(send(tenant, "minh", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null, null)
                .status()).isEqualTo(200);
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo(code);
    }

    static byte[] cardFee(String name) {
        return new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name).getBytes(StandardCharsets.UTF_8);
    }

    Reply send(String tenant, String user, String method, String path, byte[] body, String contentType,
            String idempotencyKey, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", TestTokens.bearerFor(user, tenant, PERMISSIONS.get(user)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        // An approval or rejection must name the lock version it was decided on (#234); these tests approve a version
        // just requested, at lock version 1, unless they pass their own If-Match.
        String lock = ifMatch == null && path.matches(".*:(approve|reject)$") ? "\"1\"" : ifMatch;
        if (lock != null) {
            request.header("If-Match", lock);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("ETag").orElse(null), response.body());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    static byte[] resource(String path) {
        try (InputStream in = EditorApiIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String contentType, String etag, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
