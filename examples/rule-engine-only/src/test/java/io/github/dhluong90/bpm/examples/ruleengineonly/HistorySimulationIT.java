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
import java.util.List;
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
 * The history simulation demo over HTTP (RLE-015): card-fee v1 is in force and the tellers have made 100 decisions,
 * 18 of them for STANDARD customers with one transaction, who fall through to the default fee. Draft v2 from
 * {@code rules/card-fee-v2.yaml} lowers that fee from 50,000 VND to 30,000 VND. Replaying the 100 decisions through
 * v2 flags 18, and the decision log does not grow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class HistorySimulationIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String REPLAYER = TestTokens.bearerFor("pricing-approver", "bank-a",
            List.of("rules:read", "rules:approve", "rules:simulate", "rules:log:read"));
    static final String DECEMBER_15 = "{\"from\":\"2026-12-15T00:00:00Z\",\"to\":\"2026-12-16T00:00:00Z\"}";

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
    void RLE_015_simulation_leaves_decision_log_untouched() throws Exception {
        // Given card-fee-hist v1 with 100 logged decisions, and v2 imported as a draft
        String name = "card-fee-hist";
        publishV1(name);
        for (int i = 0; i < 100; i++) {
            String inputs = (i % 6 == 0 || i == 99) ? "{\"tier\":\"STANDARD\",\"txCount\":1}"
                    : "{\"tier\":\"GOLD\",\"txCount\":5}";
            assertThat(send(TestTokens.bearer("teller"), "/decisions/" + name + ":evaluate",
                    ("{\"inputs\":" + inputs + "}").getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(200);
        }
        assertThat(send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions", "application/yaml",
                renamed("/rules/card-fee-v2.yaml", name)).status()).isEqualTo(201);
        long decisions = decisionRows(name);

        // When the approver replays the day's decisions through v2
        Reply replay = send(REPLAYER, "/rule-sets/" + name + "/versions/2:simulate-history",
                DECEMBER_15.getBytes(StandardCharsets.UTF_8));

        // Then
        JsonNode body = replay.json();
        System.out.println("RLE-015 replay of " + name + " v2 over its logged decisions: "
                + body.path("decisionCount").asInt() + " replayed, " + body.path("changedCount").asInt()
                + " changed; decision rows before " + decisions + ", after " + decisionRows(name));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.contentType()).startsWith("application/json");
        assertThat(decisions).isEqualTo(100);
        assertThat(body.path("status").asString()).isEqualTo("DRAFT");
        assertThat(body.path("decisionCount").asInt()).isEqualTo(100);
        assertThat(body.path("replayedCount").asInt()).isEqualTo(100);
        assertThat(body.path("changedCount").asInt()).isEqualTo(18);
        assertThat(body.path("notReplayableCount").asInt()).isZero();
        assertThat(body.path("truncated").asBoolean()).isFalse();
        JsonNode changed = null;
        for (JsonNode item : body.path("results")) {
            if (item.path("changed").asBoolean()) {
                changed = item;
                break;
            }
        }
        assertThat(changed).isNotNull();
        assertThat(changed.path("loggedVersion").asInt()).isEqualTo(1);
        assertThat(changed.path("maskedInputs").isEmpty()).isTrue();
        assertThat(changed.path("simulated").toString()).isEqualTo("{\"outcome\":\"MATCHED\",\"matchedRuleIds\":[\"R3\"],"
                + "\"outputs\":{\"fee\":{\"currency\":\"VND\",\"amount\":30000}}}");
        assertThat(changed.path("logged").toString()).isEqualTo("{\"outcome\":\"MATCHED\",\"matchedRuleIds\":[\"R3\"],"
                + "\"outputs\":{\"fee\":{\"currency\":\"VND\",\"amount\":50000}}}");
        assertThat(decisionRows(name)).isEqualTo(decisions);

        // And a run of 10 replays the 10 newest and says there were more
        JsonNode ten = send(REPLAYER, "/rule-sets/" + name + "/versions/2:simulate-history",
                "{\"from\":\"2026-12-15T00:00:00Z\",\"to\":\"2026-12-16T00:00:00Z\",\"limit\":10}"
                        .getBytes(StandardCharsets.UTF_8)).json();
        assertThat(ten.path("decisionCount").asInt()).isEqualTo(10);
        assertThat(ten.path("truncated").asBoolean()).isTrue();
    }

    @Test
    void RLE_015_without_rules_log_read_or_rules_simulate_it_is_403_and_nothing_is_read() throws Exception {
        // Given
        String name = "card-fee-hist-403";
        publishV1(name);
        byte[] period = DECEMBER_15.getBytes(StandardCharsets.UTF_8);

        // When the simulator of RLE-016, who may not read the log, and an auditor who may not simulate ask
        Reply simulator = send(TestTokens.bearerFor("pricing-approver", "bank-a", List.of("rules:simulate")),
                "/rule-sets/" + name + "/versions/1:simulate-history", period);
        Reply auditor = send(TestTokens.bearerFor("auditor", "bank-a", List.of("rules:log:read")),
                "/rule-sets/" + name + "/versions/1:simulate-history", period);

        // Then
        assertThat(simulator.status()).isEqualTo(403);
        assertThat(simulator.json().path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(auditor.status()).isEqualTo(403);
    }

    @Test
    void RLE_015_another_tenant_is_404_and_the_body_cannot_choose_the_tenant() throws Exception {
        // Given card-fee-hist-tb v1 exists only in tenant-b, with one decision, and the body names tenant-b
        assertThat(send(TestTokens.bearer("carol"), "/rule-sets", "application/yaml",
                renamed("/rules/card-fee.yaml", "card-fee-hist-tb")).status()).isEqualTo(201);
        byte[] period = "{\"tenantId\":\"tenant-b\",\"from\":\"2026-12-15T00:00:00Z\",\"to\":\"2026-12-16T00:00:00Z\"}"
                .getBytes(StandardCharsets.UTF_8);
        long before = jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single();

        // When a bank-a caller replays it
        Reply crossTenant = send(REPLAYER, "/rule-sets/card-fee-hist-tb/versions/1:simulate-history", period);

        // Then
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(crossTenant.json().path("code").asString()).isEqualTo("VERSION_NOT_FOUND");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void RLE_015_a_period_over_31_days_or_a_malformed_instant_is_400_query_invalid() throws Exception {
        // Given
        String name = "card-fee-hist-400";
        publishV1(name);
        String path = "/rule-sets/" + name + "/versions/1:simulate-history";

        // When
        Reply tooLong = send(REPLAYER, path,
                "{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-12-03T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8));
        Reply malformed = send(REPLAYER, path,
                "{\"from\":\"1 Nov 2026\",\"to\":\"2026-12-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8));
        Reply overLimit = send(REPLAYER, path,
                "{\"from\":\"2026-11-01T00:00:00Z\",\"to\":\"2026-12-01T00:00:00Z\",\"limit\":1001}"
                        .getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.json().path("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(tooLong.json().path("detail").asString()).contains("query.max-range");
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(malformed.json().path("detail").asString()).contains("from must be an ISO 8601 instant");
        assertThat(overLimit.status()).isEqualTo(400);
        assertThat(overLimit.json().path("detail").asString()).contains("simulation.max-inputs");
    }

    private void publishV1(String name) throws Exception {
        assertThat(send(TestTokens.bearer("alice"), "/rule-sets", "application/yaml",
                renamed("/rules/card-fee.yaml", name)).status()).isEqualTo(201);
        assertThat(send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions/1:request-publish", null, null)
                .status()).isEqualTo(200);
        assertThat(send(TestTokens.bearer("bob"), "/rule-sets/" + name + "/versions/1:approve", null, null).status())
                .isEqualTo(200);
    }

    private long decisionRows(String ruleSet) {
        return jdbc.sql("SELECT count(*) FROM rle_decision WHERE rule_set_name = :name").param("name", ruleSet)
                .query(Long.class).single();
    }

    private static byte[] renamed(String resource, String name) {
        return new String(RuleLifecycleIT.resource(resource), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name).getBytes(StandardCharsets.UTF_8);
    }

    /** Reads a resource as the same caller; an approval reads the version first to name the content it decides on. */
    private Reply get(String bearer, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private Reply send(String bearer, String path, byte[] json) throws Exception {
        return send(bearer, path, "application/json", json);
    }

    private Reply send(String bearer, String path, String contentType, byte[] body) throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> get(bearer, p).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", bearer)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        // An approval names the lock version it was decided on; these tests approve a version just requested, at 1.
        if (path.endsWith(":approve")) {
            request.header("If-Match", "\"1\"");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""), response.body());
    }

    record Reply(int status, String contentType, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
