package com.horus.bpm.examples.ruleengineonly;

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
import java.util.Locale;
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
 * The simulation demo over HTTP (RLE-016): card-fee v1 is in force, v2 from {@code rules/card-fee-v2.yaml} lowers the
 * default fee from 50,000 VND to 30,000 VND, and the five customers of {@code rules/card-fee-samples.json} are run
 * through both. Two are flagged, and the decision log does not grow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SimulationIT {

    static final Instant HOST_NOW = Instant.parse("2026-12-15T02:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String SIMULATOR = TestTokens.bearerFor("pricing-approver", "bank-a",
            List.of("rules:read", "rules:approve", "rules:simulate"));

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
    void RLE_016_sample_simulation_leaves_decision_log_untouched() throws Exception {
        // Given card-fee v1 in force with one logged decision, and v2 imported as a draft
        String name = "card-fee-sim";
        publishV1(name);
        assertThat(send(TestTokens.bearer("teller"), "/decisions/" + name + ":evaluate",
                "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}".getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(200);
        Reply draft = send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions", "application/yaml",
                renamed("/rules/card-fee-v2.yaml", name));
        assertThat(draft.status()).isEqualTo(201);
        long decisions = decisionRows();

        // When the approver simulates the five sample customers
        Reply simulation = send(SIMULATOR, "/rule-sets/" + name + "/versions/2:simulate",
                RuleLifecycleIT.resource("/rules/card-fee-samples.json"));

        // Then
        JsonNode body = simulation.json();
        System.out.println("RLE-016 simulation of " + name + " v2 against v1");
        for (JsonNode result : body.path("results")) {
            System.out.printf(Locale.ROOT, "  sample %d  v2 %s %s  v1 %s %s  %s%n", result.path("index").asInt(),
                    result.path("simulated").path("matchedRuleIds"), result.path("simulated").path("outputs").path("fee"),
                    result.path("baseline").path("matchedRuleIds"), result.path("baseline").path("outputs").path("fee"),
                    result.path("changed").asBoolean() ? "CHANGED" : "same");
        }
        System.out.println("  decision rows before " + decisions + ", after " + decisionRows());
        assertThat(simulation.status()).isEqualTo(200);
        assertThat(simulation.contentType()).startsWith("application/json");
        assertThat(body.path("version").asInt()).isEqualTo(2);
        assertThat(body.path("status").asString()).isEqualTo("DRAFT");
        assertThat(body.path("baselineVersion").asInt()).isEqualTo(1);
        assertThat(body.path("sampleCount").asInt()).isEqualTo(5);
        assertThat(body.path("changedCount").asInt()).isEqualTo(2);
        assertThat(body.path("results").get(3).toString()).isEqualTo("{\"index\":3,\"changed\":true,"
                + "\"simulated\":{\"outcome\":\"MATCHED\",\"matchedRuleIds\":[\"R3\"],\"outputs\":{\"fee\":{\"currency\":\"VND\",\"amount\":30000}}},"
                + "\"baseline\":{\"outcome\":\"MATCHED\",\"matchedRuleIds\":[\"R3\"],\"outputs\":{\"fee\":{\"currency\":\"VND\",\"amount\":50000}}}}");
        assertThat(body.path("results").get(1).path("changed").asBoolean()).isFalse();
        assertThat(decisionRows()).isEqualTo(decisions);
    }

    @Test
    void TR_DET_01_simulating_the_same_draft_twice_gives_identical_responses() throws Exception {
        // Given
        String name = "card-fee-det";
        publishV1(name);
        send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions", "application/yaml",
                renamed("/rules/card-fee-v2.yaml", name));
        byte[] samples = RuleLifecycleIT.resource("/rules/card-fee-samples.json");

        // When
        Reply first = send(SIMULATOR, "/rule-sets/" + name + "/versions/2:simulate", samples);
        Reply second = send(SIMULATOR, "/rule-sets/" + name + "/versions/2:simulate", samples);

        // Then
        assertThat(first.status()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
    }

    @Test
    void RLE_016_caller_without_rules_simulate_gets_403_and_nothing_is_evaluated() throws Exception {
        // Given alice is author and approver, but holds no rules:simulate
        String name = "card-fee-nosim";
        publishV1(name);
        send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions", "application/yaml",
                renamed("/rules/card-fee-v2.yaml", name));
        long decisions = decisionRows();

        // When
        Reply refused = send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions/2:simulate",
                RuleLifecycleIT.resource("/rules/card-fee-samples.json"));

        // Then
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.contentType()).startsWith("application/problem+json");
        assertThat(refused.json().path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(refused.body()).doesNotContain("R3", "30000", "50000");
        assertThat(decisionRows()).isEqualTo(decisions);
    }

    @Test
    void RLE_016_a_version_of_another_tenant_is_404_version_not_found() throws Exception {
        // Given card-fee-tb v1 exists only in tenant-b, and the body names tenant-b
        assertThat(send(TestTokens.bearer("carol"), "/rule-sets", "application/yaml",
                renamed("/rules/card-fee.yaml", "card-fee-tb")).status()).isEqualTo(201);
        byte[] samples = "{\"tenantId\":\"tenant-b\",\"samples\":[{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":5}}]}"
                .getBytes(StandardCharsets.UTF_8);

        // When a bank-a caller simulates it
        Reply crossTenant = send(SIMULATOR, "/rule-sets/card-fee-tb/versions/1:simulate", samples);

        // Then
        assertThat(crossTenant.status()).isEqualTo(404);
        assertThat(crossTenant.json().path("code").asString()).isEqualTo("VERSION_NOT_FOUND");
        assertThat(send(TestTokens.bearerFor("tenant-b-approver", "tenant-b", List.of("rules:simulate")),
                "/rule-sets/card-fee-tb/versions/1:simulate", samples).status()).isEqualTo(200);
    }

    @Test
    void RLE_016_no_samples_and_malformed_samples_are_400_input_invalid() throws Exception {
        // Given
        String name = "card-fee-bad";
        publishV1(name);

        // When
        Reply empty = send(SIMULATOR, "/rule-sets/" + name + "/versions/1:simulate",
                "{\"samples\":[]}".getBytes(StandardCharsets.UTF_8));
        Reply notInputs = send(SIMULATOR, "/rule-sets/" + name + "/versions/1:simulate",
                "{\"samples\":[{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":1}},{\"tier\":\"GOLD\"}]}".getBytes(StandardCharsets.UTF_8));
        Reply unbounded = send(SIMULATOR, "/rule-sets/" + name + "/versions/1:simulate",
                "{\"samples\":[{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":1.5e400}}]}".getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.json().path("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(notInputs.status()).isEqualTo(400);
        assertThat(notInputs.json().path("detail").asString()).startsWith("samples[1]");
        assertThat(unbounded.status()).isEqualTo(400);
        assertThat(unbounded.json().path("detail").asString()).startsWith("samples[0]");
    }

    private void publishV1(String name) throws Exception {
        assertThat(send(TestTokens.bearer("alice"), "/rule-sets", "application/yaml",
                renamed("/rules/card-fee.yaml", name)).status()).isEqualTo(201);
        assertThat(send(TestTokens.bearer("alice"), "/rule-sets/" + name + "/versions/1:request-publish", null, null)
                .status()).isEqualTo(200);
        assertThat(send(TestTokens.bearer("bob"), "/rule-sets/" + name + "/versions/1:approve", null, null).status())
                .isEqualTo(200);
    }

    private long decisionRows() {
        return jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single();
    }

    private static byte[] renamed(String resource, String name) {
        return new String(RuleLifecycleIT.resource(resource), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name).getBytes(StandardCharsets.UTF_8);
    }

    private Reply send(String bearer, String path, byte[] json) throws Exception {
        return send(bearer, path, "application/json", json);
    }

    private Reply send(String bearer, String path, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", bearer)
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
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
