package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Rule Engine's meters in a host (DESIGN section 4.8, TR-OBS-02) and the envelope of the approval event in the
 * outbox (TR-OBS-04, TR-OBS-06): a teller's evaluations are counted and timed by outcome, a request for another
 * tenant's rule set is counted as refused and writes nothing, and no meter carries a tenant, rule set, caller or input.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EvaluationMetricsIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

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
    MeterRegistry meters;

    @Autowired
    JdbcClient jdbc;

    @Test
    void TR_OBS_02_evaluations_are_counted_and_timed_by_outcome_with_no_tenant_or_rule_set_tag() throws Exception {
        // Given card-fee imported by alice and approved by bob at bank-a
        send("alice", "POST", "/rule-sets", resource("/rules/card-fee.yaml"), "application/yaml");
        send("alice", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null);
        Reply approved = send("bob", "POST", "/rule-sets/card-fee/versions/1:approve", null, null);
        assertThat(approved.status()).isEqualTo(200);
        double matchedBefore = count("matched");
        double refusedBefore = count("refused");
        int decisionsBefore = decisions();

        // When the teller prices a Gold card and a Silver card with 5 transactions, and carol of tenant-b asks for
        // bank-a's card-fee
        Reply gold = evaluate("teller", "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":0}}");
        Reply silver = evaluate("teller", "{\"inputs\":{\"tier\":\"SILVER\",\"txCount\":5}}");
        Reply otherTenant = evaluate("carol", "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":0}}");

        // Then 0 VND and 10,000 VND, two matched evaluations timed, and one refusal that wrote no decision
        assertThat(gold.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":0}");
        assertThat(silver.json().path("outputs").path("fee").toString())
                .isEqualTo("{\"currency\":\"VND\",\"amount\":10000}");
        assertThat(otherTenant.status()).isEqualTo(404);
        assertThat(otherTenant.json().path("code").asString()).isEqualTo("RULE_SET_NOT_FOUND");
        assertThat(decisions()).isEqualTo(decisionsBefore + 2);
        assertThat(count("matched")).isEqualTo(matchedBefore + 2);
        assertThat(count("refused")).isEqualTo(refusedBefore + 1);
        assertThat(meters.get("horus.rle.evaluations").tag("outcome", "matched").timer().totalTime(TimeUnit.NANOSECONDS))
                .isPositive();
        assertThat(meters.get("horus.rle.evaluation.errors").tag("code", "RULE_SET_NOT_FOUND").counter().count())
                .isGreaterThanOrEqualTo(1.0);

        // And every Rule Engine meter has only the tag keys DESIGN 4.8 lists, none with a tenant, rule set or user
        Set<String> keys = new TreeSet<>();
        for (Meter meter : meters.getMeters()) {
            if (!meter.getId().getName().startsWith("horus.rle.")) {
                continue;
            }
            for (Tag tag : meter.getId().getTags()) {
                keys.add(tag.getKey());
                assertThat(tag.getValue()).doesNotContain("bank-a", "tenant-b", "card-fee", "teller", "carol");
            }
        }
        assertThat(keys).isSubsetOf("outcome", "code", "cause", "reason");
    }

    @Test
    void TR_OBS_06_the_approval_event_in_the_outbox_carries_the_standard_envelope() throws Exception {
        // Given a copy of card-fee, card-fee-metrics, imported by alice and approved by bob
        byte[] yaml = new String(resource("/rules/card-fee.yaml"), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: card-fee-metrics").getBytes(StandardCharsets.UTF_8);
        send("alice", "POST", "/rule-sets", yaml, "application/yaml");
        send("alice", "POST", "/rule-sets/card-fee-metrics/versions/1:request-publish", null, null);
        assertThat(send("bob", "POST", "/rule-sets/card-fee-metrics/versions/1:approve", null, null).status())
                .isEqualTo(200);

        // When the outbox is read for the published event
        String payload = jdbc.sql("SELECT payload::text FROM rle_decision_outbox WHERE tenant_id = 'bank-a'"
                        + " AND payload->>'eventType' = 'rules.version.published.v1'"
                        + " AND payload->>'ruleSet' = 'card-fee-metrics'")
                .query(String.class).single();
        JsonNode event = JSON.readTree(payload);

        // Then it carries every envelope member
        assertThat(event.path("eventId").asString()).hasSize(26);
        assertThat(event.path("eventType").asString()).isEqualTo("rules.version.published.v1");
        assertThat(event.path("occurredAt").asString()).endsWith("Z");
        assertThat(event.path("tenantId").asString()).isEqualTo("bank-a");
        assertThat(event.path("correlationId").asString()).isNotBlank();
        assertThat(event.has("causationId")).isTrue();
        assertThat(event.path("producer").asString()).startsWith("rule-engine/");
        assertThat(event.path("schemaVersion").asInt()).isEqualTo(1);
    }

    double count(String outcome) {
        return meters.get("horus.rle.evaluations").tag("outcome", outcome).timer().count();
    }

    int decisions() {
        return jdbc.sql("SELECT count(*) FROM rle_decision").query(Integer.class).single();
    }

    Reply evaluate(String user, String body) throws Exception {
        return send(user, "POST", "/decisions/card-fee:evaluate", body.getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    Reply send(String user, String method, String path, byte[] body, String contentType) throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> send(user, "GET", p, null, null).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", TestTokens.bearer(user))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        // An approval names the lock version it was decided on (#234): a version just requested is at lock version 1.
        if (path.endsWith(":approve")) {
            request.header("If-Match", "\"1\"");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1"
                + path);
    }

    static byte[] resource(String path) {
        try (InputStream in = EvaluationMetricsIT.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
