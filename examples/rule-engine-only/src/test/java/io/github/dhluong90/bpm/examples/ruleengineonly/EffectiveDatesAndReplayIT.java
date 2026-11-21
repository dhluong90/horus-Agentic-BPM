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
 * RLE-008 and RLE-022 over HTTP (#22), as the demo runs them: a fee change scheduled for 1 December, the November
 * decision replayed after the change, the same answer pinned to version 1, and a same-day cut-off in Ho Chi Minh City
 * time. Each test uses its own tenant. Users: lan authors, minh approves, pricing evaluates with
 * {@code rules:simulate}, teller evaluates without it, thu reads the decision log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class EffectiveDatesAndReplayIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Map<String, List<String>> PERMISSIONS = Map.of(
            "lan", List.of("rules:read", "rules:author"),
            "minh", List.of("rules:read", "rules:approve"),
            "pricing", List.of("rules:evaluate", "rules:simulate", "rules:pin"),
            "analyst", List.of("rules:evaluate", "rules:simulate"),
            "teller", List.of("rules:evaluate"),
            "thu", List.of("rules:log:read"));

    /** The host clock, moved between steps: approvals in October and November, the replay in December. */
    static final class HostClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-20T00:00:00Z");

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

    static byte[] cardFee(String effectiveFrom, long fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: txCount, type: integer }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
                  - { id: R2, when: {}, then: { fee: "%d VND" } }
                """).formatted(effectiveFrom, fee).getBytes(StandardCharsets.UTF_8);
    }

    static final String STANDARD = "{\"inputs\":{\"tier\":\"STANDARD\",\"txCount\":1}";

    static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    void publish(String tenant, String ruleSet, byte[] document, int version, Instant approvedAt) throws Exception {
        String path = version == 1 ? "/rule-sets" : "/rule-sets/" + ruleSet + "/versions";
        assertThat(send(tenant, "lan", path, document, "application/yaml").status()).isEqualTo(201);
        Reply requested = send(tenant, "lan", "/rule-sets/" + ruleSet + "/versions/" + version + ":request-publish",
                null, null);
        assertThat(requested.status()).isEqualTo(200);
        clock.set(approvedAt);
        // An approval names the lock version it was decided on (If-Match).
        assertThat(send("POST", tenant, "minh", "/rule-sets/" + ruleSet + "/versions/" + version + ":approve", null, null,
                "\"" + requested.json().path("lockVersion").asLong() + "\"").status()).isEqualTo(200);
    }

    @Test
    void RLE_008_explicit_clock_resolves_effective_version_and_replays() throws Exception {
        // Given card-fee v1 from 1 Nov at 50,000 VND and v2 from 1 Dec at 30,000 VND
        String t = "bank-replay-a";
        clock.set(Instant.parse("2026-10-20T00:00:00Z"));
        publish(t, "card-fee", cardFee("2026-11-01T00:00:00Z", 50_000), 1, Instant.parse("2026-10-20T00:00:00Z"));
        publish(t, "card-fee", cardFee("2026-12-01T00:00:00Z", 30_000), 2, Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));

        // When pricing evaluates with clock 20 Nov, then 1 Dec
        Reply november = send(t, "pricing", "/decisions/card-fee:evaluate",
                body(STANDARD + ",\"clock\":\"2026-11-20T10:00:00Z\"}"), "application/json");
        Reply december = send(t, "pricing", "/decisions/card-fee:evaluate",
                body(STANDARD + ",\"clock\":\"2026-12-01T07:00:00+07:00\"}"), "application/json");

        // Then v1 and 50,000 VND, then v2 and 30,000 VND, each with the clock it reasoned about
        assertThat(november.status()).as(november.body()).isEqualTo(200);
        assertThat(november.json().path("version").asInt()).isEqualTo(1);
        assertThat(november.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":50000}");
        assertThat(november.json().path("evalClock").asString()).isEqualTo("2026-11-20T10:00:00Z");
        assertThat(november.json().path("evaluatedAt").asString()).isEqualTo("2026-11-26T09:00:00Z");
        assertThat(november.json().path("clockChosen").asBoolean()).as("back-dated decision is flagged").isTrue();
        assertThat(december.json().path("version").asInt()).isEqualTo(2);
        assertThat(december.json().path("outputs").path("fee").toString()).isEqualTo("{\"currency\":\"VND\",\"amount\":30000}");
        assertThat(december.json().path("evalClock").asString()).isEqualTo("2026-12-01T00:00:00Z");

        // When the auditor replays the November decision on 5 Dec, and pricing evaluates pinned to (v1, 20 Nov)
        clock.set(Instant.parse("2026-12-05T08:00:00Z"));
        long rows = decisions(t);
        String id = november.json().path("decisionId").asString();
        Reply replayed = send(t, "thu", "/decisions/" + id + ":replay", null, null);
        Reply pinned = send(t, "pricing", "/decisions/card-fee/versions/1:evaluate",
                body(STANDARD + ",\"clock\":\"2026-11-20T10:00:00Z\"}"), "application/json");

        // Then version, fee and matchedRuleIds are identical to the logged decision; the replay wrote nothing
        assertThat(replayed.status()).as(replayed.body()).isEqualTo(200);
        for (String field : List.of("decisionId", "version", "evalClock", "evaluatedAt", "clockChosen", "pinned", "outcome",
                "matchedRuleIds", "outputs")) {
            assertThat(replayed.json().path(field)).as(field).isEqualTo(november.json().path(field));
        }
        assertThat(pinned.status()).as(pinned.body()).isEqualTo(200);
        for (String field : List.of("version", "evalClock", "outcome", "matchedRuleIds", "outputs")) {
            assertThat(pinned.json().path(field)).as(field).isEqualTo(november.json().path(field));
        }
        assertThat(decisions(t)).isEqualTo(rows + 1);
        // and the pinned decision says so, in the response and on replay, while the selected one does not
        assertThat(pinned.json().path("pinned").asBoolean()).isTrue();
        assertThat(november.json().path("pinned").asBoolean()).isFalse();
        assertThat(send(t, "thu", "/decisions/" + pinned.json().path("decisionId").asString() + ":replay", null, null)
                .json().path("pinned").asBoolean()).isTrue();
        // and at the host clock alone, v2 is in force
        Reply hostClock = send(t, "teller", "/decisions/card-fee:evaluate", body(STANDARD + "}"), "application/json");
        assertThat(hostClock.json().path("version").asInt()).isEqualTo(2);
        assertThat(hostClock.json().path("clockChosen").asBoolean()).isFalse();
    }

    @Test
    void RLE_008_pinned_and_chosen_clock_decisions_are_marked_when_the_log_is_read() throws Exception {
        // Given card-fee v1 from 1 Nov, and on 26 Nov three decisions: at the host clock, back-dated to 20 Nov, and
        // pinned to version 1
        String t = "bank-replay-log";
        clock.set(Instant.parse("2026-10-20T00:00:00Z"));
        publish(t, "card-fee", cardFee("2026-11-01T00:00:00Z", 50_000), 1, Instant.parse("2026-10-20T00:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        String normal = send(t, "teller", "/decisions/card-fee:evaluate", body(STANDARD + "}"), "application/json")
                .json().path("decisionId").asString();
        String backDated = send(t, "pricing", "/decisions/card-fee:evaluate",
                body(STANDARD + ",\"clock\":\"2026-11-20T10:00:00Z\"}"), "application/json").json().path("decisionId")
                .asString();
        String pinned = send(t, "pricing", "/decisions/card-fee/versions/1:evaluate", body(STANDARD + "}"),
                "application/json").json().path("decisionId").asString();

        // When the auditor reads each one through GET /decisions/{id}
        Reply readNormal = send("GET", t, "thu", "/decisions/" + normal, null, null, null);
        Reply readBackDated = send("GET", t, "thu", "/decisions/" + backDated, null, null, null);
        Reply readPinned = send("GET", t, "thu", "/decisions/" + pinned, null, null, null);

        // Then each read says which kind of decision it was
        for (Reply read : List.of(readNormal, readBackDated, readPinned)) {
            assertThat(read.status()).as(read.body()).isEqualTo(200);
            assertThat(read.json().path("evaluatedAt").asString()).isEqualTo("2026-11-26T09:00:00Z");
        }
        assertThat(readNormal.json().path("evalClock").asString()).isEqualTo("2026-11-26T09:00:00Z");
        assertThat(readNormal.json().path("clockChosen").asBoolean()).isFalse();
        assertThat(readNormal.json().path("pinned").asBoolean()).isFalse();
        assertThat(readBackDated.json().path("evalClock").asString()).isEqualTo("2026-11-20T10:00:00Z");
        assertThat(readBackDated.json().path("clockChosen").asBoolean()).as("back-dated decision is flagged").isTrue();
        assertThat(readBackDated.json().path("pinned").asBoolean()).isFalse();
        assertThat(readPinned.json().path("pinned").asBoolean()).as("pinned decision is flagged").isTrue();
        assertThat(readPinned.json().path("clockChosen").asBoolean()).isFalse();
    }

    @Test
    void RLE_008_failure_paths_write_nothing() throws Exception {
        String t = "bank-replay-b";
        clock.set(Instant.parse("2026-10-20T00:00:00Z"));
        publish(t, "card-fee", cardFee("2026-11-01T00:00:00Z", 50_000), 1, Instant.parse("2026-10-20T00:00:00Z"));
        assertThat(send(t, "lan", "/rule-sets/card-fee/versions", cardFee("2027-01-01T00:00:00Z", 20_000),
                "application/yaml").status()).isEqualTo(201);
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        String id = send(t, "pricing", "/decisions/card-fee:evaluate", body(STANDARD + "}"), "application/json")
                .json().path("decisionId").asString();
        long rows = decisions(t);

        // a chosen clock without rules:simulate
        assertProblem(send(t, "teller", "/decisions/card-fee:evaluate",
                body(STANDARD + ",\"clock\":\"2026-11-20T10:00:00Z\"}"), "application/json"), 403, "CLOCK_NOT_ALLOWED");
        // a clock that is not an instant
        assertProblem(send(t, "pricing", "/decisions/card-fee:evaluate",
                body(STANDARD + ",\"clock\":\"20 Nov 2026\"}"), "application/json"), 400, "INPUT_INVALID");
        // a pin without rules:pin, even with rules:simulate
        assertProblem(send(t, "analyst", "/decisions/card-fee/versions/1:evaluate", body(STANDARD + "}"),
                "application/json"), 403, "PERMISSION_DENIED");
        // a pinned draft, and a version that does not exist
        assertProblem(send(t, "pricing", "/decisions/card-fee/versions/2:evaluate", body(STANDARD + "}"),
                "application/json"), 409, "INVALID_STATE");
        assertProblem(send(t, "pricing", "/decisions/card-fee/versions/9:evaluate", body(STANDARD + "}"),
                "application/json"), 404, "VERSION_NOT_FOUND");
        // replay: another tenant, an unknown id, a caller without rules:log:read
        assertProblem(send("bank-replay-c", "thu", "/decisions/" + id + ":replay", null, null), 404, "DECISION_NOT_FOUND");
        assertProblem(send(t, "thu", "/decisions/01KCS3W1DQ0M8V6T2B9R5N7K4E:replay", null, null), 404, "DECISION_NOT_FOUND");
        assertProblem(send(t, "teller", "/decisions/" + id + ":replay", null, null), 403, "PERMISSION_DENIED");

        assertThat(decisions(t)).isEqualTo(rows);
        assertThat(decisions("bank-replay-c")).isZero();
    }

    @Test
    void RLE_022_today_uses_rule_set_time_zone() throws Exception {
        // Given same-day-cutoff v1 in Asia/Ho_Chi_Minh and v2 without a time zone
        String t = "bank-cutoff";
        String cutoff = """
                schemaVersion: 1
                ruleSet: same-day-cutoff
                %s
                hitPolicy: first
                inputs:
                  - { name: txDate, type: date }
                outputs:
                  - { name: sameDay, type: boolean }
                rules:
                  - { id: R1, when: { txDate: "== today()" }, then: { sameDay: true } }
                """;
        clock.set(Instant.parse("2027-01-10T00:00:00Z"));
        byte[] request = body("{\"inputs\":{\"txDate\":\"2027-01-16\"},\"clock\":\"2027-01-15T18:30:00Z\"}");

        // When each is evaluated with txDate 2027-01-16 at 01:30 on 16 January in Ho Chi Minh City, while it is the
        // version in force (v2's approval retires v1, and a retired version is not pinned by default)
        publish(t, "same-day-cutoff", cutoff.formatted("timeZone: Asia/Ho_Chi_Minh").getBytes(StandardCharsets.UTF_8),
                1, Instant.parse("2027-01-10T00:00:00Z"));
        Reply v1 = send(t, "pricing", "/decisions/same-day-cutoff/versions/1:evaluate", request, "application/json");
        publish(t, "same-day-cutoff", cutoff.formatted("").getBytes(StandardCharsets.UTF_8),
                2, Instant.parse("2027-01-10T00:00:01Z"));
        Reply v2 = send(t, "pricing", "/decisions/same-day-cutoff/versions/2:evaluate", request, "application/json");

        // Then v1 matches R1 and v2 does not; replaying either gives the same
        assertThat(v1.json().path("matchedRuleIds").toString()).isEqualTo("[\"R1\"]");
        assertThat(v1.json().path("outputs").path("sameDay").asBoolean()).isTrue();
        assertThat(v2.json().path("outcome").asString()).isEqualTo("NO_MATCH");
        for (Reply logged : List.of(v1, v2)) {
            Reply replayed = send(t, "thu", "/decisions/" + logged.json().path("decisionId").asString() + ":replay", null, null);
            assertThat(replayed.json().path("outcome")).isEqualTo(logged.json().path("outcome"));
            assertThat(replayed.json().path("outputs")).isEqualTo(logged.json().path("outputs"));
        }
    }

    long decisions(String tenant) {
        return jdbc.sql("SELECT count(*) FROM rle_decision WHERE tenant_id = :t").param("t", tenant)
                .query(Long.class).single();
    }

    static void assertProblem(Reply reply, int status, String code) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(reply.json().path("code").asString()).isEqualTo(code);
    }

    Reply send(String tenant, String user, String path, byte[] body, String contentType) throws Exception {
        return send("POST", tenant, user, path, body, contentType, null);
    }

    Reply send(String method, String tenant, String user, String path, byte[] body, String contentType, String ifMatch)
            throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> send("GET", tenant, user, p, null, null, null).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/bpm/rules/v1" + path))
                .header("Authorization", TestTokens.bearerFor(user, tenant, PERMISSIONS.get(user)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
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
