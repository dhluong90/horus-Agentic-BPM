package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
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
 * The error matrix and the duplicate guard end to end, through the example host's own security and a real
 * PostgreSQL (RLE-036, RLE-014, TR-SEC-08, TR-IDEM-01, TR-IDEM-05). Each error is triggered the way a client
 * would trigger it; the test prints one report line per code for the sprint review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ErrorsAndDuplicatesIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";

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

    @Autowired
    DataSource dataSource;

    /** One triggered error: what was sent, what came back and what the matrix expects. */
    record Triggered(String code, int status, Reply reply) {
    }

    @Test
    void RLE_036_every_rest_error_is_problem_details_with_stable_code() throws Exception {
        // Given
        publish("fee-matrix");
        importDraft("fee-matrix-draft");
        publishFile("/rules/loan-pricing.yaml", "loan-pricing");
        send("alice", "POST", "/rule-sets", renamed("fee-matrix-pending"), "application/yaml", null, Map.of());
        send("alice", "POST", "/rule-sets/fee-matrix-pending/versions/1:request-publish", null, null, null, Map.of());
        byte[] invalid = new String(renamed("fee-matrix-invalid"), StandardCharsets.UTF_8)
                .replace("fee: \"10000 VND\"", "fee: \"10000\"").getBytes(StandardCharsets.UTF_8);
        byte[] gold = "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8);
        send("alice", "POST", "/rule-sets", renamed("fee-matrix-key"), "application/yaml", "k-201", Map.of());

        // When
        List<Triggered> matrix = new ArrayList<>();
        matrix.add(new Triggered("INPUT_INVALID", 400,
                send("alice", "POST", "/rule-sets", "tier,fee".getBytes(StandardCharsets.UTF_8), "text/csv", null, Map.of())));
        matrix.add(new Triggered("QUERY_INVALID", 400, send("alice", "GET", "/rule-sets?limit=0", null, null, null, Map.of())));
        matrix.add(new Triggered("UNAUTHENTICATED", 401, send(null, "GET", "/rule-sets/fee-matrix", null, null, null, Map.of())));
        matrix.add(new Triggered("PERMISSION_DENIED", 403, send("teller", "GET", "/rule-sets/fee-matrix", null, null, null, Map.of())));
        matrix.add(new Triggered("TENANT_NOT_ALLOWED", 403,
                send("alice", "GET", "/rule-sets/fee-matrix", null, null, null, Map.of("X-Tenant-Id", "tenant-b"))));
        matrix.add(new Triggered("SELF_APPROVAL_FORBIDDEN", 403,
                send("alice", "POST", "/rule-sets/fee-matrix-pending/versions/1:approve", null, null, null,
                        Map.of("If-Match", "\"1\""))));
        matrix.add(new Triggered("RULE_SET_NOT_FOUND", 404, send("alice", "GET", "/rule-sets/no-such-fee", null, null, null, Map.of())));
        matrix.add(new Triggered("VERSION_NOT_FOUND", 404,
                send("alice", "GET", "/rule-sets/fee-matrix/versions/9", null, null, null, Map.of())));
        matrix.add(new Triggered("NO_EFFECTIVE_VERSION", 404,
                send("teller", "POST", "/decisions/fee-matrix-draft:evaluate", gold, "application/json", null, Map.of())));
        matrix.add(new Triggered("VERSION_CONFLICT", 409, send("alice", "POST",
                "/rule-sets/fee-matrix-draft/versions/1:request-publish", null, null, null, Map.of("If-Match", "\"7\""))));
        matrix.add(new Triggered("INVALID_STATE", 409,
                send("bob", "POST", "/rule-sets/fee-matrix-draft/versions/1:approve", null, null, null,
                        Map.of("If-Match", "\"0\""))));
        matrix.add(new Triggered("RULE_SET_EXISTS", 409,
                send("alice", "POST", "/rule-sets", renamed("fee-matrix"), "application/yaml", null, Map.of())));
        matrix.add(new Triggered("PAYLOAD_TOO_LARGE", 413, send("bob", "POST",
                "/rule-sets/fee-matrix-pending/versions/1:approve", new byte[16_385], "application/json", null,
                Map.of("If-Match", "\"1\""))));
        matrix.add(new Triggered("VALIDATION_FAILED", 422,
                send("alice", "POST", "/rule-sets", invalid, "application/yaml", null, Map.of())));
        matrix.add(new Triggered("IDEMPOTENCY_KEY_REUSE_MISMATCH", 422,
                send("alice", "POST", "/rule-sets", renamed("fee-matrix-other"), "application/yaml", "k-201", Map.of())));
        matrix.add(new Triggered("UNIQUE_HIT_VIOLATION", 422, send("teller", "POST", "/decisions/loan-pricing:evaluate",
                "{\"inputs\":{\"score\":750,\"income\":{\"currency\":\"VND\",\"amount\":25000000}}}"
                        .getBytes(StandardCharsets.UTF_8), "application/json", null, Map.of())));

        // Then
        System.out.println("RLE-036 error matrix over the example host");
        for (Triggered row : matrix) {
            Reply reply = row.reply();
            JsonNode problem = reply.json();
            System.out.printf(Locale.ROOT, "  %-31s expected %d got %d  %s  correlationId=%s%n", row.code(), row.status(),
                    reply.status(), reply.contentType(), problem.path("correlationId").asString());
            assertThat(reply.status()).as(row.code()).isEqualTo(row.status());
            assertThat(reply.contentType()).as(row.code()).startsWith("application/problem+json");
            assertThat(problem.path("code").asString()).as(row.code()).isEqualTo(row.code());
            assertThat(problem.path("type").asString()).as(row.code())
                    .isEqualTo(TYPE_BASE + row.code().toLowerCase(Locale.ROOT));
            assertThat(problem.path("status").asInt()).as(row.code()).isEqualTo(row.status());
            assertThat(problem.path("detail").asString()).as(row.code()).isNotBlank();
            assertThat(problem.path("correlationId").asString()).as(row.code()).isNotBlank();
            assertThat(reply.body()).as(row.code()).doesNotContain("Exception", "\tat ", "SQL", "rle_");
        }
        assertThat(matrix.get(matrix.size() - 3).reply().json().path("violations").get(0).path("column").asString())
                .isEqualTo("fee");
    }

    @Test
    void TR_IDEM_05_in_flight_duplicate_gets_409_retry_after_then_replays_the_original() throws Exception {
        // Given alice's import with k-101 has taken its key and waits inside the engine for the rule set row,
        // which this test holds locked
        publish("card-fee-paused");
        byte[] body = renamed("card-fee-paused");
        CompletableFuture<Reply> first;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM rle_rule_set WHERE tenant_id = 'bank-a' AND name = 'card-fee-paused' FOR UPDATE")) {
                lock.executeQuery().close();
            }
            first = CompletableFuture.supplyAsync(() -> sendUnchecked("alice", "POST", "/rule-sets/card-fee-paused/versions",
                    body, "application/yaml", "k-101"));
            awaitBlockedOnTheRuleSetRow();

            // When the same request is sent again while the first is still running
            Reply duplicate = send("alice", "POST", "/rule-sets/card-fee-paused/versions", body, "application/yaml",
                    "k-101", Map.of());

            // Then it is refused at once, and nothing is created for it
            assertThat(duplicate.status()).isEqualTo(409);
            assertThat(duplicate.retryAfter()).isEqualTo("1");
            assertThat(duplicate.contentType()).startsWith("application/problem+json");
            assertThat(duplicate.json().path("code").asString()).isEqualTo("IDEMPOTENCY_IN_FLIGHT");
            assertThat(first).isNotDone();
            holder.rollback();
        }

        // When the first completes and the client retries after Retry-After
        Reply original = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
        Reply replay = send("alice", "POST", "/rule-sets/card-fee-paused/versions", body, "application/yaml", "k-101",
                Map.of());

        // Then the retry gets the first result, and there is one version 2
        assertThat(original.status()).isEqualTo(201);
        assertThat(original.json().path("version").asInt()).isEqualTo(2);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(original.body());
        assertThat(versionCount("card-fee-paused")).isEqualTo(2L);
    }

    @Test
    void TR_IDEM_01_replayed_lifecycle_commands_change_nothing_twice() throws Exception {
        // Given v1 of card-fee-replay as a draft, and v1 of card-fee-sent-back pending approval
        importDraft("card-fee-replay");
        importDraft("card-fee-sent-back");
        send("alice", "POST", "/rule-sets/card-fee-sent-back/versions/1:request-publish", null, null, null, Map.of());
        byte[] reason = "{\"reason\":\"fee for SILVER missing\"}".getBytes(StandardCharsets.UTF_8);

        // When every command is sent twice with the same key and body
        Reply request = send("alice", "POST", "/rule-sets/card-fee-replay/versions/1:request-publish", null, null, "k-301",
                Map.of("If-Match", "\"0\""));
        Reply requestAgain = send("alice", "POST", "/rule-sets/card-fee-replay/versions/1:request-publish", null, null,
                "k-301", Map.of("If-Match", "\"0\""));
        Reply approve = send("bob", "POST", "/rule-sets/card-fee-replay/versions/1:approve", null, null, "k-302",
                Map.of("If-Match", "\"1\""));
        Reply approveAgain = send("bob", "POST", "/rule-sets/card-fee-replay/versions/1:approve", null, null, "k-302",
                Map.of("If-Match", "\"1\""));
        Reply reject = send("bob", "POST", "/rule-sets/card-fee-sent-back/versions/1:reject", reason, "application/json",
                "k-303", Map.of("If-Match", "\"1\""));
        Reply rejectAgain = send("bob", "POST", "/rule-sets/card-fee-sent-back/versions/1:reject", reason,
                "application/json", "k-303", Map.of("If-Match", "\"1\""));

        // Then each replay is the original answer, and each command left one history row and one event
        for (Reply[] pair : new Reply[][] {{request, requestAgain}, {approve, approveAgain}, {reject, rejectAgain}}) {
            assertThat(pair[0].status()).isEqualTo(200);
            assertThat(pair[1].status()).isEqualTo(200);
            assertThat(pair[1].body()).isEqualTo(pair[0].body());
        }
        assertThat(history("card-fee-replay")).containsExactly("CREATED", "REQUESTED", "APPROVED");
        assertThat(history("card-fee-sent-back")).containsExactly("CREATED", "REQUESTED", "REJECTED");
        assertThat(events("card-fee-replay")).isEqualTo(3L);
        assertThat(events("card-fee-sent-back")).isEqualTo(3L);
    }

    private void awaitBlockedOnTheRuleSetRow() {
        org.awaitility.Awaitility.await("the first request waits for the rule set row lock")
                .atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20))
                .until(() -> jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                        + "AND query LIKE '%FROM rle_rule_set%FOR UPDATE%'").query(Long.class).single() > 0);
    }

    private List<String> history(String ruleSet) {
        return jdbc.sql("""
                SELECT h.action FROM rle_version_history h
                JOIN rle_version v ON v.tenant_id = h.tenant_id AND v.id = h.version_id
                JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE h.tenant_id = 'bank-a' AND s.name = :name ORDER BY h.occurred_at, h.id
                """).param("name", ruleSet).query(String.class).list();
    }

    private Long events(String ruleSet) {
        return jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = 'bank-a' AND payload->>'ruleSet' = :name")
                .param("name", ruleSet).query(Long.class).single();
    }

    private Long versionCount(String ruleSet) {
        return jdbc.sql("SELECT count(*) FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id "
                + "AND s.id = v.rule_set_id WHERE v.tenant_id = 'bank-a' AND s.name = :name")
                .param("name", ruleSet).query(Long.class).single();
    }

    private void importDraft(String name) throws Exception {
        assertThat(send("alice", "POST", "/rule-sets", renamed(name), "application/yaml", null, Map.of()).status())
                .isEqualTo(201);
    }

    private void publish(String name) throws Exception {
        importDraft(name);
        assertThat(send("alice", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null, Map.of())
                .status()).isEqualTo(200);
        assertThat(send("bob", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null,
                Map.of("If-Match", "\"1\"")).status())
                .isEqualTo(200);
    }

    private void publishFile(String resource, String name) throws Exception {
        assertThat(send("alice", "POST", "/rule-sets", RuleLifecycleIT.resource(resource), "application/yaml", null,
                Map.of()).status()).isEqualTo(201);
        assertThat(send("alice", "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null, Map.of())
                .status()).isEqualTo(200);
        assertThat(send("bob", "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null,
                Map.of("If-Match", "\"1\"")).status())
                .isEqualTo(200);
    }

    private static byte[] renamed(String name) {
        return RuleLifecycleIT.renamed(name);
    }

    private Reply sendUnchecked(String user, String method, String path, byte[] body, String contentType, String key) {
        try {
            return send(user, method, path, body, contentType, key, Map.of());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Reply send(String user, String method, String path, byte[] body, String contentType, String idempotencyKey,
            Map<String, String> headers) throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> send(user, "GET", p, null, null, null, java.util.Map.of()).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (user != null) {
            request.header("Authorization", TestTokens.bearer(user));
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        headers.forEach(request::header);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("Retry-After").orElse(null), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    record Reply(int status, String contentType, String retryAfter, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
