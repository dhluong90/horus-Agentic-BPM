package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The embedded cockpit against the real example host on the {@code dev} profile, over HTTP: the host's form
 * sign-in, the cockpit page and its registry, and the two metadata calls the cockpit makes with the session
 * (CKP-001 case A, real host). Needs the cockpit bundle; skipped with -Dcockpit.bundle.skip=true.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "HORUS_DEMO_PASSWORD=test-only-password")
@ActiveProfiles("dev")
@Testcontainers
class CockpitEmbeddedIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path SCHEMAS = Path.of("..", "..", "cockpit", "api", "schema");
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Environment environment;

    @Test
    void TR_SEC_09_without_a_host_session_the_cockpit_sends_the_user_to_the_host_sign_in() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        for (String path : List.of("/bpm/cockpit/", "/bpm/cockpit/index.html", "/bpm/cockpit/cockpit-hosts.json")) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(url(path)).header("Accept", "text/html").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).as(path)
                    .hasValueSatisfying(location -> assertThat(location).endsWith("/login"));
            assertThat(response.body()).as(path).doesNotContain("<div id=\"root\">").doesNotContain("Loan origination");
        }
    }

    @Test
    void TR_SEC_09_the_host_sign_in_page_is_branded_strict_and_lists_demo_users_on_the_dev_profile_only() throws Exception {
        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> page = browser.send(HttpRequest.newBuilder(url("/login?lang=vi")).header("Accept", "text/html").GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<html lang=\"vi\">", "HORUS Cockpit", "Loan origination", "data-username=\"lan\"", "data-username=\"minh\"")
                .doesNotContain("test-only-password").doesNotContainPattern("<script(?![^>]*\\ssrc=)").doesNotContain("<style");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp ->
                assertThat(csp).contains("script-src 'self'").contains("frame-ancestors 'none'").doesNotContain("unsafe-inline"));
        assertThat(page.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        // Its styles and script load before sign-in.
        for (String asset : List.of("/sign-in/sign-in.css", "/sign-in/sign-in.js")) {
            assertThat(get(browser, asset).statusCode()).as(asset).isEqualTo(200);
        }
    }

    @Test
    void CKP_001_after_sign_in_the_user_returns_to_the_cockpit_even_after_the_browser_asked_for_a_favicon() throws Exception {
        HttpClient browser = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        // What a browser does: open the cockpit, follow it to the sign-in page, ask for the favicon there.
        HttpResponse<String> cockpit = browser.send(HttpRequest.newBuilder(url("/bpm/cockpit/"))
                .header("Accept", "text/html").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(cockpit.statusCode()).isEqualTo(302);
        get(browser, "/favicon.ico");

        HttpResponse<String> result = signIn(browser, "lan");

        // Spring Security marks a restored request with "?continue"; the path is what matters.
        assertThat(result.headers().firstValue("Location").map(l -> URI.create(l).getPath())).hasValue("/bpm/cockpit/");
    }

    @Test
    void CKP_001_signed_in_user_gets_the_cockpit_page_and_its_one_host_registry() throws Exception {
        HttpClient browser = signedIn("lan", new CookieManager());

        HttpResponse<String> page = get(browser, "/bpm/cockpit/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\">");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp ->
                assertThat(csp).contains("script-src 'self'").contains("frame-ancestors 'self'").doesNotContain("unsafe-inline"));
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        // Same-origin framing only, even though the host's Spring Security sends DENY everywhere else.
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("SAMEORIGIN");

        HttpResponse<String> registry = get(browser, "/bpm/cockpit/cockpit-hosts.json");
        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(errors("cockpit-hosts.v1.schema.json", registry.body())).isEmpty();
        assertThat(registry.body()).isEqualTo("{\"schemaVersion\":1,\"auth\":{\"mode\":\"host-session\"},"
                + "\"hosts\":[{\"id\":\"rule-engine-only\",\"label\":\"Loan origination\",\"baseUrl\":\"\",\"environment\":\"demo\"}],"
                + "\"ui\":{\"defaultLocale\":\"en\"}}");
    }

    @Test
    void CKP_001_cockpit_session_calls_meta_and_me_and_both_match_the_cockpit_schemas() throws Exception {
        for (String user : List.of("lan", "minh")) {
            HttpClient browser = signedIn(user, new CookieManager());
            get(browser, "/bpm/cockpit/index.html");

            // The calls the cockpit makes: same origin, session cookie, the headers of its host client.
            HttpResponse<String> meta = cockpitGet(browser, "/api/bpm/meta");
            HttpResponse<String> me = cockpitGet(browser, "/api/bpm/me");

            assertThat(meta.statusCode()).as(user).isEqualTo(200);
            assertThat(me.statusCode()).as(user).isEqualTo(200);
            assertThat(errors("bpm-meta.v1.schema.json", meta.body())).as(user).isEmpty();
            assertThat(errors("bpm-me.v1.schema.json", me.body())).as(user).isEmpty();
            assertThat(JSON.readTree(meta.body()).path("hostId").asString()).isEqualTo("rule-engine-only");
            JsonNode body = JSON.readTree(me.body());
            assertThat(body.path("user").path("id").asString()).isEqualTo(user);
            assertThat(body.path("tenant").path("current").asString()).isEqualTo("bank-a");
            assertThat(body.path("permissions").toString()).isEqualTo(
                    user.equals("lan") ? "[\"rules:read\",\"rules:author\"]" : "[\"rules:read\",\"rules:approve\"]");
        }
    }

    @Test
    void CKP_001_after_the_cockpit_page_a_call_that_changes_data_needs_x_xsrf_token_from_the_cookie() throws Exception {
        CookieManager cookies = new CookieManager();
        HttpClient browser = signedIn("lan", cookies);
        get(browser, "/bpm/cockpit/index.html");
        String xsrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue)
                .findFirst().orElseThrow(() -> new AssertionError("no XSRF-TOKEN cookie after the cockpit page"));

        HttpResponse<String> withoutToken = browser.send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> withToken = browser.send(HttpRequest.newBuilder(url("/api/bpm/me"))
                .header("X-XSRF-TOKEN", xsrf).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        // Without the token the CSRF filter refuses the call; with it the call passes CSRF and reaches
        // Spring MVC, which answers that /me has no POST.
        assertThat(withoutToken.statusCode()).isEqualTo(403);
        assertThat(withToken.statusCode()).isEqualTo(405);
    }

    private HttpClient signedIn(String user, CookieManager cookies) throws Exception {
        HttpClient browser = HttpClient.newBuilder().cookieHandler(cookies).build();
        signIn(browser, user);
        return browser;
    }

    private HttpResponse<String> signIn(HttpClient browser, String user) throws Exception {
        HttpResponse<String> form = get(browser, "/login");
        Matcher csrf = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(form.body());
        assertThat(csrf.find()).as("CSRF token on the sign-in form").isTrue();
        HttpResponse<String> result = browser.send(HttpRequest.newBuilder(url("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=" + user + "&password=test-only-password&_csrf="
                        + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8)))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).doesNotContain("error"));
        return result;
    }

    private HttpResponse<String> get(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(url(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> cockpitGet(HttpClient browser, String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(url(path))
                .header("Accept", "application/json").header("X-Requested-With", "XMLHttpRequest")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<Error> errors(String schemaFile, String json) throws Exception {
        Schema schema = REGISTRY.getSchema(Files.readString(SCHEMAS.resolve(schemaFile)), InputFormat.JSON);
        return schema.validate(json, InputFormat.JSON);
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
