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
 * Replacing a draft (RLE-047) and the version diff (RLE-028, RLE-048) over HTTP, as the cockpit editor and the
 * approver's view call them, on a host with {@code authoring.cockpit-drafts} on. Each test uses its own tenant. Users:
 * lan authors, minh authors and approves, bob approves, dan reads.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"horus.bpm.rules.authoring.cockpit-drafts=true",
                "spring.jackson.property-naming-strategy=SNAKE_CASE"})
@Testcontainers
class DraftReplaceAndDiffIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "minh", List.of("rules:read", "rules:author", "rules:approve"),
            "bob", List.of("rules:read", "rules:approve"),
            "dan", List.of("rules:read"));

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(HOST_NOW, ZoneOffset.UTC);
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

    @Autowired
    JdbcClient jdbc;

    @Test
    void RLE_047_author_replaces_draft_in_place_with_import_checks() throws Exception {
        // Given card-fee v1 published and v2 a draft by lan
        String t = "bank-replace";
        publish(t);
        Reply draft = send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("10000 VND"), "application/yaml", null, null);
        String path = "/rule-sets/card-fee/versions/2";
        long before = rows(t);

        // Refusals change nothing: headers, permission, authorship, document, state, tenant, size
        assertProblem(send(t, "lan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-501", null), 428, "PRECONDITION_REQUIRED");
        assertProblem(send(t, "lan", "PUT", path, cardFee("0 VND"), "application/yaml", null, draft.etag()), 400, "INPUT_INVALID");
        assertProblem(send(t, "lan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-s", "\"7\""), 409, "VERSION_CONFLICT");
        assertProblem(send(t, "minh", "PUT", path, cardFee("0 VND"), "application/yaml", "k-m", draft.etag()), 403, "PERMISSION_DENIED");
        assertProblem(send(t, "dan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-d", draft.etag()), 403, "PERMISSION_DENIED");
        assertProblem(send(t, "lan", "PUT", path, cardFee("+ 1"), "application/yaml", "k-v", draft.etag()), 422, "VALIDATION_FAILED");
        Reply invalid = send(t, "lan", "PUT", path, cardFee("+ 1"), "application/yaml", "k-v2", draft.etag());
        assertThat(invalid.json().path("violations").get(0).path("ruleId").asString()).isEqualTo("R2");
        assertThat(invalid.json().path("violations").get(0).path("column").asString()).isEqualTo("fee");
        assertProblem(send(t, "lan", "PUT", path, new String(cardFee("0 VND"), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: kyc-risk").getBytes(StandardCharsets.UTF_8), "application/yaml",
                "k-n", draft.etag()), 400, "INPUT_INVALID");
        assertProblem(send(t, "lan", "PUT", "/rule-sets/card-fee/versions/1", cardFee("0 VND"), "application/yaml", "k-p", "\"2\""),
                409, "INVALID_STATE");
        assertProblem(send("bank-replace-b", "lan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-o", draft.etag()),
                404, "RULE_SET_NOT_FOUND");
        byte[] big = ("# " + "x".repeat(1_048_576) + "\n" + new String(cardFee("0 VND"), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        assertProblem(send(t, "lan", "PUT", path, big, "application/yaml", "k-b", draft.etag()), 413, "PAYLOAD_TOO_LARGE");
        assertProblem(send(t, "dan", "PUT", path, big, "application/yaml", "k-b2", draft.etag()), 403, "PERMISSION_DENIED");
        assertThat(rows(t)).isEqualTo(before);

        // When lan replaces it with R2 at 0 VND and repeats the request
        Reply replaced = send(t, "lan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-501", draft.etag());
        Reply replay = send(t, "lan", "PUT", path, cardFee("0 VND"), "application/yaml", "k-501", draft.etag());

        // Then 200 with the same number, a new ETag and hash, and the same body on replay
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        assertThat(replaced.json().path("version").asInt()).isEqualTo(2);
        assertThat(replaced.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(replaced.json().path("createdBy").asString()).isEqualTo("lan");
        assertThat(replaced.etag()).isEqualTo("\"1\"");
        assertThat(replaced.json().path("canonicalHash").asString()).isNotEqualTo(draft.json().path("canonicalHash").asString());
        assertThat(replaced.json().path("allowedActions").toString()).isEqualTo("[\"request-publish\"]");
        assertThat(replay.body()).isEqualTo(replaced.body());
        assertThat(send(t, "dan", "GET", path + "?include=source", null, null, null, null).json().path("sourceDocument")
                .asString()).isEqualTo(new String(cardFee("0 VND"), StandardCharsets.UTF_8));
        // and no new version; the history shows one DRAFT_UPDATED entry with both hashes, and one event is written
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version WHERE tenant_id = :t").param("t", t).query(Long.class).single())
                .isEqualTo(2L);
        JsonNode history = send(t, "dan", "GET", path + "/history", null, null, null, null).json().path("data");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(1).path("action").asString()).isEqualTo("DRAFT_UPDATED");
        assertThat(history.get(1).path("actor").asString()).isEqualTo("lan");
        assertThat(history.get(1).path("previousCanonicalHash").asString()).isEqualTo(draft.json().path("canonicalHash").asString());
        assertThat(history.get(1).path("canonicalHash").asString()).isEqualTo(replaced.json().path("canonicalHash").asString());
        assertThat(history.get(0).has("canonicalHash")).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t "
                + "AND payload->>'eventType' = 'rules.version.draft-updated.v1'").param("t", t).query(Long.class).single())
                .isEqualTo(1L);
        // and minh, another author, is pointed to importing his own version
        assertThat(send(t, "minh", "PUT", path, cardFee("0 VND"), "application/yaml", "k-m2", replaced.etag()).json()
                .path("detail").asString()).contains("POST .../rule-sets/card-fee/versions");
    }

    /** Security review of #234 (#275): an approval decided on replaced content is refused over HTTP. */
    @Test
    void RLE_047_an_approval_based_on_content_that_was_replaced_since_is_refused() throws Exception {
        // Given v2 requested by lan; minh reads it and keeps its ETag
        String t = "bank-replace-approve";
        publish(t);
        Reply draft = send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("10000 VND"), "application/yaml", null, null);
        Reply requested = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, "k-r1", draft.etag());
        String reviewed = send(t, "minh", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).etag();
        assertThat(reviewed).isEqualTo(requested.etag());

        // When bob sends it back, lan replaces R2 and requests again
        Reply rejected = send(t, "bob", "POST", "/rule-sets/card-fee/versions/2:reject",
                "{\"reason\":\"Please double-check R2\"}".getBytes(StandardCharsets.UTF_8), "application/json", "k-j1", reviewed);
        Reply replaced = send(t, "lan", "PUT", "/rule-sets/card-fee/versions/2", cardFee("0 VND"), "application/yaml", "k-p1",
                rejected.etag());
        Reply again = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, "k-r2", replaced.etag());
        long before = rows(t);

        // Then minh's approval with the ETag he reviewed is 409, and approve or reject without If-Match is 428
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve", null, null, "k-a1", reviewed),
                409, "VERSION_CONFLICT");
        for (String action : new String[] {":approve", ":reject"}) {
            java.net.http.HttpResponse<String> bare = HTTP.send(java.net.http.HttpRequest.newBuilder(
                            uri("/rule-sets/card-fee/versions/2" + action))
                    .header("Authorization", TestTokens.bearerFor("minh", t, PERMISSIONS.get("minh")))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"reason\":\"no lock\"}")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(bare.statusCode()).as(action).isEqualTo(428);
            assertThat(JSON.readTree(bare.body()).path("code").asString()).isEqualTo("PRECONDITION_REQUIRED");
        }
        assertThat(rows(t)).isEqualTo(before);
        assertThat(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).json().path("status")
                .asString()).isEqualTo("PENDING_APPROVAL");

        // When minh reviews the new content and approves with its ETag, it publishes
        Reply approved = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve", null, null, "k-a2", again.etag());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
    }

    /** The same attack with withdraw (#162): request, withdraw, replace, request again, approve with the old ETag. */
    @Test
    void RLE_046_an_approval_of_content_withdrawn_and_replaced_since_is_refused() throws Exception {
        String t = "bank-withdraw-approve";
        publish(t);
        Reply draft = send(t, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("10000 VND"), "application/yaml", null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, "k-r1", draft.etag());
        String reviewed = send(t, "minh", "GET", "/rule-sets/card-fee/versions/2", null, null, null, null).etag();

        // lan takes it back, changes R2 and sends it again
        Reply withdrawn = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:withdraw", null, null, "k-w1", reviewed);
        assertThat(withdrawn.status()).as(withdrawn.body()).isEqualTo(200);
        Reply replaced = send(t, "lan", "PUT", "/rule-sets/card-fee/versions/2", cardFee("0 VND"), "application/yaml", "k-p1",
                withdrawn.etag());
        Reply again = send(t, "lan", "POST", "/rule-sets/card-fee/versions/2:request-publish", null, null, "k-r2", replaced.etag());
        long before = rows(t);

        // minh's approval with the ETag he reviewed is refused and nothing changes
        assertProblem(send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve", null, null, "k-a1", reviewed),
                409, "VERSION_CONFLICT");
        assertThat(rows(t)).isEqualTo(before);
        // with the current ETag, after reviewing the new content, it publishes
        Reply approved = send(t, "minh", "POST", "/rule-sets/card-fee/versions/2:approve", null, null, "k-a2", again.etag());
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void RLE_028_version_diff_lists_added_changed_removed_and_moved_rows() throws Exception {
        // Given card-fee v1 published (R2 30000 VND), v2 with R2 at 0 VND and R6 inserted before R3
        String t = "bank-diff";
        send(t, "lan", "POST", "/rule-sets", cardFee("30000 VND"), "application/yaml", null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, null, null);
        send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:approve", null, null, null, null);
        send(t, "lan", "POST", "/rule-sets/card-fee/versions", new String(cardFee("0 VND"), StandardCharsets.UTF_8)
                .replace("  - id: R3", "  - id: R6\n    when: { tier: \"== 'PLATINUM'\" }\n    then: { fee: \"0 VND\" }\n  - id: R3")
                .getBytes(StandardCharsets.UTF_8), "application/yaml", null, null);
        long before = rows(t);

        // When dan asks for the diff of v2 against v1, twice
        Reply diff = send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=1", null, null, null, null);
        Reply again = send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=1", null, null, null, null);

        // Then the engine's camelCase body, byte-identical on repeat, whatever the host's Jackson settings
        assertThat(diff.status()).as(diff.body()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(diff.body());
        JsonNode body = diff.json();
        assertThat(body.path("rows").toString()).isEqualTo("[{\"ruleId\":\"R2\",\"change\":\"CHANGED\",\"position\":2,"
                + "\"basePosition\":2,\"moved\":false,\"fields\":[{\"path\":\"/then/fee\","
                + "\"from\":{\"currency\":\"VND\",\"amount\":30000},\"to\":{\"currency\":\"VND\",\"amount\":0},"
                + "\"nonAscii\":false}]},{\"ruleId\":\"R6\",\"change\":\"ADDED\",\"position\":3}]");
        assertThat(body.path("unchangedCount").asInt()).isEqualTo(2);
        assertThat(body.path("base").asInt()).isEqualTo(1);
        assertThat(body.path("header").size()).isZero();
        assertThat(body.path("inputs").toString())
                .isEqualTo("{\"added\":[],\"removed\":[],\"changed\":[],\"reordered\":false}");
        assertThat(body.path("canonicalHash").asString()).hasSize(64);
        assertThat(body.path("baseCanonicalHash").asString()).hasSize(64);
        assertThat(body.path("effectiveFrom").path("from").asString()).isEqualTo("2026-12-15T02:00:00Z");
        assertThat(body.path("effectiveFrom").path("to").isNull()).isTrue();

        // and the failure cases
        assertProblem(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=9", null, null, null, null),
                404, "VERSION_NOT_FOUND");
        assertProblem(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff", null, null, null, null),
                400, "INPUT_INVALID");
        assertProblem(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=2", null, null, null, null),
                400, "INPUT_INVALID");
        assertProblem(send(t, "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=one", null, null, null, null),
                400, "INPUT_INVALID");
        assertProblem(send("bank-diff-b", "dan", "GET", "/rule-sets/card-fee/versions/2/diff?base=1", null, null, null, null),
                404, "RULE_SET_NOT_FOUND");
        // and nothing was written
        assertThat(rows(t)).isEqualTo(before);
    }

    @Test
    void RLE_048_diff_marks_non_ascii_literals_and_reports_header_and_column_changes() throws Exception {
        String t = "bank-diff-ascii";
        send(t, "lan", "POST", "/rule-sets", kycRoute("first", "!= 'VN'"), "application/yaml", null, null);
        send(t, "lan", "POST", "/rule-sets/kyc-route/versions", kycRoute("unique", "!= 'ВN'"), "application/yaml", null, null);

        JsonNode body = send(t, "dan", "GET", "/rule-sets/kyc-route/versions/2/diff?base=1", null, null, null, null).json();

        assertThat(body.path("header").toString())
                .isEqualTo("[{\"path\":\"/hitPolicy\",\"from\":\"first\",\"to\":\"unique\",\"nonAscii\":false}]");
        JsonNode field = body.path("rows").get(0).path("fields").get(0);
        assertThat(field.path("path").asString()).isEqualTo("/when/country");
        assertThat(field.path("to").asString()).isEqualTo("!= 'ВN'");
        assertThat(field.path("nonAscii").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    void publish(String tenant) throws Exception {
        assertThat(send(tenant, "lan", "POST", "/rule-sets", cardFee("10000 VND"), "application/yaml", null, null).status())
                .isEqualTo(201);
        assertThat(send(tenant, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, null, null)
                .status()).isEqualTo(200);
        assertThat(send(tenant, "bob", "POST", "/rule-sets/card-fee/versions/1:approve", null, null, null, null).status())
                .isEqualTo(200);
    }

    long rows(String tenant) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM rle_version WHERE tenant_id = :t)
                     + (SELECT coalesce(sum(lock_version), 0) FROM rle_version WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_version_history WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_idempotency WHERE tenant_id = :t)
                """).param("t", tenant).query(Long.class).single();
    }

    static byte[] cardFee(String r2Fee) {
        return new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"" + r2Fee + "\" }")
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] kycRoute(String hitPolicy, String condition) {
        return ("schemaVersion: 1\nruleSet: kyc-route\nhitPolicy: " + hitPolicy + "\n"
                + "inputs:\n  - { name: country, type: string }\n"
                + "outputs:\n  - { name: route, type: string }\n"
                + "rules:\n  - id: R1\n    when: { country: \"" + condition + "\" }\n    then: { route: \"'MANUAL'\" }\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo(code);
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
        try (InputStream in = DraftReplaceAndDiffIT.class.getResourceAsStream(path)) {
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
