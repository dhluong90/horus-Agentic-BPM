package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The walking skeleton over HTTP, as the demo runs it: import a YAML rule set, request publication as
 * alice, refuse alice's own approval, approve as bob, evaluate as a teller and read the decision row.
 * The host's clock is fixed at 15 December 2026, 09:00 in Ho Chi Minh City.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"example.demo-password=it-only-password", "spring.jackson.property-naming-strategy=SNAKE_CASE"})
@Testcontainers
class RuleLifecycleIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(HOST_NOW, ZoneOffset.UTC);
        }
    }

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    Environment environment;

    @Autowired
    JdbcClient jdbc;

    @Test
    void RLE_020_import_keeps_source_document_and_canonical_hash() throws Exception {
        // Given
        byte[] file = resource("/rules/card-fee.yaml");

        // When
        Reply created = send("alice", "POST", "/rule-sets", file, "application/yaml", null);
        Reply withSource = send("alice", "GET", "/rule-sets/card-fee/versions/1?include=source", null, null, null);
        Reply canonicalOnly = send("alice", "GET", "/rule-sets/card-fee/versions/1", null, null, null);

        // Then
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("status").asString()).isEqualTo("DRAFT");
        assertThat(withSource.json().path("sourceFormat").asString()).isEqualTo("YAML");
        assertThat(withSource.json().path("sourceDocument").asString().getBytes(StandardCharsets.UTF_8)).isEqualTo(file);
        assertThat(withSource.json().path("sourceHash").asString()).isEqualTo(sha256(file));
        assertThat(withSource.json().path("canonicalHash").asString()).isEqualTo(sha256(PIPELINE_CANONICAL.getBytes(StandardCharsets.UTF_8)));
        assertThat(canonicalOnly.json().has("sourceDocument")).isFalse();
        assertThat(canonicalOnly.json().path("definition").path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(jdbc.sql("SELECT source_format || ' ' || canonical_hash FROM rle_version v JOIN rle_rule_set s "
                + "ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id WHERE v.tenant_id = 'bank-a' AND s.name = 'card-fee'")
                .query(String.class).single()).isEqualTo("YAML " + sha256(PIPELINE_CANONICAL.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void RLE_009_maker_checker_blocks_self_approval() throws Exception {
        // Given
        send("alice", "POST", "/rule-sets", renamed("card-fee-mc"), "application/yaml", null);
        send("alice", "POST", "/rule-sets/card-fee-mc/versions/1:request-publish", null, null, null);

        // When
        Reply selfApproval = send("alice", "POST", "/rule-sets/card-fee-mc/versions/1:approve", null, null, null);

        // Then
        assertThat(selfApproval.status()).isEqualTo(403);
        assertThat(selfApproval.contentType()).startsWith("application/problem+json");
        assertThat(selfApproval.json().path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(send("alice", "GET", "/rule-sets/card-fee-mc/versions/1", null, null, null).json()
                .path("status").asString()).isEqualTo("PENDING_APPROVAL");

        // When
        Reply approved = send("bob", "POST", "/rule-sets/card-fee-mc/versions/1:approve",
                "{\"comment\":\"fees checked against the tariff\"}".getBytes(StandardCharsets.UTF_8), "application/json", null);

        // Then
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(approved.json().path("approvedBy").asString()).isEqualTo("bob");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = 'bank-a' AND payload->>'ruleSet' = 'card-fee-mc'")
                .query(Long.class).single()).isEqualTo(3L);
    }

    @Test
    void RLE_001_first_hit_policy_returns_top_matching_rule() throws Exception {
        // Given
        publish("card-fee-eval");

        // When
        Reply decision = send("teller", "POST", "/decisions/card-fee-eval:evaluate",
                "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8), "application/json", null);

        // Then
        assertThat(decision.status()).isEqualTo(200);
        assertThat(decision.json().path("matchedRuleIds").toString()).isEqualTo("[\"R1\"]");
        assertThat(decision.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":0}");
        assertThat(decision.json().path("evaluatedAt").asString()).isEqualTo("2026-12-15T02:00:00Z");
        assertThat(decision.json().has("decisionId")).isTrue();
        assertThat(jdbc.sql("SELECT caller_id || ' ' || outcome || ' ' || matched_rule_ids::text FROM rle_decision "
                        + "WHERE tenant_id = 'bank-a' AND id = :id")
                .param("id", decision.json().path("decisionId").asString()).query(String.class).single())
                .isEqualTo("teller OK [\"R1\"]");
    }

    @Test
    void RLE_011_tenant_isolation_ignores_body_and_blocks_cross_tenant_read() throws Exception {
        // Given secret-tier exists only in tenant-b
        send("carol", "POST", "/rule-sets", resource("/rules/secret-tier.yaml"), "application/yaml", null);
        send("carol", "POST", "/rule-sets/secret-tier/versions/1:request-publish", null, null, null);
        assertThat(send("dave", "POST", "/rule-sets/secret-tier/versions/1:approve", null, null, null).status()).isEqualTo(200);

        // When a bank-a caller names tenant-b in the body
        Reply crossTenant = send("teller", "POST", "/decisions/secret-tier:evaluate",
                "{\"tenantId\":\"tenant-b\",\"inputs\":{\"score\":820}}".getBytes(StandardCharsets.UTF_8), "application/json", null);
        Reply crossRead = send("alice", "GET", "/rule-sets/secret-tier/versions/1", null, null, null);

        // Then
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(crossTenant.json().path("code").asString()).isEqualTo("RULE_SET_NOT_FOUND");
        assertThat(crossRead.status()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE rule_set_name = 'secret-tier'").query(Long.class).single())
                .isZero();
        assertThat(send("dave", "POST", "/decisions/secret-tier:evaluate",
                "{\"inputs\":{\"score\":820}}".getBytes(StandardCharsets.UTF_8), "application/json", null)
                .json().path("outputs").path("tier").asString()).isEqualTo("PLATINUM");
    }

    @Test
    void RLE_014_idempotency_key_replays_identical_and_rejects_mismatch() throws Exception {
        // Given
        byte[] b1 = renamed("card-fee-idem");
        byte[] b2 = new String(b1, StandardCharsets.UTF_8).replace("50000 VND", "60000 VND").getBytes(StandardCharsets.UTF_8);
        Reply first = send("alice", "POST", "/rule-sets", b1, "application/yaml", "k-101");

        // When
        Reply replay = send("alice", "POST", "/rule-sets", b1, "application/yaml", "k-101");
        Reply mismatch = send("alice", "POST", "/rule-sets", b2, "application/yaml", "k-101");

        // Then
        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.json().path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id "
                + "AND s.id = v.rule_set_id WHERE s.name = 'card-fee-idem'").query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void RLE_002_unique_hit_policy_rejects_zero_or_multiple_matches() throws Exception {
        // Given
        publishFile("/rules/loan-pricing.yaml", "loan-pricing");

        // When
        Reply overlap = send("teller", "POST", "/decisions/loan-pricing:evaluate",
                "{\"inputs\":{\"score\":750,\"income\":{\"currency\":\"VND\",\"amount\":25000000}}}"
                        .getBytes(StandardCharsets.UTF_8), "application/json", null);
        Reply none = send("teller", "POST", "/decisions/loan-pricing:evaluate",
                "{\"inputs\":{\"score\":500,\"income\":{\"currency\":\"VND\",\"amount\":5000000}}}"
                        .getBytes(StandardCharsets.UTF_8), "application/json", null);

        // Then
        assertThat(overlap.status()).isEqualTo(422);
        assertThat(overlap.contentType()).startsWith("application/problem+json");
        assertThat(overlap.json().path("code").asString()).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(overlap.json().path("matchedRuleIds").toString()).isEqualTo("[\"R1\",\"R2\"]");
        assertThat(overlap.json().has("outputs")).isFalse();
        assertThat(none.status()).isEqualTo(422);
        assertThat(none.json().path("matchedRuleIds").toString()).isEqualTo("[]");
        assertThat(jdbc.sql("SELECT outcome FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id")
                .param("id", overlap.json().path("decisionId").asString()).query(String.class).single())
                .isEqualTo("UNIQUE_HIT_VIOLATION");
    }

    @Test
    void RLE_003_collect_hit_policy_aggregates_matched_rules() throws Exception {
        publishFile("/rules/risk-flags.yaml", "risk-flags");

        Reply decision = send("teller", "POST", "/decisions/risk-flags:evaluate",
                "{\"inputs\":{\"txPerHour\":12,\"country\":\"VN\",\"newDevice\":true}}".getBytes(StandardCharsets.UTF_8),
                "application/json", null);

        assertThat(decision.status()).isEqualTo(200);
        assertThat(decision.json().path("matchedRuleIds").toString()).isEqualTo("[\"R1\",\"R3\"]");
        assertThat(decision.json().path("outputs").toString())
                .isEqualTo("{\"flags\":[\"HIGH_VELOCITY\",\"NEW_DEVICE\"],\"penalty\":15}");
    }

    void publishFile(String resource, String name) throws Exception {
        assertThat(send("alice", "POST", "/rule-sets", resource(resource), "application/yaml", null).status()).isEqualTo(201);
        assertThat(send("alice", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null).status()).isEqualTo(200);
        assertThat(send("bob", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null).status()).isEqualTo(200);
    }

    @Test
    void permissions_and_authentication_are_checked() throws Exception {
        assertThat(send("teller", "POST", "/rule-sets", renamed("card-fee-perm"), "application/yaml", null).json()
                .path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee/versions/1")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    /** The canonical JSON of card-fee.yaml as the authoring pipeline computes it (RFC 8785). */
    static final String PIPELINE_CANONICAL = "{\"description\":\"Monthly card fee by tier and transaction count\","
            + "\"hitPolicy\":\"first\",\"inputs\":[{\"name\":\"tier\",\"type\":\"string\"},"
            + "{\"description\":\"card transactions in the month\",\"name\":\"txCount\",\"type\":\"integer\"}],"
            + "\"outputs\":[{\"name\":\"fee\",\"type\":\"money\"}],\"ruleSet\":\"card-fee\","
            + "\"rules\":[{\"id\":\"R1\",\"then\":{\"fee\":\"0 VND\"},\"when\":{\"tier\":\"== 'GOLD'\"}},"
            + "{\"id\":\"R2\",\"then\":{\"fee\":\"10000 VND\"},\"when\":{\"txCount\":\">= 3\"}},"
            + "{\"id\":\"R3\",\"then\":{\"fee\":\"50000 VND\"},\"when\":{}}],\"schemaVersion\":1}";

    void publish(String name) throws Exception {
        assertThat(send("alice", "POST", "/rule-sets", renamed(name), "application/yaml", null).status()).isEqualTo(201);
        assertThat(send("alice", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null).status()).isEqualTo(200);
        assertThat(send("bob", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null).status()).isEqualTo(200);
    }

    static byte[] renamed(String name) {
        return new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name).getBytes(StandardCharsets.UTF_8);
    }

    Reply send(String user, String method, String path, byte[] body, String contentType, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":it-only-password").getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    static byte[] resource(String path) {
        try (InputStream in = RuleLifecycleIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    record Reply(int status, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
