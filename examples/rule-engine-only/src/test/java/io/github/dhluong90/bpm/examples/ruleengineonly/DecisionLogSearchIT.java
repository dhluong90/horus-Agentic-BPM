package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.postgres.DecisionLogPartitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
 * The decision log over HTTP, as the demo runs it (RLE-029, RLE-017): an auditor lists one month of card-fee
 * decisions page by page, and the default-partition gauge goes from 1 to 0 when the partition job creates a month
 * without a restart. The host clock is fixed at 26 January 2027.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class DecisionLogSearchIT {

    static final Instant HOST_NOW = Instant.parse("2027-01-26T00:00:00Z");
    static final Instant JAN = Instant.parse("2027-01-01T00:00:00Z");
    static final String HASH = "hmac:" + "9f2c41d07a5be3c8".repeat(4);
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
    MeterRegistry meters;

    @Autowired
    DecisionLogPartitions partitions;

    static final List<String> cardFee = new ArrayList<>();
    static final List<String> tenantB = new ArrayList<>();

    @BeforeEach
    void logJanuaryOnce() {
        if (!cardFee.isEmpty()) {
            return;
        }
        // 250 card-fee and 40 kyc-risk decisions of bank-a in January 2027, 10 card-fee decisions of tenant-b.
        for (int i = 0; i < 250; i++) {
            cardFee.add(insert("bank-a", "card-fee", JAN.plus(Duration.ofMinutes(10L * i + 1)), i));
        }
        for (int i = 0; i < 40; i++) {
            insert("bank-a", "kyc-risk", JAN.plus(Duration.ofMinutes(10L * i + 2)), 1000 + i);
        }
        for (int i = 0; i < 10; i++) {
            tenantB.add(insert("tenant-b", "card-fee", JAN.plus(Duration.ofMinutes(10L * i + 3)), 2000 + i));
        }
    }

    @Test
    void RLE_029_decision_search_by_rule_set_and_period_is_bounded() throws Exception {
        // When dan, holding rules:log:read in bank-a, reads January and follows the cursor
        String query = "/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z";
        Reply first = get("dan", query);
        Reply second = get("dan", query + "&cursor=" + first.json().path("nextCursor").asString());

        // Then 200 newest first and a cursor, then the other 50 and no cursor
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().path("data")).hasSize(200);
        assertThat(first.json().path("hasNext").asBoolean()).isTrue();
        assertThat(second.json().path("data")).hasSize(50);
        assertThat(second.json().path("nextCursor").isNull()).isTrue();
        assertThat(second.json().path("hasNext").asBoolean()).isFalse();
        List<String> seen = new ArrayList<>();
        first.json().path("data").forEach(d -> seen.add(d.path("decisionId").asString()));
        second.json().path("data").forEach(d -> seen.add(d.path("decisionId").asString()));
        assertThat(seen).isEqualTo(cardFee.reversed());

        // And every row is a bank-a card-fee decision with its masked input still hashed and money in minor units
        JsonNode row = first.json().path("data").get(0);
        assertThat(row.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(row.path("outcome").asString()).isEqualTo("MATCHED");
        assertThat(row.path("inputs").path("nationalId").asString()).isEqualTo(HASH);
        assertThat(row.path("outputs").path("fee").path("currency").asString()).isEqualTo("VND");
        assertThat(row.path("outputs").path("fee").path("amount").asLong()).isEqualTo(10000);
        assertThat(row.has("tenantId")).isFalse();
        assertThat(first.body()).doesNotContain("kyc-risk").doesNotContain("012345678901");
    }

    @Test
    void RLE_029_failure_paths_over_http() throws Exception {
        // A range of 32 days and a missing rule set are 400 QUERY_INVALID naming the parameter
        Reply tooLong = get("dan", "/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-02T00:00:00Z");
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.json().path("code").asString()).isEqualTo("QUERY_INVALID");
        assertThat(tooLong.json().path("detail").asString()).contains("from and to");
        Reply noRuleSet = get("dan", "/decisions?from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z");
        assertThat(noRuleSet.status()).isEqualTo(400);
        assertThat(noRuleSet.json().path("detail").asString()).contains("ruleSet");
        Reply badInstant = get("dan", "/decisions?ruleSet=card-fee&from=yesterday&to=2027-02-01T00:00:00Z");
        assertThat(badInstant.json().path("code").asString()).isEqualTo("QUERY_INVALID");

        // A caller of tenant-b gets its own 10 decisions only
        Reply otherTenant = get("erin", "/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z");
        List<String> seen = new ArrayList<>();
        otherTenant.json().path("data").forEach(d -> seen.add(d.path("decisionId").asString()));
        assertThat(seen).isEqualTo(tenantB.reversed());

        // A caller without rules:log:read is refused
        Reply teller = get("teller", "/decisions?ruleSet=card-fee&from=2027-01-01T00:00:00Z&to=2027-02-01T00:00:00Z");
        assertThat(teller.status()).isEqualTo(403);
    }

    @Test
    void RLE_017_the_default_rows_gauge_goes_from_1_to_0_when_the_job_creates_the_month() {
        // Given start-up created January and February, and a decision of 15 March 2027 landed in the default
        String straggler = insert("bank-a", "card-fee", Instant.parse("2027-03-15T00:00:00Z"), 9000);
        assertThat(meters.get("horus.rle.partition.default.rows").gauge().value()).isEqualTo(1.0);

        // When the clock reaches 25 February 2027 and the job runs, without a restart
        List<String> created = partitions.ensureAhead(Instant.parse("2027-02-25T00:00:00Z"), Duration.ofDays(7));

        // Then March exists, the row moved into it and the gauge reads 0
        assertThat(created).containsExactly("rle_decision_2027_03");
        assertThat(jdbc.sql("SELECT tableoid::regclass::text FROM rle_decision WHERE id = :id").param("id", straggler)
                .query(String.class).single()).isEqualTo("rle_decision_2027_03");
        assertThat(meters.get("horus.rle.partition.default.rows").gauge().value()).isZero();
    }

    private String insert(String tenant, String ruleSet, Instant at, int n) {
        String id = String.format("01KD%022d", n);   // a ULID-shaped id; rows are ordered by time first
        jdbc.sql("""
                INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                  masked_inputs, outputs, matched_rule_ids, caller_id, eval_clock, duration_micros, log_mode)
                VALUES (:tenant, :at, :id, :ruleSet, 1, 'FIRST', 'OK', CAST(:inputs AS jsonb),
                  CAST('{"fee": {"currency": "VND", "amount": 10000}}' AS jsonb), CAST('["R2"]' AS jsonb), 'teller',
                  :at, 31, 'TRANSACTIONAL')
                """)
                .param("tenant", tenant).param("at", java.sql.Timestamp.from(at)).param("id", id)
                .param("ruleSet", ruleSet)
                .param("inputs", "{\"tier\": \"SILVER\", \"txCount\": " + (n % 9) + ", \"nationalId\": \"" + HASH + "\"}")
                .update();
        return id;
    }

    private Reply get(String user, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", TestTokens.bearer(user)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
