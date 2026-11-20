package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The guards against unmasked personal data over HTTP on the example host (PD-099): the warning the approver sees
 * (RLE-049), strict mode for one tenant (RLE-050), masking in the diff (RLE-051), and the masking default logged at
 * start-up and shown in {@code /api/bpm/meta} (RLE-052). bank-a runs without strict mode, bank-b with it.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class UnmaskedPersonalDataHostIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String KEY = Base64.getEncoder().encodeToString(seeded(32));
    static final String STRICT_BANK_B = "horus.bpm.rules.masking.tenants.bank-b.require-explicit-mask=true";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final List<String> AUTHOR = List.of("rules:read", "rules:author");
    static final List<String> APPROVER = List.of("rules:read", "rules:approve");

    /** A KYC routing table whose nationalId declares no mask. */
    static final String KYC_ROUTING = """
            schemaVersion: 1
            ruleSet: kyc-routing
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string }
              - { name: age, type: integer, mask: false }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: {}, then: { route: "'AUTO'" } }
            """;

    @Test
    void RLE_049_RLE_050_RLE_051_RLE_052_guards_over_http_and_the_masking_default_across_a_restart(
            CapturedOutput output) throws Exception {
        String db = freshDatabase();
        String alice = TestTokens.bearerFor("alice", "bank-a", AUTHOR);
        String bob = TestTokens.bearerFor("bob", "bank-a", APPROVER);
        String binh = TestTokens.bearerFor("binh", "bank-b", AUTHOR);

        try (ConfigurableApplicationContext host = start(db, STRICT_BANK_B)) {
            int port = port(host);

            // RLE-049: alice's import answers 201 with a warning naming nationalId and "national id"
            Reply imported = send(port, alice, "POST", "/api/bpm/rules/v1/rule-sets", KYC_ROUTING);
            assertThat(imported.status()).isEqualTo(201);
            JsonNode warning = imported.json().path("warnings").get(0);
            assertThat(warning.path("code").asString()).isEqualTo("UNMASKED_PERSONAL_DATA");
            assertThat(warning.path("input").asString()).isEqualTo("nationalId");
            assertThat(warning.path("matchedName").asString()).isEqualTo("national id");

            // The publication request carries it, and bob sees it when he opens the change to approve it
            Reply requested = send(port, alice, "POST",
                    "/api/bpm/rules/v1/rule-sets/kyc-routing/versions/1:request-publish", null);
            assertThat(requested.json().path("warnings")).hasSize(1);
            Reply pending = send(port, bob, "GET", "/api/bpm/rules/v1/rule-sets/kyc-routing/versions/1", null);
            assertThat(pending.json().path("status").asString()).isEqualTo("PENDING_APPROVAL");
            assertThat(pending.json().path("warnings").get(0).path("input").asString()).isEqualTo("nationalId");

            // RLE-050: bank-b runs in strict mode, so the same import is 422 naming nationalId, and nothing is stored
            Reply refused = send(port, binh, "POST", "/api/bpm/rules/v1/rule-sets", KYC_ROUTING);
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.json().path("code").asString()).isEqualTo("VALIDATION_FAILED");
            assertThat(refused.json().path("violations").get(0).path("column").asString()).isEqualTo("nationalId");
            assertThat(send(port, binh, "GET", "/api/bpm/rules/v1/rule-sets/kyc-routing", null).status())
                    .isEqualTo(404);
            // With every input declared, bank-b's import succeeds
            String declared = KYC_ROUTING.replace("{ name: nationalId, type: string }",
                    "{ name: nationalId, type: string, mask: true }");
            assertThat(send(port, binh, "POST", "/api/bpm/rules/v1/rule-sets", declared).status()).isEqualTo(201);

            // RLE-051: bank-b's v2 drops the mask; the diff marks nationalId MASKED to NOT_MASKED
            String unmasked = KYC_ROUTING.replace("{ name: nationalId, type: string }",
                    "{ name: nationalId, type: string, mask: false }");
            assertThat(send(port, binh, "POST", "/api/bpm/rules/v1/rule-sets/kyc-routing/versions", unmasked)
                    .status()).isEqualTo(201);
            JsonNode masking = send(port, binh, "GET",
                    "/api/bpm/rules/v1/rule-sets/kyc-routing/versions/2/diff?base=1", null).json().path("masking");
            assertThat(masking.get(0).path("input").asString()).isEqualTo("nationalId");
            assertThat(masking.get(0).path("from").asString()).isEqualTo("MASKED");
            assertThat(masking.get(0).path("to").asString()).isEqualTo("NOT_MASKED");
            assertThat(masking.get(0).path("changed").asBoolean()).isTrue();
            assertThat(masking.get(1).path("changed").asBoolean()).isFalse();

            // RLE-052: /meta shows each tenant the masking default and strict mode in force for it
            JsonNode metaA = rulesSettings(send(port, alice, "GET", "/api/bpm/meta", null));
            JsonNode metaB = rulesSettings(send(port, binh, "GET", "/api/bpm/meta", null));
            assertThat(metaA.path("decision-log.mask-default").isBoolean()).isTrue();
            assertThat(metaA.path("decision-log.mask-default").asBoolean(true)).isFalse();
            assertThat(metaA.path("decision-log.require-explicit-mask").asBoolean(true)).isFalse();
            assertThat(metaB.path("decision-log.require-explicit-mask").asBoolean(false)).isTrue();
        }
        int before = output.getAll().length();

        // When the host restarts with bank-a masking undeclared inputs by default (and a key, which that now needs)
        try (ConfigurableApplicationContext host = start(db, STRICT_BANK_B,
                "horus.bpm.rules.masking.tenants.bank-a.mask-default=true",
                "horus.bpm.rules.masking.hmac-key=" + KEY)) {
            // Then one start-up line names bank-a with the old and new value; bank-b, unchanged, is not logged
            String restart = output.getAll().substring(before);
            String line = "decision-log.mask-default for tenant bank-a changed from false to true since the last"
                    + " start-up";
            assertThat(restart.split(java.util.regex.Pattern.quote(line), -1)).hasSize(2);
            assertThat(restart).doesNotContain("tenant bank-b changed").doesNotContain(KEY);
            // And /meta shows the value now in force
            JsonNode metaA = rulesSettings(send(port(host), alice, "GET", "/api/bpm/meta", null));
            assertThat(metaA.path("decision-log.mask-default").asBoolean(false)).isTrue();
        }
    }

    static JsonNode rulesSettings(Reply meta) {
        assertThat(meta.status()).isEqualTo(200);
        for (JsonNode module : meta.json().path("modules")) {
            if (module.path("module").asString().equals("rules")) {
                return module.path("settings");
            }
        }
        throw new AssertionError("no rules module in " + meta.body());
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

    private static Reply send(int port, String bearer, String method, String path, String yaml) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", bearer)
                .method(method, yaml == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(yaml, StandardCharsets.UTF_8));
        if (yaml != null) {
            request.header("Content-Type", "application/yaml");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private static String freshDatabase() {
        String name = "personal_data_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    static byte[] seeded(int length) {
        byte[] bytes = new byte[length];
        new java.util.Random(283L).nextBytes(bytes);
        return bytes;
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
