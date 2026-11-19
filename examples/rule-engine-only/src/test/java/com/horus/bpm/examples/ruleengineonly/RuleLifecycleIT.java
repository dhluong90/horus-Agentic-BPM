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
import java.util.List;
import java.util.HexFormat;
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
 * The walking skeleton over HTTP, as the demo runs it: import a YAML rule set, request publication as
 * alice, refuse alice's own approval, approve as bob, evaluate as a teller and read the decision row.
 * The host's clock is fixed at 15 December 2026, 09:00 in Ho Chi Minh City.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jackson.property-naming-strategy=SNAKE_CASE")
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
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
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

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Autowired
    com.horus.bpm.rules.api.VersionService versions;

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
    void RLE_002_unique_hit_policy_returns_one_match_no_match_on_zero_and_fails_on_several() throws Exception {
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
        // Zero matches is a no-match with every output null, logged as OK with [] as under first (PD-088)
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.json().path("outcome").asString()).isEqualTo("NO_MATCH");
        assertThat(none.json().path("matched").asBoolean()).isFalse();
        assertThat(none.json().path("outputs").toString()).isEqualTo("{\"rate\":null}");
        assertThat(none.json().path("matchedRuleIds").toString()).isEqualTo("[]");
        assertThat(jdbc.sql("SELECT outcome FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id")
                .param("id", none.json().path("decisionId").asString()).query(String.class).single())
                .isEqualTo("OK");
        assertThat(jdbc.sql("SELECT outcome FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id")
                .param("id", overlap.json().path("decisionId").asString()).query(String.class).single())
                .isEqualTo("UNIQUE_HIT_VIOLATION");
    }

    @Test
    void RLE_003_collect_hit_policy_keeps_null_entries_and_aggregates_non_null_values() throws Exception {
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
    void RLE_014_a_request_whose_key_is_still_in_flight_gets_409_with_retry_after() throws Exception {
        // Given alice's import with key k-in-flight is running in another transaction
        publish("card-fee-flight");
        java.util.concurrent.CountDownLatch holding = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread running = Thread.ofVirtual().start(() -> {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
                            org.springframework.security.oauth2.jwt.Jwt.withTokenValue("in-flight").header("alg", "none")
                                    .subject("alice").claim("tenant_id", "bank-a").build(),
                            org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("rules:author"),
                            "alice"));
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                versions.importVersion("card-fee-flight", com.horus.bpm.rules.api.ImportRequest.yaml(renamed("card-fee-flight"))
                        .withIdempotencyKey("k-in-flight"));
                holding.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        assertThat(holding.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // When alice sends the same request over REST
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee-flight/versions"))
                .header("Authorization", TestTokens.bearer("alice"))
                .header("Content-Type", "application/yaml").header("Idempotency-Key", "k-in-flight")
                .POST(HttpRequest.BodyPublishers.ofByteArray(renamed("card-fee-flight"))).build(),
                HttpResponse.BodyHandlers.ofString());
        release.countDown();
        running.join(10_000);

        // Then
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo("IDEMPOTENCY_IN_FLIGHT");
    }

    @Test
    void TR_SEC_08_oversized_numbers_and_undeclared_inputs_are_400_not_500() throws Exception {
        publish("card-fee-limits");
        for (String inputs : new String[] {"{\"tier\":\"GOLD\",\"txCount\":1e-30000000}",
                "{\"tier\":\"GOLD\",\"txCount\":1e999999999}", "{\"tier\":\"SILVER\",\"txcount\":5}"}) {
            long started = System.nanoTime();
            Reply reply = send("teller", "POST", "/decisions/card-fee-limits:evaluate",
                    ("{\"inputs\":" + inputs + "}").getBytes(StandardCharsets.UTF_8), "application/json", null);
            assertThat(reply.status()).as(inputs).isEqualTo(400);
            assertThat(reply.json().path("code").asString()).isEqualTo("INPUT_INVALID");
            assertThat(System.nanoTime() - started).isLessThan(2_000_000_000L);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE rule_set_name = 'card-fee-limits'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void RLE_009_a_caller_without_rules_approve_gets_403_permission_denied() throws Exception {
        send("alice", "POST", "/rule-sets", renamed("card-fee-perm2"), "application/yaml", null);
        send("alice", "POST", "/rule-sets/card-fee-perm2/versions/1:request-publish", null, null, null);

        Reply reply = send("teller", "POST", "/rule-sets/card-fee-perm2/versions/1:approve", null, null, null);

        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.json().path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void a_malformed_correlation_id_is_replaced_not_echoed() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(uri("/decisions/no-such-rule-set:evaluate"))
                .header("Authorization", TestTokens.bearer("teller"))
                .header("Content-Type", "application/json").header("X-Correlation-Id", "x".repeat(300))
                .POST(HttpRequest.BodyPublishers.ofString("{\"inputs\":{}}")).build(), HttpResponse.BodyHandlers.ofString());

        String correlationId = JSON.readTree(response.body()).path("correlationId").asString();
        assertThat(correlationId).hasSize(26).doesNotContain("xxx");
    }

    @Test
    void RLE_009_engine_permissions_follow_the_same_role_mapping_as_api_bpm_me() throws Exception {
        // Given minh holds only the host role PRICING_APPROVER, mapped in application.yml
        String minh = TestTokens.bearerWithRoles("minh", "bank-a", List.of("PRICING_APPROVER"));
        send("alice", "POST", "/rule-sets", renamed("card-fee-roles"), "application/yaml", null);
        send("alice", "POST", "/rule-sets/card-fee-roles/versions/1:request-publish", null, null, null);

        // When
        HttpResponse<String> me = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + "/api/bpm/me")).header("Authorization", minh).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> approve = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee-roles/versions/1:approve"))
                .header("Authorization", minh).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> importAttempt = HTTP.send(HttpRequest.newBuilder(uri("/rule-sets"))
                .header("Authorization", minh).header("Content-Type", "application/yaml")
                .POST(HttpRequest.BodyPublishers.ofByteArray(renamed("card-fee-roles-2"))).build(),
                HttpResponse.BodyHandlers.ofString());

        // Then /me lists read and approve, and the engine allows exactly those
        assertThat(JSON.readTree(me.body()).path("permissions").toString()).isEqualTo("[\"rules:read\",\"rules:approve\"]");
        assertThat(approve.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(approve.body()).path("approvedBy").asString()).isEqualTo("minh");
        assertThat(importAttempt.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(importAttempt.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void permissions_and_authentication_are_checked() throws Exception {
        assertThat(send("teller", "POST", "/rule-sets", renamed("card-fee-perm"), "application/yaml", null).json()
                .path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(HTTP.send(HttpRequest.newBuilder(uri("/rule-sets/card-fee/versions/1")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    /**
     * The canonical JSON of card-fee.yaml, written out by hand from RFC 8785 as a test vector. It stands in for the
     * authoring pipeline's hash; it is not output of an independent RFC 8785 implementation.
     */
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
                .header("Authorization", TestTokens.bearer(user))
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
