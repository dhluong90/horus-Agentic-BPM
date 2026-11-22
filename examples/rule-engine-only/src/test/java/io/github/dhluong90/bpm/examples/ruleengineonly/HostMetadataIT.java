package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The example host on the default profile with bearer tokens over real HTTP, through Spring Boot's own
 * token validation: an RSA public key and the audience {@code rule-engine-only}. Lan is a pricing author
 * and Minh a pricing approver at bank-a; their token roles reach engine permissions through the role
 * mapping in {@code application.yml}. {@code HORUS_DEMO_PASSWORD} is set, yet the demo users stay off
 * because the {@code dev} profile is not active.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // The test client is java.net.CookieManager over plain http, which never returns a Secure cookie;
        // SecureCookiesIT checks the attributes the example sends by default.
        properties = {"HORUS_DEMO_PASSWORD=test-only-password", "server.servlet.session.cookie.secure=false"})
@Testcontainers
class HostMetadataIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AUDIENCE = "rule-engine-only";
    private static final KeyPair KEYS = rsaKeys();

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void databaseAndTokenValidation(DynamicPropertyRegistry registry) throws Exception {
        // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        Path publicKey = Files.createTempFile("host-metadata-it", ".pub");
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        publicKey.toFile().deleteOnExit();
        registry.add("spring.security.oauth2.resourceserver.jwt.public-key-location", () -> "file:" + publicKey);
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> AUDIENCE);
    }

    @Autowired
    private Environment environment;

    @Test
    void RLE_019_example_host_answers_meta_and_me_for_two_bearer_tokens() throws Exception {
        // When Lan and Minh call the host with tokens issued for it
        HttpResponse<String> meta = get("/api/bpm/meta", token("lan", "bank-a", "PRICING_AUTHOR", AUDIENCE));
        HttpResponse<String> lan = get("/api/bpm/me", token("lan", "bank-a", "PRICING_AUTHOR", AUDIENCE));
        HttpResponse<String> minh = get("/api/bpm/me", token("minh", "bank-a", "PRICING_APPROVER", AUDIENCE));

        // Then meta names this host and the rules engine of this build, privately cacheable
        assertThat(meta.statusCode()).isEqualTo(200);
        JsonNode metaBody = JSON.readTree(meta.body());
        assertThat(metaBody.path("hostId").asString()).isEqualTo("rule-engine-only");
        assertThat(metaBody.path("modules")).hasSize(1);
        assertThat(metaBody.path("modules").get(0).path("module").asString()).isEqualTo("rules");
        // PD-094: off by default; this profile (no dev) leaves it unset, so the engine publishes false.
        assertThat(metaBody.path("modules").get(0).path("settings").path("authoring.cockpit-drafts").isBoolean()).isTrue();
        assertThat(metaBody.path("modules").get(0).path("settings").path("authoring.cockpit-drafts").asBoolean()).isFalse();
        assertThat(metaBody.path("modules").get(0).path("settings").path("lifecycle.approver-must-differ-from").asString())
                .isEqualTo("makers");
        // TR-EXT-08: this host configures no masking key, so the caller's tenant shows unset and no key id.
        assertThat(metaBody.path("modules").get(0).path("settings").path("masking.hmac-key").asString()).isEqualTo("unset");
        assertThat(metaBody.path("modules").get(0).path("settings").has("masking.hmac-key-id")).isFalse();
        assertThat(meta.headers().allValues("Vary")).isNotEmpty();
        assertThat(metaBody.path("modules").get(0).path("engineVersion").asString())
                .isEqualTo(System.getProperty("rule-engine.version"));
        // The Rule Engine publishes the caller's tenant's masking status, so /meta revalidates every time, as /me.
        assertThat(meta.headers().firstValue("Cache-Control")).hasValue("private, no-cache");
        assertThat(meta.headers().firstValue("ETag")).isPresent();

        // And the two callers get different permission lists from their roles; /me is revalidated each time
        assertThat(strings(lan, "permissions")).containsExactly("rules:read", "rules:author");
        assertThat(strings(lan, "roles")).containsExactly("ROLE_PRICING_AUTHOR");
        assertThat(strings(minh, "permissions")).containsExactly("rules:read", "rules:approve", "rules:simulate");
        assertThat(JSON.readTree(lan.body()).path("tenant").path("current").asString()).isEqualTo("bank-a");
        assertThat(lan.headers().firstValue("Cache-Control")).hasValue("private, no-cache");
    }

    @Test
    void RLE_019_example_host_answers_401_problem_without_a_valid_token() throws Exception {
        List<String> refused = List.of("", "Bearer not-a-jwt",
                // TR-SEC-12: a well-signed token issued for another host
                token("lan", "bank-a", "PRICING_AUTHOR", "another-host"));
        for (String authorization : refused) {
            HttpResponse<String> response = get("/api/bpm/meta", authorization);

            assertThat(response.statusCode()).as(authorization).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/problem+json"));
            assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                    challenge -> assertThat(challenge).startsWith("Bearer"));
            JsonNode problem = JSON.readTree(response.body());
            assertThat(problem.path("code").asString()).isEqualTo("UNAUTHENTICATED");
            assertThat(problem.path("correlationId").asString()).isNotBlank();
            assertThat(response.body()).doesNotContain("Exception", "\tat ");
            assertThat(response.headers().allValues("Set-Cookie")).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        }
    }

    @Test
    void TR_SEC_03_example_host_refuses_a_tenant_header_that_differs_from_the_token() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .header("Authorization", token("lan", "bank-a", "PRICING_AUTHOR", AUDIENCE))
                .header("X-Tenant-Id", "bank-b").GET());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).path("code").asString()).isEqualTo("TENANT_NOT_ALLOWED");
    }

    @Test
    void TR_SEC_09_rule_engine_answers_are_no_store_on_success_and_on_every_problem() throws Exception {
        String lan = token("lan", "bank-a", "PRICING_AUTHOR", AUDIENCE);
        String teller = token("tuan", "bank-a", "TELLER", AUDIENCE);

        // When the rule engine answers with success, a not-found problem, a permission problem and a sign-in problem
        HttpResponse<String> listed = get("/api/bpm/rules/v1/rule-sets", lan);
        HttpResponse<String> notFound = get("/api/bpm/rules/v1/rule-sets/no-such-rule-set", lan);
        HttpResponse<String> denied = get("/api/bpm/rules/v1/rule-sets", teller);
        HttpResponse<String> unauthenticated = get("/api/bpm/rules/v1/rule-sets", "");

        // Then every one of them tells browsers and proxies not to store it
        List<HttpResponse<String>> answers = List.of(listed, notFound, denied, unauthenticated);
        assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(200, 404, 403, 401);
        for (HttpResponse<String> response : answers) {
            assertThat(response.headers().allValues("Cache-Control")).as(response.uri() + " " + response.statusCode())
                    .containsExactly("no-store");
        }

        // And the metadata endpoint keeps its own private caching: tenant settings make it revalidate, as /me
        assertThat(get("/api/bpm/meta", lan).headers().firstValue("Cache-Control")).hasValue("private, no-cache");
    }

    @Test
    void demo_users_are_off_outside_the_dev_profile() throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> form = client.send(HttpRequest.newBuilder(url("/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(form.body());
        assertThat(csrf.find()).isTrue();

        HttpResponse<String> signIn = client.send(HttpRequest.newBuilder(url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=lan&password=test-only-password&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> me = client.send(HttpRequest.newBuilder(url("/api/bpm/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(signIn.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).contains("error"));
        assertThat(me.statusCode()).isEqualTo(401);
    }

    private static String token(String subject, String tenant, String role, String audience) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject).audience(audience).claim("tenant_id", tenant).claim("roles", List.of(role))
                .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
        return "Bearer " + jwt.serialize();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).GET();
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static List<String> strings(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).path(field).valueStream().map(JsonNode::asString).toList();
    }

    private static KeyPair rsaKeys() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
