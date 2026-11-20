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
 * An approval or a rejection binds to the content the approver reviewed (RLE-084, #275), over HTTP as the cockpit
 * calls it, on a host with {@code authoring.cockpit-drafts} on. lan authors card-fee v1 and requests publication;
 * bob reads it. lan then withdraws the request, changes the R2 fee and requests again under the same number, so
 * bob's refreshed {@code If-Match} is current while the hash he reviewed is not.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"horus.bpm.rules.authoring.cockpit-drafts=true"})
@Testcontainers
class ApprovalBindsToContentHostIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "bob", List.of("rules:read", "rules:approve"));

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(HOST_NOW, ZoneOffset.UTC);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

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
    void RLE_084_an_approval_or_rejection_of_other_content_than_reviewed_is_409_and_writes_nothing() throws Exception {
        // Given bob reviewed card-fee v1 with R2 at 10,000 VND
        String t = "bank-bound";
        assertThat(send(t, "lan", "POST", "/rule-sets", cardFee("10000 VND"), "application/yaml", null, null).status())
                .isEqualTo(201);
        Reply reviewed = send(t, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, "rp-1", null);
        String reviewedHash = reviewed.json().path("canonicalHash").asString();

        // And lan withdrew the request, changed R2 to 12,000 VND and requested again under the same number
        Reply draft = send(t, "lan", "POST", "/rule-sets/card-fee/versions/1:withdraw", null, null, "wd-1", reviewed.etag());
        assertThat(draft.status()).as(draft.body()).isEqualTo(200);
        Reply replaced = send(t, "lan", "PUT", "/rule-sets/card-fee/versions/1", cardFee("12000 VND"), "application/yaml",
                "rpl-1", draft.etag());
        assertThat(replaced.status()).as(replaced.body()).isEqualTo(200);
        Reply changed = send(t, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, "rp-2",
                replaced.etag());
        assertThat(changed.status()).as(changed.body()).isEqualTo(200);
        String currentHash = changed.json().path("canonicalHash").asString();
        assertThat(currentHash).isNotEqualTo(reviewedHash);
        assertThat(changed.etag()).isNotEqualTo(reviewed.etag());
        long before = rows(t);

        // When bob approves with the current If-Match and the hash of what he read, then rejects the same way
        Reply approval = send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:approve",
                body("{\"comment\":\"ok\",\"expectedCanonicalHash\":\"" + reviewedHash + "\"}"), "application/json", "ap-1",
                changed.etag());
        Reply rejection = send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:reject",
                body("{\"reason\":\"R2 waiver needs minutes\",\"expectedCanonicalHash\":\"" + reviewedHash + "\"}"),
                "application/json", "rj-1", changed.etag());

        // Then both are 409 VERSION_CONFLICT naming the current hash and lock version, and nothing is written
        for (Reply refused : List.of(approval, rejection)) {
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(refused.contentType()).startsWith("application/problem+json");
            assertThat(refused.json().path("code").asString()).isEqualTo("VERSION_CONFLICT");
            assertThat(refused.json().path("currentCanonicalHash").asString()).isEqualTo(currentHash);
            assertThat("\"" + refused.json().path("currentLockVersion").asLong() + "\"").isEqualTo(changed.etag());
        }
        assertThat(rows(t)).isEqualTo(before);
        assertThat(send(t, "bob", "GET", "/rule-sets/card-fee/versions/1", null, null, null, null).json().path("status")
                .asString()).isEqualTo("PENDING_APPROVAL");

        // And a hash that is not 64 lower-case hexadecimal characters, or not a string, is 400 INPUT_INVALID
        for (String malformed : List.of("\"abc\"", "\"" + currentHash.toUpperCase() + "\"", "12")) {
            Reply invalid = send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:approve",
                    body("{\"expectedCanonicalHash\":" + malformed + "}"), "application/json", "ap-bad", changed.etag());
            assertThat(invalid.status()).as(invalid.body()).isEqualTo(400);
            assertThat(invalid.json().path("code").asString()).isEqualTo("INPUT_INVALID");
        }
        assertThat(rows(t)).isEqualTo(before);

        // And after reviewing the current content bob approves it with its hash, under the key that was refused
        Reply approved = send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:approve",
                body("{\"comment\":\"ok\",\"expectedCanonicalHash\":\"" + currentHash + "\"}"), "application/json", "ap-1",
                changed.etag());
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void RLE_084_an_approval_that_names_no_hash_works_as_before() throws Exception {
        // Given card-fee v1 pending at bank-bound-b
        String t = "bank-bound-b";
        send(t, "lan", "POST", "/rule-sets", cardFee("10000 VND"), "application/yaml", null, null);
        Reply pending = send(t, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, "rp-1", null);

        // When bob approves with the lock version only
        Reply approved = send(t, "bob", "POST", "/rule-sets/card-fee/versions/1:approve", null, null, "ap-1", pending.etag());

        // Then it publishes
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(approved.json().path("status").asString()).isEqualTo("PUBLISHED");
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

    static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] cardFee(String r2Fee) {
        return new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"" + r2Fee + "\" }")
                .getBytes(StandardCharsets.UTF_8);
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
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("ETag").orElse(null), response.body());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    static byte[] resource(String path) {
        try (InputStream in = ApprovalBindsToContentHostIT.class.getResourceAsStream(path)) {
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
