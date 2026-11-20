package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The masking key at start-up and masked inputs over HTTP, on the example host (RLE-035, RLE-012, TR-SEC-10). The
 * host is started with command-line arguments, as a deployment passes its environment, on a fresh database per case.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class MaskedInputsHostIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String NATIONAL_ID = "079090001234";
    /** A non-uniform key (RFC 4231 style counting bytes), so byte-order bugs change the fingerprint. */
    static final byte[] K32 = counting(32);
    static final String K32_HEX = HexFormat.of().formatHex(K32);
    static final String K32_BASE64 = Base64.getEncoder().encodeToString(K32);
    static final String KEY_SETTING = "horus.bpm.rules.masking.hmac-key";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final String MINOR_REVIEW = """
            schemaVersion: 1
            ruleSet: minor-review
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
              - { name: age, type: integer }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { nationalId: "is not null", age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: { age: ">= 18" }, then: { route: "'AUTO'" } }
            """;

    @Test
    void RLE_035_masking_key_required_at_startup_and_never_leaked(CapturedOutput output) throws Exception {
        String db = freshDatabase();
        String auditor = TestTokens.bearerFor("audrey", "bank-a", List.of("rules:log:read", "rules:masked:search"));
        String otherTenantAuditor = TestTokens.bearerFor("oscar", "tenant-b",
                List.of("rules:log:read", "rules:masked:search"));
        String readOnly = TestTokens.bearerFor("rhea", "bank-a", List.of("rules:log:read"));
        List<String> bodies = new ArrayList<>();
        String decisionId;

        // Case C: a 32-byte key starts the host; the masked value is stored, read and found by its fingerprint.
        try (ConfigurableApplicationContext host = start(db, KEY_SETTING + "=" + K32_BASE64,
                "horus.bpm.rules.masking.search.max-per-minute=4")) {
            int port = port(host);
            bodies.add(send(port, TestTokens.bearer("alice"), "POST", "/rule-sets", MINOR_REVIEW, "application/yaml")
                    .body());
            bodies.add(send(port, TestTokens.bearer("alice"), "POST",
                    "/rule-sets/minor-review/versions/1:request-publish", null, null).body());
            bodies.add(send(port, TestTokens.bearer("bob"), "POST", "/rule-sets/minor-review/versions/1:approve", null,
                    null).body());
            Reply evaluated = send(port, TestTokens.bearer("teller"), "POST", "/decisions/minor-review:evaluate",
                    "{\"inputs\": {\"nationalId\": \"" + NATIONAL_ID + "\", \"age\": 16}}", "application/json");
            bodies.add(evaluated.body());
            assertThat(evaluated.status()).isEqualTo(200);
            decisionId = evaluated.json().path("decisionId").asString();
            send(port, TestTokens.bearer("teller"), "POST", "/decisions/minor-review:evaluate",
                    "{\"inputs\": {\"nationalId\": \"" + NATIONAL_ID + "\", \"age\": 40}}", "application/json");

            // bank-a shares the host key, so its token is made with its HKDF subkey, computed here independently.
            String fingerprint = "hmac:k1:" + hmacHex(tenantSubkey(K32, "bank-a"), NATIONAL_ID);
            JdbcClient jdbc = host.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE id = :id")
                    .param("id", decisionId).query(String.class).single()).isEqualTo(fingerprint);

            Reply read = send(port, auditor, "GET", "/decisions/" + decisionId, null, null);
            bodies.add(read.body());
            assertThat(read.status()).isEqualTo(200);
            assertThat(read.json().path("inputs").path("nationalId").asString()).isEqualTo(fingerprint);
            assertThat(read.json().path("inputs").path("age").asInt()).isEqualTo(16);
            assertThat(read.json().path("outputs").path("route").asString()).isEqualTo("MANUAL");

            Instant now = Instant.now();
            String search = "{\"ruleSet\": \"minor-review\", \"input\": \"nationalId\", \"value\": \"" + NATIONAL_ID
                    + "\", \"from\": \"" + now.minus(1, ChronoUnit.DAYS) + "\", \"to\": \""
                    + now.plus(1, ChronoUnit.DAYS) + "\"}";
            Reply found = send(port, auditor, "POST", "/decisions:find-by-masked-input", search, "application/json");
            bodies.add(found.body());
            assertThat(found.status()).isEqualTo(200);
            assertThat(found.json().path("data")).hasSize(2);
            assertThat(found.json().path("truncated").asBoolean()).isFalse();

            // Tenant isolation: another tenant's auditor neither reads nor finds bank-a's decision.
            Reply foreignRead = send(port, otherTenantAuditor, "GET", "/decisions/" + decisionId, null, null);
            bodies.add(foreignRead.body());
            assertThat(foreignRead.status()).isEqualTo(404);
            assertThat(foreignRead.json().path("code").asString()).isEqualTo("DECISION_NOT_FOUND");
            Reply foreignSearch = send(port, otherTenantAuditor, "POST", "/decisions:find-by-masked-input", search,
                    "application/json");
            bodies.add(foreignSearch.body());
            assertThat(foreignSearch.json().path("data")).isEmpty();

            Reply tellerRead = send(port, TestTokens.bearer("teller"), "GET", "/decisions/" + decisionId, null, null);
            assertThat(tellerRead.status()).isEqualTo(403);
            Reply tooLong = send(port, auditor, "POST", "/decisions:find-by-masked-input", search.replace(
                    now.minus(1, ChronoUnit.DAYS).toString(), now.minus(40, ChronoUnit.DAYS).toString()),
                    "application/json");
            bodies.add(tooLong.body());
            assertThat(tooLong.status()).isEqualTo(400);
            assertThat(tooLong.json().path("code").asString()).isEqualTo("QUERY_INVALID");

            // rules:log:read alone may read a decision but not search by a masked value.
            assertThat(send(port, readOnly, "GET", "/decisions/" + decisionId, null, null).status()).isEqualTo(200);
            Reply denied = send(port, readOnly, "POST", "/decisions:find-by-masked-input", search, "application/json");
            assertThat(denied.status()).isEqualTo(403);

            // Four searches per minute for this caller on this replica: the fifth is 429 with Retry-After.
            for (int i = 0; i < 3; i++) {
                assertThat(send(port, auditor, "POST", "/decisions:find-by-masked-input", search, "application/json")
                        .status()).isEqualTo(200);
            }
            HttpResponse<String> limited = HTTP.send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/bpm/rules/v1/decisions:find-by-masked-input"))
                    .header("Authorization", auditor).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(search)).build(), HttpResponse.BodyHandlers.ofString());
            bodies.add(limited.body());
            assertThat(limited.statusCode()).isEqualTo(429);
            assertThat(JSON.readTree(limited.body()).path("code").asString()).isEqualTo("RATE_LIMITED");
            assertThat(Long.parseLong(limited.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
            assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload ->> 'eventType' = "
                    + "'rules.decision-log.masked-input-searched.v1' AND payload ->> 'outcome' = 'RATE_LIMITED'")
                    .query(Long.class).single()).isEqualTo(1);
        }

        // Case A: the same database, no key: the host refuses to start and names the setting and the rule set.
        assertThatThrownBy(() -> start(db).close())
                .satisfies(e -> assertThat(rootMessage(e)).contains(KEY_SETTING).contains("minor-review"));

        // Case B: a key that decodes to 31 bytes: the same refusal, the key itself never printed.
        String k31 = Base64.getEncoder().encodeToString(filled(31, 'k'));
        assertThatThrownBy(() -> start(db, KEY_SETTING + "=" + k31).close())
                .satisfies(e -> assertThat(rootMessage(e)).contains(KEY_SETTING).contains("minor-review")
                        .doesNotContain(k31));

        // No log line and no response body carries the key or the plain value.
        assertThat(output.getAll()).doesNotContain(K32_BASE64, K32_HEX, NATIONAL_ID, k31);
        assertThat(bodies).allSatisfy(body -> assertThat(body).doesNotContain(K32_BASE64, K32_HEX, NATIONAL_ID));
    }

    @Test
    void RLE_012_rotation_keeps_earlier_decisions_findable_and_writes_under_the_current_key(CapturedOutput output)
            throws Exception {
        String db = freshDatabase();
        byte[] k2 = filled(32, '2');
        String k2Base64 = Base64.getEncoder().encodeToString(k2);
        String auditor = TestTokens.bearerFor("audrey", "bank-a", List.of("rules:log:read", "rules:masked:search"));
        Instant now = Instant.now();
        String search = "{\"ruleSet\": \"minor-review\", \"input\": \"nationalId\", \"value\": \"" + NATIONAL_ID
                + "\", \"from\": \"" + now.minus(1, ChronoUnit.DAYS) + "\", \"to\": \"" + now.plus(1, ChronoUnit.DAYS)
                + "\"}";
        String before;
        String after;

        // Key k1 in use: one decision about 079090001234.
        try (ConfigurableApplicationContext host = start(db, KEY_SETTING + "=" + K32_BASE64)) {
            int port = port(host);
            send(port, TestTokens.bearer("alice"), "POST", "/rule-sets", MINOR_REVIEW, "application/yaml");
            send(port, TestTokens.bearer("alice"), "POST", "/rule-sets/minor-review/versions/1:request-publish", null,
                    null);
            send(port, TestTokens.bearer("bob"), "POST", "/rule-sets/minor-review/versions/1:approve", null, null);
            before = send(port, TestTokens.bearer("teller"), "POST", "/decisions/minor-review:evaluate",
                    "{\"inputs\": {\"nationalId\": \"" + NATIONAL_ID + "\", \"age\": 16}}", "application/json")
                    .json().path("decisionId").asString();
        }

        // Rotated: current k2, previous k1. New decisions carry k2; the search finds the decisions under both keys.
        String[] rotated = {KEY_SETTING + "=" + k2Base64, KEY_SETTING + "-id=k2",
            "horus.bpm.rules.masking.previous-keys[0].hmac-key=" + K32_BASE64,
            "horus.bpm.rules.masking.previous-keys[0].hmac-key-id=k1"};
        try (ConfigurableApplicationContext host = start(db, rotated)) {
            int port = port(host);
            after = send(port, TestTokens.bearer("teller"), "POST", "/decisions/minor-review:evaluate",
                    "{\"inputs\": {\"nationalId\": \"" + NATIONAL_ID + "\", \"age\": 30}}", "application/json")
                    .json().path("decisionId").asString();
            JdbcClient jdbc = host.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE id = :id")
                    .param("id", before).query(String.class).single())
                    .isEqualTo("hmac:k1:" + hmacHex(tenantSubkey(K32, "bank-a"), NATIONAL_ID));
            assertThat(jdbc.sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE id = :id")
                    .param("id", after).query(String.class).single())
                    .isEqualTo("hmac:k2:" + hmacHex(tenantSubkey(k2, "bank-a"), NATIONAL_ID));
            Reply found = send(port, auditor, "POST", "/decisions:find-by-masked-input", search, "application/json");
            assertThat(found.status()).isEqualTo(200);
            List<String> ids = new ArrayList<>();
            found.json().path("data").forEach(item -> ids.add(item.path("decisionId").asString()));
            assertThat(ids).containsExactlyInAnyOrder(before, after);
            // Host keys are recorded once, under '*', each with its check value, never the key.
            assertThat(jdbc.sql("SELECT tenant_id || ' ' || key_id || ' ' || check_value FROM rle_masking_key_check"
                    + " ORDER BY key_id").query(String.class).list()).containsExactly(
                    "* k1 " + checkValue(K32), "* k2 " + checkValue(k2));
        }

        // Retired: k1 no longer listed, so it is no longer searched.
        try (ConfigurableApplicationContext host = start(db, KEY_SETTING + "=" + k2Base64, KEY_SETTING + "-id=k2")) {
            Reply found = send(port(host), auditor, "POST", "/decisions:find-by-masked-input", search,
                    "application/json");
            assertThat(found.json().path("data")).singleElement()
                    .satisfies(item -> assertThat(item.path("decisionId").asString()).isEqualTo(after));
        }

        // bank-a moves off the host key to a key of its own, a1: it writes under a1 and still finds its k2 rows.
        byte[] a1 = filled(32, 'a');
        String a1Base64 = Base64.getEncoder().encodeToString(a1);
        try (ConfigurableApplicationContext host = start(db, KEY_SETTING + "=" + k2Base64, KEY_SETTING + "-id=k2",
                "horus.bpm.rules.masking.tenants.bank-a.hmac-key=" + a1Base64,
                "horus.bpm.rules.masking.tenants.bank-a.hmac-key-id=a1")) {
            int port = port(host);
            String own = send(port, TestTokens.bearer("teller"), "POST", "/decisions/minor-review:evaluate",
                    "{\"inputs\": {\"nationalId\": \"" + NATIONAL_ID + "\", \"age\": 40}}", "application/json")
                    .json().path("decisionId").asString();
            assertThat(host.getBean(JdbcClient.class)
                    .sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE id = :id")
                    .param("id", own).query(String.class).single()).isEqualTo("hmac:a1:" + hmacHex(a1, NATIONAL_ID));
            Reply found = send(port, auditor, "POST", "/decisions:find-by-masked-input", search, "application/json");
            List<String> ids = new ArrayList<>();
            found.json().path("data").forEach(item -> ids.add(item.path("decisionId").asString()));
            assertThat(ids).containsExactlyInAnyOrder(after, own);
        }

        // TR-SEC-10: id k1 back with different key bytes stops start-up, naming the id, never the key.
        String other = Base64.getEncoder().encodeToString(filled(32, 'o'));
        assertThatThrownBy(() -> start(db, KEY_SETTING + "=" + other).close())
                .satisfies(e -> assertThat(rootMessage(e)).contains("host masking key id k1", "check value")
                        .doesNotContain(other, K32_BASE64));
        assertThat(output.getAll()).doesNotContain(K32_BASE64, K32_HEX, k2Base64, a1Base64, other, NATIONAL_ID);
    }

    @Test
    void RLE_035_a_host_without_masked_rule_sets_starts_without_a_key() {
        try (ConfigurableApplicationContext host = start(freshDatabase())) {
            assertThat(host.isActive()).isTrue();
        }
    }

    static String rootMessage(Throwable e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static ConfigurableApplicationContext start(String database, String... extraProperties) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                        + "/" + database,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        TestTokens.properties().forEach((name, value) -> args.add("--" + name + "=" + value));
        Arrays.stream(extraProperties).forEach(property -> args.add("--" + property));
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext host) {
        return Integer.parseInt(host.getEnvironment().getProperty("local.server.port"));
    }

    private static Reply send(int port, String bearer, String method, String path, String body, String contentType)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/bpm/rules/v1" + path))
                .header("Authorization", bearer)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (path.endsWith(":approve")) {
            // An approval names the lock version it was decided on (#234); a version just requested is at 1.
            request.header("If-Match", "\"1\"");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private static String freshDatabase() {
        String name = "masking_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    /** HKDF-SHA256 (RFC 5869), empty salt, info "horus-bpm/masking/v1/tenant/<tenant>", 32 bytes. */
    static byte[] tenantSubkey(byte[] hostKey, String tenant) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(extract.doFinal(hostKey), "HmacSHA256"));
        expand.update(("horus-bpm/masking/v1/tenant/" + tenant).getBytes(StandardCharsets.UTF_8));
        expand.update((byte) 1);
        return expand.doFinal();
    }

    /** The key check value: HKDF-SHA256 with info "horus-bpm/masking/v1/kcv", then the HMAC of nothing, 8 bytes. */
    static String checkValue(byte[] key) throws Exception {
        Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(extract.doFinal(key), "HmacSHA256"));
        expand.update("horus-bpm/masking/v1/kcv".getBytes(StandardCharsets.UTF_8));
        expand.update((byte) 1);
        Mac check = Mac.getInstance("HmacSHA256");
        check.init(new SecretKeySpec(expand.doFinal(), "HmacSHA256"));
        return HexFormat.of().formatHex(check.doFinal(new byte[0])).substring(0, 16);
    }

    static String hmacHex(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] counting(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i + 1);
        }
        return bytes;
    }

    static byte[] filled(int length, char c) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) c);
        return bytes;
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
