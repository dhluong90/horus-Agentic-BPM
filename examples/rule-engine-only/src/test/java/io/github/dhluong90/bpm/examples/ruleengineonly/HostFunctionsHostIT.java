package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Host functions on the example host (RLE-055 to RLE-058, PD-119): the host registers {@code segmentOf} as an
 * {@code ExpressionFunction} bean; a card fee table uses it; an unregistered function is refused at import; a
 * redeploy with another version label logs one WARN and shows the change in {@code /api/bpm/meta}; a function
 * registered twice stops the host. One fresh database per case.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class HostFunctionsHostIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: balance,      type: money }
              - { name: tenureMonths, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { balance: "segmentOf(balance, tenureMonths) == 'PRIORITY'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "50000 VND" } }
            """;

    /** The host's function: a pure calculation over its arguments. */
    static ExpressionFunction segmentOf(String version) {
        return new ExpressionFunction("segmentOf", List.of(ValueType.MONEY, ValueType.NUMBER), ValueType.STRING, version, 1,
                arguments -> ((Money) arguments.get(0)).minorUnits() >= 500_000_000L
                        && ((BigDecimal) arguments.get(1)).intValue() >= 24 ? "PRIORITY" : "MASS");
    }

    @Configuration(proxyBeanMethods = false)
    static class V1 {
        @Bean
        ExpressionFunction hostSegmentOf() {
            return HostFunctionsHostIT.segmentOf("2027.1");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class V2 {
        @Bean
        ExpressionFunction hostSegmentOf() {
            return HostFunctionsHostIT.segmentOf("2027.2");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Twice {
        @Bean
        ExpressionFunction first() {
            return HostFunctionsHostIT.segmentOf("2027.1");
        }

        @Bean
        ExpressionFunction second() {
            return HostFunctionsHostIT.segmentOf("2027.1");
        }
    }

    @Test
    void RLE_055_a_host_function_prices_a_card_fee_and_an_unregistered_one_is_refused_at_import() throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext host = start(db, V1.class)) {
            int port = port(host);

            Reply refused = send(port, TestTokens.bearer("alice"), "POST", "/rule-sets",
                    CARD_FEE.replace("segmentOf(", "riskScore("), "application/yaml");
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.json().path("code").asString()).isEqualTo("VALIDATION_FAILED");
            JsonNode violation = refused.json().path("violations").get(0);
            assertThat(violation.path("ruleId").asString()).isEqualTo("R1");
            assertThat(violation.path("column").asString()).isEqualTo("balance");
            assertThat(violation.path("code").asString()).isEqualTo("FORBIDDEN_CONSTRUCT");
            assertThat(violation.path("message").asString()).contains("Unknown function 'riskScore'");

            assertThat(send(port, TestTokens.bearer("alice"), "POST", "/rule-sets", CARD_FEE, "application/yaml")
                    .status()).isEqualTo(201);
            assertThat(requestAndApprove(port).status()).isEqualTo(200);

            Reply priority = send(port, TestTokens.bearer("teller"), "POST", "/decisions/card-fee:evaluate",
                    "{\"inputs\": {\"balance\": {\"currency\": \"VND\", \"amount\": 600000000}, \"tenureMonths\": 36}}",
                    "application/json");
            assertThat(priority.status()).isEqualTo(200);
            assertThat(priority.json().path("matchedRuleIds").get(0).asString()).isEqualTo("R1");
            assertThat(priority.json().path("outputs").path("fee").path("amount").asLong()).isZero();
            Reply mass = send(port, TestTokens.bearer("teller"), "POST", "/decisions/card-fee:evaluate",
                    "{\"inputs\": {\"balance\": {\"currency\": \"VND\", \"amount\": 600000000}, \"tenureMonths\": 12}}",
                    "application/json");
            assertThat(mass.json().path("matchedRuleIds").get(0).asString()).isEqualTo("R2");
            assertThat(mass.json().path("outputs").path("fee").path("amount").asLong()).isEqualTo(50_000);

            // the policy is shown in /meta, and nothing has changed yet
            JsonNode settings = meta(port, TestTokens.bearer("teller"));
            assertThat(settings.path("evaluation.function-version-change").asString()).isEqualTo("warn");
            assertThat(settings.path("evaluation.functions-changed").asString()).isEmpty();
        }
    }

    @Test
    void RLE_058_a_redeploy_with_another_version_warns_once_and_meta_shows_the_change_to_its_tenant(
            CapturedOutput output) throws Exception {
        String db = freshDatabase();
        try (ConfigurableApplicationContext host = start(db, V1.class)) {
            int port = port(host);
            send(port, TestTokens.bearer("alice"), "POST", "/rule-sets", CARD_FEE, "application/yaml");
            requestAndApprove(port);
        }

        try (ConfigurableApplicationContext host = start(db, V2.class)) {
            int port = port(host);

            assertThat(output.getAll()).contains("Host function segmentOf was approved with version 2027.1 and is"
                    + " registered with version 2027.2").contains("bank-a/card-fee v1");
            assertThat(meta(port, TestTokens.bearer("teller")).path("evaluation.functions-changed").asString())
                    .isEqualTo("segmentOf:2027.1>2027.2");
            // another tenant's caller sees no change in versions it does not own
            assertThat(meta(port, TestTokens.bearer("carol")).path("evaluation.functions-changed").asString()).isEmpty();
            Reply decided = send(port, TestTokens.bearer("teller"), "POST", "/decisions/card-fee:evaluate",
                    "{\"inputs\": {\"balance\": {\"currency\": \"VND\", \"amount\": 600000000}, \"tenureMonths\": 36}}",
                    "application/json");
            assertThat(decided.status()).as("warn is the default: evaluation goes on").isEqualTo(200);
        }

        // refuse: the same redeploy now fails closed until a new version is approved
        try (ConfigurableApplicationContext host = start(db, V2.class,
                "horus.bpm.rules.evaluation.function-version-change=refuse")) {
            int port = port(host);
            Reply refused = send(port, TestTokens.bearer("teller"), "POST", "/decisions/card-fee:evaluate",
                    "{\"inputs\": {\"balance\": {\"currency\": \"VND\", \"amount\": 600000000}, \"tenureMonths\": 36}}",
                    "application/json");
            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.json().path("code").asString()).isEqualTo("EVALUATION_ERROR");
            assertThat(refused.json().path("detail").asString()).startsWith("FUNCTION_CHANGED").contains("segmentOf");
            assertThat(meta(port, TestTokens.bearer("teller")).path("evaluation.function-version-change").asString())
                    .isEqualTo("refuse");
        }
    }

    @Test
    void RLE_058_a_function_registered_twice_or_a_bad_setting_stops_the_host_naming_it() {
        assertThatThrownBy(() -> start(freshDatabase(), Twice.class)).hasStackTraceContaining(
                "Function 'segmentOf' is registered twice");
        assertThatThrownBy(() -> start(freshDatabase(), V1.class,
                "horus.bpm.rules.evaluation.function-version-change=maybe")).hasStackTraceContaining(
                "horus.bpm.rules.evaluation.function-version-change must be warn or refuse, was 'maybe'");
    }

    private static JsonNode meta(int port, String bearer) throws Exception {
        HttpResponse<String> meta = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/bpm/meta"))
                .header("Authorization", bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(meta.statusCode()).isEqualTo(200);
        for (JsonNode module : JSON.readTree(meta.body()).path("modules")) {
            if ("rules".equals(module.path("name").asString()) || "rules".equals(module.path("id").asString())) {
                return module.path("settings");
            }
        }
        return JSON.readTree(meta.body()).path("modules").get(0).path("settings");
    }

    private static ConfigurableApplicationContext start(String database, Class<?> functions, String... extraProperties) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort()
                        + "/" + database,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        TestTokens.properties().forEach((name, value) -> args.add("--" + name + "=" + value));
        Arrays.stream(extraProperties).forEach(property -> args.add("--" + property));
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class, functions).run(args.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext host) {
        return Integer.parseInt(host.getEnvironment().getProperty("local.server.port"));
    }

    /** Alice requests card-fee v1 and bob approves it naming the canonical hash he reviewed (PD-139). */
    private static Reply requestAndApprove(int port) throws Exception {
        String reviewed = send(port, TestTokens.bearer("alice"), "POST", "/rule-sets/card-fee/versions/1:request-publish",
                null, null).json().path("canonicalHash").asString();
        return send(port, TestTokens.bearer("bob"), "POST", "/rule-sets/card-fee/versions/1:approve",
                "{\"expectedCanonicalHash\":\"" + reviewed + "\"}", "application/json");
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
        String name = "functions_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    record Reply(int status, String body) {
        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
