package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issue #321: the former {@code system:dev-auto-publish} four-eyes exemption is reachable in no profile. Migration V12
 * removed it from the database check, no setting turns auto-publication on, and the {@code system:} namespace is
 * refused for every caller. The host starts once without a profile and once on the {@code dev} profile, each on its
 * own database, and the same three paths are tried: the reserved id as a caller, a self-approval, and a row approved
 * by its own requester under that id.
 */
@Testcontainers
class DevAutoPublishExemptionIT {

    static final String FORMER_EXEMPT_ID = "system:dev-auto-publish";
    static final List<String> ALL_RULE_PERMISSIONS = List.of("rules:read", "rules:author", "rules:approve");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final byte[] CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "30000 VND" } }
            """.getBytes(StandardCharsets.UTF_8);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @ParameterizedTest(name = "profile ''{0}''")
    @ValueSource(strings = {"", "dev"})
    void TR_SEC_04_dev_auto_publish_exemption_is_unreachable_in_every_profile(String profile) throws Exception {
        try (ConfigurableApplicationContext host = start(freshDatabase(), profile)) {
            String port = host.getEnvironment().getProperty("local.server.port");
            assertThat(host.getEnvironment().matchesProfiles("dev")).isEqualTo(profile.equals("dev"));

            // The former exempt id, holding every rule permission, may neither import nor approve: 403
            Reply imported = send(port, FORMER_EXEMPT_ID, "POST", "/rule-sets", CARD_FEE, "application/yaml", null);
            assertThat(imported.status()).as(imported.body()).isEqualTo(403);
            assertThat(imported.json().path("code").asString()).isEqualTo("PERMISSION_DENIED");

            // An author who also approves cannot publish their own version: 403 SELF_APPROVAL_FORBIDDEN
            assertThat(send(port, "alice", "POST", "/rule-sets", CARD_FEE, "application/yaml", null).status())
                    .isEqualTo(201);
            Reply requested = send(port, "alice", "POST", "/rule-sets/card-fee/versions/1:request-publish", null, null,
                    null);
            assertThat(requested.status()).as(requested.body()).isEqualTo(200);
            Reply self = send(port, "alice", "POST", "/rule-sets/card-fee/versions/1:approve", null, null,
                    requested.etag());
            assertThat(self.status()).as(self.body()).isEqualTo(403);
            assertThat(self.json().path("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
            Reply approval = send(port, FORMER_EXEMPT_ID, "POST", "/rule-sets/card-fee/versions/1:approve", null, null,
                    requested.etag());
            assertThat(approval.status()).as(approval.body()).isEqualTo(403);
            assertThat(approval.json().path("code").asString()).isEqualTo("PERMISSION_DENIED");

            // and the database refuses a row its own requester approved under the former exempt id
            JdbcClient jdbc = host.getBean(JdbcClient.class);
            assertThatThrownBy(() -> jdbc.sql("""
                    UPDATE rle_version SET requested_by = :id, approved_by = :id, approved_at = now(), status = 'PUBLISHED'
                    WHERE tenant_id = 'bank-a' AND version_num = 1
                    """).param("id", FORMER_EXEMPT_ID).update())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("rle_version_four_eyes");
            assertThat(jdbc.sql("SELECT status FROM rle_version WHERE tenant_id = 'bank-a' AND version_num = 1")
                    .query(String.class).single()).isEqualTo("PENDING_APPROVAL");
        }
    }

    private static ConfigurableApplicationContext start(String database, String profile) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                        + "/" + database,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0",
                "--HORUS_DEMO_PASSWORD=test-only-password"));
        args.addAll(TestTokens.arguments());
        if (!profile.isEmpty()) {
            args.add("--spring.profiles.active=" + profile);
        }
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    private static String freshDatabase() {
        String name = "dev_exemption_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    private static Reply send(String port, String user, String method, String path, byte[] body, String contentType,
            String ifMatch) throws Exception {
        if (ReviewedContent.decides(path)) {
            byte[] bound = ReviewedContent.withHash(path, body, p -> send(port, user, "GET", p, null, null, null).body());
            if (bound != body && contentType == null) {
                contentType = "application/json";
            }
            body = bound;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/bpm/rules/v1"
                        + path))
                .header("Authorization", TestTokens.bearerFor(user, "bank-a", ALL_RULE_PERMISSIONS))
                .header("Idempotency-Key", "k-" + System.nanoTime())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.headers().firstValue("ETag").orElse(null), response.body());
    }

    record Reply(int status, String etag, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
