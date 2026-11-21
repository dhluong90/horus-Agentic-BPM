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
 * RLE-038 over HTTP (#213), as the demo runs it, on a host that sets {@code horus.bpm.lifecycle.retire-approval:
 * four-eyes}: a retire request leaves the version serving, the requester's confirmation is refused, and a second
 * approver's confirmation retires it with an event and a history row. Every command sends {@code Idempotency-Key} and
 * the {@code If-Match} of the version it was decided on. Each test uses its own tenant. Users: lan authors, bob and
 * carol approve, dan reads, teller evaluates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "horus.bpm.lifecycle.retire-approval=four-eyes")
@Testcontainers
class FourEyesRetirementIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "bob", List.of("rules:read", "rules:approve"),
            "carol", List.of("rules:read", "rules:approve"),
            "dan", List.of("rules:read"),
            "teller", List.of("rules:evaluate"));

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
        Reply requested = send(tenant, "lan", "POST", "/rule-sets/tet-fee-waiver/versions/" + version + ":request-publish",
                null, null, null, null);
        assertThat(requested.status()).isEqualTo(200);
        // The approver names the lock version he reviewed, as every approval must.
        long reviewed = JSON.readTree(requested.body()).path("lockVersion").asLong();
        Reply approved = send(tenant, "bob", "POST", "/rule-sets/tet-fee-waiver/versions/" + version + ":approve",
                null, null, null, "\"" + reviewed + "\"");
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return approved;
    }

    static final String RETIRE = "/rule-sets/tet-fee-waiver/versions/1:retire";
    static final String CONFIRM = "/rule-sets/tet-fee-waiver/versions/1:confirm-retire";
    static final String CANCEL = "/rule-sets/tet-fee-waiver/versions/1:cancel-retire";

    @Test
    void RLE_038_four_eyes_retirement_needs_second_approver() throws Exception {
        // Given four-eyes; tet-fee-waiver v1 PUBLISHED, the only version, in effect since 20 Jan 2027
        String t = "bank-four-eyes-a";
        clock.set(Instant.parse("2027-01-15T00:00:00Z"));
        Reply v1 = publish(t, tetFeeWaiver("2027-01-20T00:00:00Z"), 1);
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        assertThat(meta(t).path("lifecycle.retire-approval").asString()).isEqualTo("four-eyes");

        // When bob asks to retire it, with the confirmation the last version in effect needs (PD-101)
        byte[] request = body("{\"reason\":\"Tet promotion ended\",\"confirmNoVersionInEffect\":true}");
        Reply requested = send(t, "bob", "POST", RETIRE, request, "application/json", "k-501", v1.etag());

        // Then 200: still PUBLISHED, with bob's pending retirement and a new ETag
        assertThat(requested.status()).as(requested.body()).isEqualTo(200);
        assertThat(requested.json().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(requested.json().path("retiredAt").isNull()).isTrue();
        assertThat(requested.json().path("retireRequestedBy").asString()).isEqualTo("bob");
        assertThat(requested.json().path("retireRequestedAt").asString()).isEqualTo("2027-02-17T08:00:00Z");
        assertThat(requested.json().path("retireReason").asString()).isEqualTo("Tet promotion ended");
        assertThat(requested.json().path("allowedActions").toString()).isEqualTo("[\"cancel-retire\"]");
        assertThat(requested.etag()).isNotEqualTo(v1.etag());
        assertThat(lastHistory(t).path("action").asString()).isEqualTo("RETIRE_REQUESTED");
        assertThat(events(t, "rules.version.retire-requested.v1")).isEqualTo(1);
        // and carol sees confirm-retire, and the host still evaluates v1
        assertThat(send(t, "carol", "GET", "/rule-sets/tet-fee-waiver/versions/1", null, null, null, null)
                .json().path("allowedActions").toString()).isEqualTo("[\"confirm-retire\",\"cancel-retire\"]");
        Reply evaluated = send(t, "teller", "POST", "/decisions/tet-fee-waiver:evaluate",
                body("{\"inputs\":{\"channel\":\"CARD\"}}"), "application/json", null, null);
        assertThat(evaluated.status()).as(evaluated.body()).isEqualTo(200);
        assertThat(evaluated.json().path("version").asInt()).isEqualTo(1);

        // When bob confirms his own request, Then 403 SELF_APPROVAL_FORBIDDEN and nothing changes
        long before = rows(t);
        assertProblem(send(t, "bob", "POST", CONFIRM, null, null, "k-502", requested.etag()), 403,
                "SELF_APPROVAL_FORBIDDEN");
        // and a second request is 409 INVALID_STATE, a confirmation without If-Match 428, a stale one 409
        assertProblem(send(t, "carol", "POST", RETIRE, request, "application/json", "k-503", requested.etag()), 409,
                "INVALID_STATE");
        assertProblem(send(t, "carol", "POST", CONFIRM, null, null, "k-504", null), 428, "PRECONDITION_REQUIRED");
        assertProblem(send(t, "carol", "POST", CONFIRM, null, null, null, requested.etag()), 400, "INPUT_INVALID");
        assertProblem(send(t, "carol", "POST", CONFIRM, null, null, "k-505", v1.etag()), 409, "VERSION_CONFLICT");
        assertProblem(send(t, "lan", "POST", CONFIRM, null, null, "k-506", requested.etag()), 403, "PERMISSION_DENIED");
        assertThat(rows(t)).isEqualTo(before);

        // When carol confirms
        clock.set(Instant.parse("2027-02-17T09:30:00Z"));
        Reply retired = send(t, "carol", "POST", CONFIRM, null, null, "k-507", requested.etag());

        // Then 200 RETIRED, with the history and the event naming bob, carol, the reason and the mode four-eyes
        assertThat(retired.status()).as(retired.body()).isEqualTo(200);
        assertThat(retired.json().path("status").asString()).isEqualTo("RETIRED");
        assertThat(retired.json().path("retiredAt").asString()).isEqualTo("2027-02-17T09:30:00Z");
        assertThat(retired.json().path("retireRequestedBy").isNull()).isTrue();
        JsonNode last = lastHistory(t);
        assertThat(last.path("action").asString()).isEqualTo("RETIRED");
        assertThat(last.path("actor").asString()).isEqualTo("carol");
        assertThat(last.path("retireRequestedBy").asString()).isEqualTo("bob");
        assertThat(last.path("reason").asString()).isEqualTo("Tet promotion ended");
        assertThat(last.path("approvalMode").asString()).isEqualTo("four-eyes");
        assertThat(last.path("noVersionInEffect").asBoolean()).isTrue();
        JsonNode event = JSON.readTree(jdbc.sql("SELECT payload::text FROM rle_decision_outbox WHERE tenant_id = :t"
                + " AND payload->>'eventType' = 'rules.version.retired.v1'").param("t", t).query(String.class).single());
        assertThat(event.path("retiredBy").asString()).isEqualTo("carol");
        assertThat(event.path("requestedBy").asString()).isEqualTo("bob");
        assertThat(event.path("approvalMode").asString()).isEqualTo("four-eyes");
        // and 5 s later the evaluation is 404 NO_EFFECTIVE_VERSION
        clock.set(Instant.parse("2027-02-17T09:30:05Z"));
        assertProblem(send(t, "teller", "POST", "/decisions/tet-fee-waiver:evaluate",
                body("{\"inputs\":{\"channel\":\"CARD\"}}"), "application/json", null, null), 404, "NO_EFFECTIVE_VERSION");
        // and a replay of k-507 is the same 200 with no second event
        Reply again = send(t, "carol", "POST", CONFIRM, null, null, "k-507", requested.etag());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.json().path("retiredAt")).isEqualTo(retired.json().path("retiredAt"));
        assertThat(events(t, "rules.version.retired.v1")).isEqualTo(1);
        // and a confirmation with no pending request is 409 INVALID_STATE
        assertProblem(send(t, "carol", "POST", CONFIRM, null, null, "k-508", retired.etag()), 409, "INVALID_STATE");
    }

    @Test
    void RLE_038_refused_requests_change_nothing_and_a_request_can_be_cancelled() throws Exception {
        String t = "bank-four-eyes-b";
        clock.set(Instant.parse("2027-01-15T00:00:00Z"));
        Reply v1 = publish(t, tetFeeWaiver("2027-01-20T00:00:00Z"), 1);
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        long before = rows(t);

        // an empty reason, and a request for the last version in effect without the confirmation
        assertProblem(send(t, "bob", "POST", RETIRE, body("{\"reason\":\"\"}"), "application/json", "k-1", v1.etag()),
                400, "INPUT_INVALID");
        assertProblem(send(t, "bob", "POST", RETIRE, body("{\"reason\":\"Tet promotion ended\"}"), "application/json",
                "k-2", v1.etag()), 409, "LAST_VERSION_IN_EFFECT");
        // a confirmation or a cancellation with nothing pending
        assertProblem(send(t, "carol", "POST", CONFIRM, null, null, "k-3", v1.etag()), 409, "INVALID_STATE");
        assertProblem(send(t, "carol", "POST", CANCEL, body("{\"reason\":\"Extended\"}"), "application/json", "k-4",
                v1.etag()), 409, "INVALID_STATE");
        assertThat(rows(t)).isEqualTo(before);

        // When bob requests it and carol cancels with a reason, Then v1 stays PUBLISHED with nothing pending
        Reply requested = send(t, "bob", "POST", RETIRE,
                body("{\"reason\":\"Tet promotion ended\",\"confirmNoVersionInEffect\":true}"), "application/json",
                "k-5", v1.etag());
        assertThat(requested.status()).as(requested.body()).isEqualTo(200);
        assertProblem(send(t, "carol", "POST", CANCEL, body("{\"reason\":\" \"}"), "application/json", "k-6",
                requested.etag()), 400, "INPUT_INVALID");
        Reply cancelled = send(t, "carol", "POST", CANCEL, body("{\"reason\":\"Promotion extended to March\"}"),
                "application/json", "k-7", requested.etag());
        assertThat(cancelled.status()).as(cancelled.body()).isEqualTo(200);
        assertThat(cancelled.json().path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(cancelled.json().path("retireRequestedBy").isNull()).isTrue();
        assertThat(cancelled.json().path("allowedActions").toString()).isEqualTo("[\"retire\"]");
        JsonNode last = lastHistory(t);
        assertThat(last.path("action").asString()).isEqualTo("RETIRE_CANCELLED");
        assertThat(last.path("actor").asString()).isEqualTo("carol");
        assertThat(last.path("reason").asString()).isEqualTo("Promotion extended to March");
        assertThat(events(t, "rules.version.retired.v1")).isZero();
    }

    JsonNode meta(String tenant) throws Exception {
        HttpResponse<String> meta = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/meta"))
                .header("Authorization", TestTokens.bearerFor("dan", tenant, PERMISSIONS.get("dan"))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(meta.body()).path("modules").get(0).path("settings");
    }

    JsonNode lastHistory(String tenant) throws Exception {
        JsonNode history = send(tenant, "dan", "GET", "/rule-sets/tet-fee-waiver/versions/1/history", null, null, null,
                null).json().path("data");
        return history.get(history.size() - 1);
    }

    long events(String tenant, String type) {
        return jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t AND payload->>'eventType' = :type")
                .param("t", tenant).param("type", type).query(Long.class).single();
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
