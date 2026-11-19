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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Failure paths of the walking skeleton over HTTP: refused imports leave nothing behind and answer
 * Problem Details with a stable code; refused inputs are not logged; money crosses the API in minor
 * units only; a refused self-approval changes nothing; the tenant never comes from the request.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {})
@Testcontainers
class RuleLifecycleFailurePathsIT {

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

    static final String BALANCE_CHECK = """
            schemaVersion: 1
            ruleSet: %s
            hitPolicy: first
            inputs:
              - { name: balance, type: money }
            outputs:
              - { name: band, type: string }
            rules:
              - id: R1
                when: { balance: ">= 50000000 VND" }
                then: { band: "'PRIORITY'" }
              - id: R2
                when: {}
                then: { band: "'MASS'" }
            """;

    // ---------------------------------------------------------------- RLE-020 refused imports

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "malformed: [unclosed\n",
        "schemaVersion: 1\nruleSet: bad-indent\n  hitPolicy: first\n",
        "schemaVersion: 1\nruleSet: unknown-root\nhitPolicy: first\ntenantId: tenant-b\ninputs: []\n"
                + "outputs:\n  - { name: fee, type: money }\nrules:\n  - { id: R1, when: {}, then: { fee: \"0 VND\" } }\n",
        "schemaVersion: 1\nruleSet: empty-table\nhitPolicy: first\ninputs: []\n"
                + "outputs:\n  - { name: fee, type: money }\nrules: []\n",
        "schemaVersion: 1\nruleSet: bad-type\nhitPolicy: first\ninputs:\n  - { name: tier, type: text }\n"
                + "outputs:\n  - { name: fee, type: money }\nrules:\n  - { id: R1, when: {}, then: { fee: \"0 VND\" } }\n",
        "schemaVersion: 1\nruleSet: bad-cell\nhitPolicy: first\ninputs:\n  - { name: tier, type: string }\n"
                + "outputs:\n  - { name: fee, type: money }\nrules:\n  - { id: R1, when: { tier: \"== 'GOLD\" }, then: { fee: \"0 VND\" } }\n",
        "schemaVersion: 1\nruleSet: half-dong\nhitPolicy: first\ninputs: []\n"
                + "outputs:\n  - { name: fee, type: money }\nrules:\n  - { id: R1, when: {}, then: { fee: \"0.5 VND\" } }\n"
    })
    void RLE_020_a_refused_import_answers_422_validation_failed_and_creates_nothing(String yaml) throws Exception {
        // Given
        long before = rows("rle_version") + rows("rle_decision_outbox") + rows("rle_idempotency");

        // When
        Reply reply = send("alice", "POST", "/rule-sets", yaml.getBytes(StandardCharsets.UTF_8), "application/yaml", "k-" + yaml.hashCode());

        // Then
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(reply.json().path("detail").asString()).isNotBlank();
        assertThat(rows("rle_version") + rows("rle_decision_outbox") + rows("rle_idempotency")).isEqualTo(before);
    }

    /** Schema: input, output and rule have additionalProperties false; a typo of mask must not import. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"maks: true", "mask: \"yes\""})
    void RLE_020_a_misspelt_or_mistyped_mask_is_refused_with_422(String maskEntry) throws Exception {
        String yaml = "schemaVersion: 1\nruleSet: kyc-" + Math.abs(maskEntry.hashCode()) + "\nhitPolicy: first\n"
                + "inputs:\n  - { name: nationalId, type: string, " + maskEntry + " }\n"
                + "outputs:\n  - { name: ok, type: boolean }\nrules:\n  - { id: R1, when: {}, then: { ok: true } }\n";

        Reply reply = send("alice", "POST", "/rule-sets", yaml.getBytes(StandardCharsets.UTF_8), "application/yaml", null);

        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.json().path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void RLE_020_only_yaml_is_imported_in_this_release() throws Exception {
        byte[] json = "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8);

        Reply asJson = send("alice", "POST", "/rule-sets", json, "application/json", null);
        Reply asText = send("alice", "POST", "/rule-sets", json, "text/plain", null);

        assertThat(asJson.status()).isEqualTo(400);
        assertThat(asJson.json().path("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(asText.status()).isIn(400, 415);
    }

    // ---------------------------------------------------------------- RLE-001 inputs and money

    @Test
    void RLE_001_refused_inputs_answer_400_and_write_no_decision_row() throws Exception {
        // Given
        publish("card-fee-inputs", cardFee("card-fee-inputs"));
        long before = decisions("card-fee-inputs");

        // When
        Map<String, Reply> replies = Map.of(
                "string for integer", evaluate("card-fee-inputs", "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":\"5\"}}"),
                "fraction for integer", evaluate("card-fee-inputs", "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":2.5}}"),
                "number for string", evaluate("card-fee-inputs", "{\"inputs\":{\"tier\":7,\"txCount\":5}}"),
                "inputs not an object", evaluate("card-fee-inputs", "{\"inputs\":[1,2]}"),
                "not json", evaluate("card-fee-inputs", "{\"inputs\":"),
                "not an object", evaluate("card-fee-inputs", "[]"));

        // Then
        replies.forEach((name, reply) -> {
            assertThat(reply.status()).as(name).isEqualTo(400);
            assertThat(reply.json().path("code").asString()).as(name).isEqualTo("INPUT_INVALID");
        });
        assertThat(decisions("card-fee-inputs")).isEqualTo(before);
    }

    @Test
    void RLE_001_money_input_threshold_is_exact_to_the_minor_unit() throws Exception {
        // Given
        publish("balance-band", BALANCE_CHECK.formatted("balance-band").getBytes(StandardCharsets.UTF_8));

        // When / Then: the threshold itself, one dong below, and the largest JSON-safe amount
        assertThat(band("{\"currency\":\"VND\",\"amount\":50000000}")).isEqualTo("PRIORITY");
        assertThat(band("{\"currency\":\"VND\",\"amount\":49999999}")).isEqualTo("MASS");
        assertThat(band("{\"currency\":\"VND\",\"amount\":9007199254740991}")).isEqualTo("PRIORITY");
        assertThat(band("{\"currency\":\"VND\",\"amount\":-9007199254740991}")).isEqualTo("MASS");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "{\"currency\":\"VND\",\"amount\":50000000.0}",
        "{\"currency\":\"VND\",\"amount\":1e8}",
        "{\"currency\":\"VND\",\"amount\":\"50000000\"}",
        "{\"currency\":\"VND\",\"amount\":9007199254740992}",
        "{\"currency\":\"XYZ\",\"amount\":1}",
        "{\"currency\":\"VND\"}",
        "{\"currency\":\"VND\",\"amount\":1,\"scale\":0}",
        "50000000"
    })
    void RLE_001_money_input_that_is_not_an_exact_minor_unit_amount_is_refused(String balance) throws Exception {
        publishOnce("balance-refused");
        long before = decisions("balance-refused");

        Reply reply = evaluate("balance-refused", "{\"inputs\":{\"balance\":" + balance + "}}");

        assertThat(reply.status()).isEqualTo(400);
        assertThat(reply.json().path("code").asString()).isEqualTo("INPUT_INVALID");
        assertThat(decisions("balance-refused")).isEqualTo(before);
    }

    @Test
    void RLE_005_money_currency_mismatch_is_rejected() throws Exception {
        // Given balance >= 50000000 VND
        publish("balance-ccy", BALANCE_CHECK.formatted("balance-ccy").getBytes(StandardCharsets.UTF_8));

        // When the balance is 3000.00 USD
        Reply reply = evaluate("balance-ccy", "{\"inputs\":{\"balance\":{\"currency\":\"USD\",\"amount\":300000}}}");

        // Then 422 EVALUATION_ERROR, no outputs, and the failure is logged
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo("EVALUATION_ERROR");
        assertThat(reply.json().has("outputs")).isFalse();
        String decisionId = reply.json().path("decisionId").asString();
        assertThat(jdbc.sql("SELECT outcome || ' ' || coalesce(outputs::text, 'no-outputs') || ' ' || matched_rule_ids::text "
                + "FROM rle_decision WHERE tenant_id = 'bank-a' AND id = :id").param("id", decisionId).query(String.class).single())
                .isEqualTo("EVALUATION_ERROR no-outputs []");
    }

    // ---------------------------------------------------------------- RLE-009, RLE-011, RLE-014

    @Test
    void RLE_009_a_refused_self_approval_over_rest_keeps_the_lock_version_and_writes_no_event() throws Exception {
        // Given
        send("alice", "POST", "/rule-sets", cardFee("card-fee-self"), "application/yaml", null);
        Reply pending = send("alice", "POST", "/rule-sets/card-fee-self/versions/1:request-publish", null, null, null);
        long events = events("card-fee-self");

        // When alice approves with the current ETag and an idempotency key
        Reply refused = send("alice", "POST", "/rule-sets/card-fee-self/versions/1:approve", null, null, "ap-self",
                Map.of("If-Match", pending.etag()));

        // Then
        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.json().path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        Reply after = send("alice", "GET", "/rule-sets/card-fee-self/versions/1", null, null, null);
        assertThat(after.json().path("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(after.etag()).isEqualTo(pending.etag());
        assertThat(after.json().path("approvedBy").isNull()).isTrue();
        assertThat(events("card-fee-self")).isEqualTo(events);
        assertThat(evaluate("card-fee-self", "{\"inputs\":{\"tier\":\"GOLD\"}}").json().path("code").asString())
                .isEqualTo("NO_EFFECTIVE_VERSION");
    }

    @Test
    void RLE_011_a_tenant_header_or_body_field_never_switches_the_tenant() throws Exception {
        // Given a rule set only in tenant-b
        publishAs("carol", "dave", "only-in-b", cardFee("only-in-b"));

        // When a bank-a caller names tenant-b in the body
        Reply read = send("alice", "GET", "/rule-sets/only-in-b/versions/1", null, null, null, Map.of());
        Reply eval = send("teller", "POST", "/decisions/only-in-b:evaluate",
                "{\"tenantId\":\"tenant-b\",\"tenant\":\"tenant-b\",\"inputs\":{\"tier\":\"GOLD\"}}".getBytes(StandardCharsets.UTF_8),
                "application/json", null, Map.of());
        Reply approve = send("bob", "POST", "/rule-sets/only-in-b/versions/1:approve",
                "{\"tenantId\":\"tenant-b\"}".getBytes(StandardCharsets.UTF_8), "application/json", null, Map.of());

        // Then every call is answered in bank-a, where the rule set does not exist
        assertThat(read.status()).isEqualTo(404);
        assertThat(eval.status()).isEqualTo(404);
        assertThat(eval.json().path("code").asString()).isEqualTo("RULE_SET_NOT_FOUND");
        assertThat(approve.status()).isEqualTo(404);

        // And a tenant-b switch header is refused on read, evaluate and approve while switching is off
        // (RLE-018, RLE-037), as /api/bpm/me does, and no decision is logged in either tenant
        String tenantBDecisions = "SELECT count(*) FROM rle_decision WHERE rule_set_name = 'only-in-b' AND tenant_id = 'tenant-b'";
        long tenantBBefore = jdbc.sql(tenantBDecisions).query(Long.class).single();
        Map<String, String> switchHeader = Map.of("X-Tenant-Id", "tenant-b");
        List<Reply> withHeader = List.of(
                send("alice", "GET", "/rule-sets/only-in-b/versions/1", null, null, null, switchHeader),
                send("teller", "POST", "/decisions/only-in-b:evaluate",
                        "{\"inputs\":{\"tier\":\"GOLD\"}}".getBytes(StandardCharsets.UTF_8), "application/json", null,
                        switchHeader),
                send("bob", "POST", "/rule-sets/only-in-b/versions/1:approve", null, null, null, switchHeader));
        for (Reply refused : withHeader) {
            assertThat(refused.status()).isEqualTo(403);
            assertThat(refused.json().path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
        }
        assertThat(jdbc.sql(tenantBDecisions).query(Long.class).single()).isEqualTo(tenantBBefore);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE rule_set_name = 'only-in-b' AND tenant_id = 'bank-a'")
                .query(Long.class).single()).isZero();
    }

    @Test
    void RLE_014_an_evaluation_replayed_over_rest_returns_the_same_decision_and_logs_one_row() throws Exception {
        // Given
        publish("card-fee-replay", cardFee("card-fee-replay"));
        String body = "{\"inputs\":{\"tier\":\"SILVER\",\"txCount\":3}}";

        // When
        Reply first = send("teller", "POST", "/decisions/card-fee-replay:evaluate", body.getBytes(StandardCharsets.UTF_8),
                "application/json", "ev-1");
        Reply replay = send("teller", "POST", "/decisions/card-fee-replay:evaluate", body.getBytes(StandardCharsets.UTF_8),
                "application/json", "ev-1");
        Reply mismatch = send("teller", "POST", "/decisions/card-fee-replay:evaluate",
                "{\"inputs\":{\"tier\":\"GOLD\",\"txCount\":3}}".getBytes(StandardCharsets.UTF_8), "application/json", "ev-1");

        // Then
        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(first.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":10000}");
        assertThat(mismatch.status()).isEqualTo(422);
        assertThat(mismatch.json().path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSE_MISMATCH");
        assertThat(decisions("card-fee-replay")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    String band(String balance) throws Exception {
        Reply reply = evaluate("balance-band", "{\"inputs\":{\"balance\":" + balance + "}}");
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        return reply.json().path("outputs").path("band").asString();
    }

    void publishOnce(String name) throws Exception {
        if (jdbc.sql("SELECT count(*) FROM rle_rule_set WHERE tenant_id = 'bank-a' AND name = :n").param("n", name)
                .query(Long.class).single() == 0) {
            publish(name, BALANCE_CHECK.formatted(name).getBytes(StandardCharsets.UTF_8));
        }
    }

    void publish(String name, byte[] yaml) throws Exception {
        publishAs("alice", "bob", name, yaml);
    }

    void publishAs(String maker, String checker, String name, byte[] yaml) throws Exception {
        assertThat(send(maker, "POST", "/rule-sets", yaml, "application/yaml", null).status()).isEqualTo(201);
        assertThat(send(maker, "POST", "/rule-sets/" + name + "/versions/1:request-publish", null, null, null).status()).isEqualTo(200);
        assertThat(send(checker, "POST", "/rule-sets/" + name + "/versions/1:approve", null, null, null).status()).isEqualTo(200);
    }

    Reply evaluate(String ruleSet, String body) throws Exception {
        return send("teller", "POST", "/decisions/" + ruleSet + ":evaluate", body.getBytes(StandardCharsets.UTF_8),
                "application/json", null);
    }

    long rows(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    long decisions(String ruleSet) {
        return jdbc.sql("SELECT count(*) FROM rle_decision WHERE rule_set_name = :n").param("n", ruleSet).query(Long.class).single();
    }

    long events(String ruleSet) {
        return jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'ruleSet' = :n").param("n", ruleSet)
                .query(Long.class).single();
    }

    static byte[] cardFee(String name) {
        try (InputStream in = RuleLifecycleFailurePathsIT.class.getResourceAsStream("/rules/card-fee.yaml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("ruleSet: card-fee", "ruleSet: " + name)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    Reply send(String user, String method, String path, byte[] body, String contentType, String idempotencyKey)
            throws Exception {
        return send(user, method, path, body, contentType, idempotencyKey, Map.of());
    }

    Reply send(String user, String method, String path, byte[] body, String contentType, String idempotencyKey,
            Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", TestTokens.bearer(user))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        headers.forEach(request::header);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("ETag").orElse(null), response.body());
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path);
    }

    record Reply(int status, String contentType, String etag, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
