package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
 * RLE-026 and RLE-027 over HTTP (#28), as the demo runs them: the retire command with its required headers and
 * reason, the refusals that change nothing, the history and event it writes, the evaluation that stops, and a newer
 * version that retires the older one at approval. Each test uses its own tenant. Users: lan authors, bob approves,
 * dan reads, teller evaluates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RetireVersionIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "bob", List.of("rules:read", "rules:approve"),
            "dan", List.of("rules:read"),
            "teller", List.of("rules:evaluate"),
            "system:supersession", List.of("rules:read", "rules:approve"));

    /** The host clock, moved between steps. */
    static final class HostClock extends Clock {
        private volatile Instant now = Instant.parse("2027-01-15T00:00:00Z");

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MovableHostClock {
        @Bean
        HostClock clock() {
            return new HostClock();
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

    @Autowired
    HostClock clock;

    static byte[] tetFeeWaiver(String effectiveFrom) {
        return ("""
                schemaVersion: 1
                ruleSet: tet-fee-waiver
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: channel, type: string }
                outputs:
                  - { name: waiveFee, type: boolean }
                rules:
                  - { id: R1, when: {}, then: { waiveFee: true } }
                """).formatted(effectiveFrom).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    Reply publish(String tenant, byte[] document, int version) throws Exception {
        String path = version == 1 ? "/rule-sets" : "/rule-sets/tet-fee-waiver/versions";
        assertThat(send(tenant, "lan", "POST", path, document, "application/yaml", null, null).status()).isEqualTo(201);
        assertThat(send(tenant, "lan", "POST", "/rule-sets/tet-fee-waiver/versions/" + version + ":request-publish",
                null, null, null, null).status()).isEqualTo(200);
        Reply approved = send(tenant, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/" + version + ":approve",
                null, null, null, null);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return approved;
    }

    @Test
    void RLE_026_manual_retire_stops_every_replica_serving_the_version() throws Exception {
        // Given tet-fee-waiver v1, published and effective since 20 Jan 2027
        String t = "bank-retire-a";
        clock.set(Instant.parse("2027-01-15T00:00:00Z"));
        Reply v1 = publish(t, tetFeeWaiver("2027-01-20T00:00:00Z"), 1);
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        assertThat(send(t, "bob", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null, null)
                .json().path("allowedActions").toString()).isEqualTo("[\"retire\"]");
        assertThat(send(t, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null, null)
                .json().path("allowedActions").toString()).isEqualTo("[]");

        // When bob retires it, the last version in effect, without confirming that none will remain (PD-101)
        Reply refused = send(t, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/1:retire",
                body("{\"reason\":\"Tet promotion ended\"}"), "application/json", "k-301", v1.etag());
        assertProblem(refused, 409, "LAST_VERSION_IN_EFFECT");
        assertThat(refused.json().path("ruleSet").asString()).isEqualTo("tet-fee-waiver");
        assertThat(refused.json().path("version").asInt()).isEqualTo(1);
        assertThat(refused.json().path("setting").asString()).isEqualTo("confirm");
        assertThat(refused.json().path("nextEffectiveFrom").isNull()).isTrue();
        assertThat(refused.json().path("detail").asString()).contains("rule set tet-fee-waiver");
        assertProblem(send(t, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/1:retire",
                body("{\"reason\":\"Tet promotion ended\",\"confirmNoVersionInEffect\":\"yes\"}"), "application/json",
                "k-301", v1.etag()), 400, "INPUT_INVALID");
        assertThat(retiredEvents(t)).isZero();

        // When he retires it with k-301, If-Match, a reason and the confirmation
        byte[] reason = body("{\"reason\":\"Tet promotion ended\",\"confirmNoVersionInEffect\":true}");
        Reply retired = send(t, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/1:retire", reason,
                "application/json", "k-301", v1.etag());

        // Then 200 RETIRED from 08:00:00Z, the new ETag, no further actions
        assertThat(retired.status()).as(retired.body()).isEqualTo(200);
        assertThat(retired.json().path("status").asString()).isEqualTo("RETIRED");
        assertThat(retired.json().path("retiredAt").asString()).isEqualTo("2027-02-17T08:00:00Z");
        assertThat(retired.json().path("allowedActions").toString()).isEqualTo("[]");
        assertThat(retired.etag()).isEqualTo("\"" + retired.json().path("lockVersion").asLong() + "\"");
        // and the history entry and the event record bob, the reason and the mode single
        JsonNode history = send(t, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1/history", null, null, null, null)
                .json().path("data");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.path("action").asString()).isEqualTo("RETIRED");
        assertThat(last.path("actor").asString()).isEqualTo("bob");
        assertThat(last.path("reason").asString()).isEqualTo("Tet promotion ended");
        assertThat(last.path("approvalMode").asString()).isEqualTo("single");
        assertThat(last.path("supersededBy").isNull()).isTrue();
        assertThat(last.path("noVersionInEffect").asBoolean()).isTrue();
        assertThat(history.get(0).has("approvalMode")).as("only a retirement carries the mode").isFalse();
        assertThat(retiredEvents(t)).isEqualTo(1);

        // and 5 s later the evaluation is 404 NO_EFFECTIVE_VERSION
        clock.set(Instant.parse("2027-02-17T08:00:05Z"));
        assertProblem(send(t, "teller", "POST", "/decisions/tet-fee-waiver:evaluate",
                body("{\"inputs\":{\"channel\":\"CARD\"}}"), "application/json", null, null), 404, "NO_EFFECTIVE_VERSION");
        // and a replay of k-301 is the same 200 with no second event
        Reply again = send(t, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/1:retire", reason,
                "application/json", "k-301", v1.etag());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("retiredAt")).isEqualTo(retired.json().path("retiredAt"));
        assertThat(retiredEvents(t)).isEqualTo(1);
    }

    @Test
    void RLE_026_refused_retirements_change_nothing() throws Exception {
        String t = "bank-retire-b";
        clock.set(Instant.parse("2027-01-15T00:00:00Z"));
        Reply v1 = publish(t, tetFeeWaiver("2027-01-20T00:00:00Z"), 1);
        assertThat(send(t, "lan", "POST", "/rule-sets/tet-fee-waiver/versions", tetFeeWaiver("2027-03-01T00:00:00Z"),
                "application/yaml", null, null).status()).isEqualTo(201);
        String path = "/rule-sets/tet-fee-waiver/versions/1:retire";
        byte[] reason = body("{\"reason\":\"Tet promotion ended\"}");
        long before = rows(t);

        // the permission is checked first, then the headers, then the body and the state
        assertProblem(send(t, "lan", "POST", path, reason, "application/json", "k-1", v1.etag()), 403, "PERMISSION_DENIED");
        assertProblem(send(t, "bob", "POST", path, reason, "application/json", null, v1.etag()), 400, "INPUT_INVALID");
        assertProblem(send(t, "bob", "POST", path, reason, "application/json", "k-2", null), 428, "PRECONDITION_REQUIRED");
        assertProblem(send(t, "bob", "POST", path, body("{\"reason\":\"\"}"), "application/json", "k-3", v1.etag()),
                400, "INPUT_INVALID");
        assertProblem(send(t, "bob", "POST", path, body("{}"), "application/json", "k-4", v1.etag()), 400, "INPUT_INVALID");
        assertProblem(send(t, "bob", "POST", path, reason, "application/json", "k-5", "\"0\""), 409, "VERSION_CONFLICT");
        assertProblem(send(t, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/2:retire", reason, "application/json",
                "k-6", "\"0\""), 409, "INVALID_STATE");
        assertProblem(send(t, "bob", "POST", path, body("{\"reason\":\"" + "x".repeat(17_000) + "\"}"),
                "application/json", "k-7", v1.etag()), 413, "PAYLOAD_TOO_LARGE");
        assertProblem(send("bank-retire-other", "bob", "POST", path, reason, "application/json", "k-8", v1.etag()),
                404, "RULE_SET_NOT_FOUND");
        // a host user whose id is in the engine's reserved system: namespace, even with rules:approve
        assertProblem(send(t, "system:supersession", "POST", path, reason, "application/json", "k-9", v1.etag()),
                403, "PERMISSION_DENIED");
        assertProblem(send(t, "system:supersession", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null,
                null), 403, "PERMISSION_DENIED");

        assertThat(rows(t)).isEqualTo(before);
        assertThat(send(t, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null, null)
                .json().path("status").asString()).isEqualTo("PUBLISHED");
    }

    @Test
    void RLE_027_previous_version_retires_once_when_newer_becomes_effective() throws Exception {
        // Given v1 published and v2 effective at its approval
        String t = "bank-retire-c";
        clock.set(Instant.parse("2027-01-15T00:00:00Z"));
        publish(t, tetFeeWaiver("2027-01-15T00:00:00Z"), 1);
        clock.set(Instant.parse("2027-01-20T00:00:00Z"));
        publish(t, tetFeeWaiver("2027-01-20T00:00:00Z"), 2);

        // Then v1 is RETIRED as superseded by v2, in the approve transaction, with one event
        JsonNode v1 = send(t, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null, null).json();
        assertThat(v1.path("status").asString()).isEqualTo("RETIRED");
        assertThat(v1.path("retiredAt").asString()).isEqualTo("2027-01-20T00:00:00Z");
        JsonNode history = send(t, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1/history", null, null, null, null)
                .json().path("data");
        JsonNode last = history.get(history.size() - 1);
        assertThat(last.path("actor").asString()).isEqualTo("system:supersession");
        assertThat(last.path("reason").asString()).isEqualTo("SUPERSEDED");
        assertThat(last.path("supersededBy").asInt()).isEqualTo(2);
        assertThat(last.path("approvalMode").asString()).isEqualTo("system");
        assertThat(retiredEvents(t)).isEqualTo(1);
    }

    @Test
    void RLE_026_the_host_metadata_shows_the_retirement_mode() throws Exception {
        HttpResponse<String> meta = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/meta"))
                .header("Authorization", TestTokens.bearerFor("dan", "bank-retire-a", PERMISSIONS.get("dan"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode settings = JSON.readTree(meta.body()).path("modules").get(0).path("settings");
        assertThat(settings.path("lifecycle.retire-approval").asString()).isEqualTo("single");
        assertThat(settings.path("lifecycle.retire-last-version").asString()).isEqualTo("confirm");
    }

    long retiredEvents(String tenant) {
        return jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t"
                + " AND payload->>'eventType' = 'rules.version.retired.v1'").param("t", tenant).query(Long.class).single();
    }

    long rows(String tenant) {
        return jdbc.sql("SELECT (SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t)"
                + " + (SELECT count(*) FROM rle_version_history WHERE tenant_id = :t)"
                + " + (SELECT coalesce(sum(lock_version), 0) FROM rle_version WHERE tenant_id = :t)")
                .param("t", tenant).query(Long.class).single();
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo(code);
    }

    Reply send(String tenant, String user, String method, String path, byte[] body, String contentType,
            String idempotencyKey, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
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

    record Reply(int status, String contentType, String etag, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
