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
 * What-if comparison over a past period over HTTP (RLE-075, RLE-077), on PostgreSQL with the host's monthly decision
 * partitions and {@code simulation.max-history-decisions} lowered to 200. lan authors card-fee v1 and a draft v2 that
 * differs in R3, minh approves v1, and thu, who holds {@code rules:simulate} and {@code rules:log:read}, compares them
 * over December 2026.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"horus.bpm.rules.authoring.cockpit-drafts=true",
                "horus.bpm.rules.simulation.max-history-decisions=200"})
@Testcontainers
class HistoryComparisonHostIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "minh", List.of("rules:read", "rules:approve"),
            "thu", List.of("rules:read", "rules:simulate", "rules:log:read"),
            "dan", List.of("rules:read", "rules:log:read"));

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

    static final String DEC_01 = "2026-12-01T00:00:00Z";

    /** card-fee v1 published and v2 a draft with R3 at 30,000 VND; 300 December decisions, one every 100 minutes. */
    void seed(String tenant) throws Exception {
        assertThat(send(tenant, "lan", "POST", "/rule-sets", cardFee("50000 VND"), "application/yaml", null, null).status())
                .isEqualTo(201);
        Reply pending = send(tenant, "lan", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null, "rp-1", null);
        assertThat(send(tenant, "minh", "POST", "/rule-sets/card-fee/versions/1:approve", null, null, "ap-1", pending.etag())
                .status()).isEqualTo(200);
        assertThat(send(tenant, "lan", "POST", "/rule-sets/card-fee/versions", cardFee("30000 VND"), "application/yaml", null,
                null).status()).isEqualTo(201);
        // 60 GOLD decisions (R1), 90 with three transactions (R2) and 150 others (R3)
        jdbc.sql("INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome, "
                        + "masked_inputs, outputs, matched_rule_ids, eval_clock, duration_micros, log_mode) "
                        + "SELECT :t, :from::timestamptz + (g * 100 || ' minutes')::interval, "
                        + "'01' || lpad(g::text, 24, '0'), 'card-fee', 1, 'FIRST', 'OK', "
                        + "CASE WHEN g % 10 < 2 THEN '{\"tier\":\"GOLD\",\"txCount\":5}'::jsonb "
                        + "WHEN g % 10 < 5 THEN '{\"tier\":\"SILVER\",\"txCount\":5}'::jsonb "
                        + "ELSE '{\"tier\":\"SILVER\",\"txCount\":1}'::jsonb END, "
                        + "'{}'::jsonb, '[]'::jsonb, :from::timestamptz + (g * 100 || ' minutes')::interval, 31, 'TRANSACTIONAL' "
                        + "FROM generate_series(0, 299) g")
                .param("t", tenant).param("from", DEC_01).update();
    }

    String compare(String base, String candidate, String from, String to) {
        return "{\"base\":" + base + ",\"candidate\":" + candidate + ",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
    }

    @Test
    void RLE_075_two_versions_are_compared_over_a_period_over_http_with_totals_side_by_side() throws Exception {
        // Given 300 December decisions of card-fee, the first 150 of which fall before 11 December 10:00
        String t = "bank-cmp";
        seed(t);
        long before = rows(t);
        String path = "/rule-sets/card-fee:compare";
        String period = compare("1", "2", DEC_01, "2026-12-11T10:00:00Z");

        // When thu compares v1 with v2 over the first 150 decisions, then repeats it
        Reply first = send(t, "thu", "POST", path, body(period), "application/json", null, null);
        Reply second = send(t, "thu", "POST", path, body(period), "application/json", null, null);

        // Then 200 with totals in minor units as text, the transitions, both hashes and no input of a customer
        assertThat(first.status()).as(first.body()).isEqualTo(200);
        JsonNode json = first.json();
        assertThat(json.path("decisionsCompared").asInt()).isEqualTo(150);
        assertThat(json.path("changedCount").asInt()).isEqualTo(75);
        assertThat(json.path("outcomes").path("base").path("MATCHED").asInt()).isEqualTo(150);
        assertThat(json.path("totals")).hasSize(1);
        assertThat(json.path("totals").get(0).path("output").asString()).isEqualTo("fee");
        assertThat(json.path("totals").get(0).path("currency").asString()).isEqualTo("VND");
        assertThat(json.path("totals").get(0).path("base").asString()).isEqualTo("4200000");
        assertThat(json.path("totals").get(0).path("candidate").asString()).isEqualTo("2700000");
        assertThat(json.path("totals").get(0).path("difference").asString()).isEqualTo("-1500000");
        assertThat(json.path("transitions").get(0).path("count").asInt()).isEqualTo(75);
        assertThat(json.path("transitions").get(0).path("from").path("amount").asInt()).isEqualTo(50000);
        assertThat(json.path("transitions").get(0).path("to").path("amount").asInt()).isEqualTo(30000);
        assertThat(json.path("baseCanonicalHash").asString()).hasSize(64);
        assertThat(json.path("skipped").size()).isZero();
        assertThat(first.body()).doesNotContain("tier", "txCount");
        assertThat(second.body()).isEqualTo(first.body());
        // And nothing was written
        assertThat(rows(t)).isEqualTo(before);
    }

    @Test
    void RLE_077_over_http_the_cap_the_period_the_versions_the_tenant_and_the_permissions_are_enforced() throws Exception {
        // Given 300 December decisions and a cap of 200
        String t = "bank-cmp-b";
        seed(t);
        long before = rows(t);
        String path = "/rule-sets/card-fee:compare";

        // The whole period is 422 HISTORY_TOO_LARGE naming the 300 found and the setting; never a sample
        Reply large = send(t, "thu", "POST", path, body(compare("1", "2", DEC_01, "2026-12-25T00:00:00Z")),
                "application/json", null, null);
        assertProblem(large, 422, "HISTORY_TOO_LARGE");
        assertThat(large.json().path("found").asInt()).isEqualTo(300);
        assertThat(large.json().path("maxHistoryDecisions").asInt()).isEqualTo(200);
        assertThat(large.json().path("detail").asString()).contains("simulation.max-history-decisions");
        // A 32-day period and equal versions are 400 QUERY_INVALID; a missing period or a malformed body are 400 too
        assertProblem(send(t, "thu", "POST", path, body(compare("1", "2", DEC_01, "2027-01-02T00:00:00Z")),
                "application/json", null, null), 400, "QUERY_INVALID");
        assertProblem(send(t, "thu", "POST", path, body(compare("2", "2", DEC_01, "2026-12-02T00:00:00Z")),
                "application/json", null, null), 400, "QUERY_INVALID");
        assertProblem(send(t, "thu", "POST", path, body("{\"base\":1,\"candidate\":2,\"to\":\"2026-12-02T00:00:00Z\"}"),
                "application/json", null, null), 400, "QUERY_INVALID");
        assertProblem(send(t, "thu", "POST", path, body("{\"base\":\"one\",\"candidate\":2}"), "application/json", null,
                null), 400, "INPUT_INVALID");
        // Version 9 is 404 VERSION_NOT_FOUND; another tenant's caller gets 404 RULE_SET_NOT_FOUND
        assertProblem(send(t, "thu", "POST", path, body(compare("1", "9", DEC_01, "2026-12-02T00:00:00Z")),
                "application/json", null, null), 404, "VERSION_NOT_FOUND");
        assertProblem(send("bank-cmp-other", "thu", "POST", path, body(compare("1", "2", DEC_01, "2026-12-02T00:00:00Z")),
                "application/json", null, null), 404, "RULE_SET_NOT_FOUND");
        // dan holds rules:log:read only: 403 PERMISSION_DENIED, before the body is read
        assertProblem(send(t, "dan", "POST", path, body(compare("1", "2", DEC_01, "2026-12-02T00:00:00Z")),
                "application/json", null, null), 403, "PERMISSION_DENIED");
        assertProblem(send(t, "thu", "POST", path, new byte[20_000], "application/json", null, null), 413,
                "PAYLOAD_TOO_LARGE");
        // And nothing was written
        assertThat(rows(t)).isEqualTo(before);
    }

    long rows(String tenant) {
        return jdbc.sql("""
                SELECT (SELECT count(*) FROM rle_version WHERE tenant_id = :t)
                     + (SELECT coalesce(sum(lock_version), 0) FROM rle_version WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_version_history WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_decision_outbox WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_idempotency WHERE tenant_id = :t)
                     + (SELECT count(*) FROM rle_decision WHERE tenant_id = :t)
                """).param("t", tenant).query(Long.class).single();
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo(code);
    }

    static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] cardFee(String r3Fee) {
        return new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("then: { fee: \"50000 VND\" }", "then: { fee: \"" + r3Fee + "\" }")
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
